package com.miruro

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import android.util.Base64
import java.util.zip.GZIPInputStream
import java.io.ByteArrayInputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * MiruroProvider — a from-scratch Kotlin port of `api.py`'s logic.
 *
 * `api.py` is a FastAPI wrapper around two upstreams:
 *   1. AniList's public GraphQL API (metadata: search, collections, info)
 *   2. Miruro's internal `/api/secure/pipe` endpoint (episodes + video sources,
 *      requests/responses are base64(gzip(json)))
 *
 * This provider re-implements every one of api.py's routes as a Kotlin
 * function that talks to those same two upstreams directly — no Python
 * server involved. Each section below is labeled with the api.py route
 * it mirrors.
 */
class MiruroProvider : MainAPI() {

    override var mainUrl = "https://www.miruro.tv"
    override var name = "Miruro"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val anilistUrl = "https://graphql.anilist.co"
    private val pipeUrl = "$mainUrl/api/secure/pipe"

    // ────────────────────────────────────────────────────────────
    //  Providers behind the Miruro pipe (episodes/sources routes)
    // ────────────────────────────────────────────────────────────
    private val streamProviders = listOf(
        StreamProviderConfig("bee",  "sub", "Bee"),
        StreamProviderConfig("hop",  "sub", "Hop"),
        StreamProviderConfig("dune", "sub", "Dune"),
        StreamProviderConfig("kiwi", "sub", "Kiwi"),
        StreamProviderConfig("ally", "sub", "Ally")
    )

    data class StreamProviderConfig(val id: String, val category: String, val label: String)

    // ══════════════════════════════════════════════════════════════
    //  ANILIST — shared GraphQL fragments
    //  (mirrors api.py's MEDIA_LIST_FIELDS / MEDIA_FULL_FIELDS)
    // ══════════════════════════════════════════════════════════════

    private val mediaListFields = """
        id
        title { romaji english native }
        coverImage { large extraLarge }
        bannerImage
        format
        season
        seasonYear
        episodes
        duration
        status
        averageScore
        meanScore
        popularity
        favourites
        genres
        source
        countryOfOrigin
        isAdult
        studios(isMain: true) { nodes { name isAnimationStudio } }
        nextAiringEpisode { episode airingAt timeUntilAiring }
        startDate { year month day }
        endDate { year month day }
    """.trimIndent()

    private val mediaFullFields = """
        id
        idMal
        title { romaji english native }
        description(asHtml: false)
        coverImage { large extraLarge color }
        bannerImage
        format
        season
        seasonYear
        episodes
        duration
        status
        averageScore
        meanScore
        popularity
        favourites
        trending
        genres
        tags { name rank isMediaSpoiler }
        source
        countryOfOrigin
        isAdult
        synonyms
        siteUrl
        trailer { id site thumbnail }
        studios { nodes { name isAnimationStudio } }
        nextAiringEpisode { episode airingAt timeUntilAiring }
        startDate { year month day }
        endDate { year month day }
        characters(sort: [ROLE, RELEVANCE], perPage: 25) {
            edges {
                role
                node { name { full native } image { large } }
                voiceActors(language: JAPANESE) { name { full native } image { large } }
            }
        }
        relations {
            edges {
                relationType(version: 2)
                node {
                    id
                    title { romaji english }
                    coverImage { large }
                    format
                    type
                    status
                }
            }
        }
        recommendations(sort: RATING_DESC, perPage: 10) {
            nodes {
                mediaRecommendation {
                    id
                    title { romaji english }
                    coverImage { large }
                    format
                }
            }
        }
    """.trimIndent()

    /** Mirrors api.py's `_anilist_query()` — real GraphQL variables, no string interpolation of user input. */
    private suspend fun anilistQuery(query: String, variables: Map<String, Any?> = emptyMap()): String {
        return app.post(
            anilistUrl,
            json = mapOf("query" to query, "variables" to variables),
            headers = mapOf("Content-Type" to "application/json")
        ).text
    }

    /** Mirrors api.py's `_fetch_collection()` — generic sort/status collection query. */
    private suspend fun fetchCollection(sortType: String, status: String? = null, page: Int, perPage: Int = 20): List<AnilistMedia> {
        val statusArg = if (status != null) ", status: $status" else ""
        val gql = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
                Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    media(type: ANIME, sort: [$sortType]$statusArg) { $mediaListFields }
                }
            }
        """.trimIndent()
        val text = anilistQuery(gql, mapOf("page" to page, "perPage" to perPage))
        return AppUtils.parseJson<AnilistCollectionResponse>(text).data.page.media
    }

    private fun slugify(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
    }

    private fun AnilistMedia.toSearchResult(): SearchResponse? {
        val titleStr = title.english ?: title.romaji ?: return null
        val slug     = slugify(titleStr)
        val watchUrl = "$mainUrl/watch/$id/$slug"
        val tvType   = if (format == "MOVIE") TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(titleStr, watchUrl, tvType) {
            posterUrl = coverImage.large
        }
    }

    private fun AnilistRelatedMedia.toSearchResult(): SearchResponse? {
        val titleStr = title.english ?: title.romaji ?: return null
        val slug     = slugify(titleStr)
        val watchUrl = "$mainUrl/watch/$id/$slug"
        val tvType   = if (format == "MOVIE") TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(titleStr, watchUrl, tvType) {
            posterUrl = coverImage?.large
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  MIRURO PIPE — mirrors api.py's _encode_pipe_request /
    //  _decode_pipe_response (base64 + gzip request/response over
    //  /api/secure/pipe)
    // ══════════════════════════════════════════════════════════════

    private fun buildPipeParam(path: String, query: Map<String, Any>): String {
        val payload = """{"path":"$path","method":"GET","query":${
            query.entries.joinToString(",", "{", "}") { (k, v) ->
                when (v) {
                    is String  -> "\"$k\":\"$v\""
                    is Boolean -> "\"$k\":$v"
                    is List<*> -> "\"$k\":${v.joinToString(",", "[", "]") { "\"$it\"" }}"
                    else       -> "\"$k\":$v"
                }
            }
        },"body":null}"""
        return Base64.encodeToString(
            payload.toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun decodePipeResponse(text: String): String {
        val bytes = Base64.decode(text, Base64.URL_SAFE)
        return GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader().readText()
    }

    private suspend fun pipeGet(path: String, query: Map<String, Any>): String {
        val e = buildPipeParam(path, query)
        val response = app.get(
            "$pipeUrl?e=$e",
            headers = mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl)
        )
        return decodePipeResponse(response.text)
    }

    /** Mirrors api.py's `/episodes/{anilist_id}` for a single provider. */
    private suspend fun fetchEpisodes(anilistId: Int, provider: String): List<EpisodeItem> {
        return try {
            val decoded = pipeGet("episodes", mapOf("anilistId" to anilistId, "provider" to provider))
            val resp = AppUtils.parseJson<PipeEpisodesResponse>(decoded)
            val eps = resp.providers?.let { p ->
                when (provider) {
                    "kiwi" -> p.kiwi?.episodes
                    "ally" -> p.ally?.episodes
                    "bee"  -> p.bee?.episodes
                    "hop"  -> p.hop?.episodes
                    "dune" -> p.dune?.episodes
                    else   -> null
                }
            }?.sub
            eps ?: emptyList()
        } catch (ex: Exception) {
            emptyList()
        }
    }

    /** Mirrors api.py's multi-provider `/episodes` payload: try every provider, use the first that has data. */
    private suspend fun fetchEpisodesAnyProvider(anilistId: Int): List<EpisodeItem> {
        for (provider in streamProviders) {
            val eps = fetchEpisodes(anilistId, provider.id)
            if (eps.isNotEmpty()) return eps
        }
        return emptyList()
    }

    // ══════════════════════════════════════════════════════════════
    //  MAIN PAGE — mirrors /spotlight, /trending, /popular,
    //  /upcoming, /recent, /schedule, and two /filter examples
    // ══════════════════════════════════════════════════════════════

    override val mainPage = mainPageOf(
        "spotlight" to "Spotlight",
        "trending"  to "Trending Now",
        "popular"   to "All-Time Popular",
        "upcoming"  to "Upcoming",
        "recent"    to "Airing Now",
        "schedule"  to "Airing Schedule",
        "finished"  to "Recently Finished",
        "movies"    to "Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home: List<SearchResponse> = when (request.data) {

            // ── mirrors /spotlight (fixed perPage 10, no real pagination) ──
            "spotlight" -> {
                if (page > 1) emptyList() else {
                    val gql = """
                        query {
                            Page(page: 1, perPage: 10) {
                                media(sort: [TRENDING_DESC, POPULARITY_DESC], type: ANIME) { $mediaListFields }
                            }
                        }
                    """.trimIndent()
                    val text = anilistQuery(gql)
                    AppUtils.parseJson<AnilistCollectionResponse>(text)
                        .data.page.media.mapNotNull { it.toSearchResult() }
                }
            }

            // ── mirrors /trending ───────────────────────────────────
            "trending" -> fetchCollection("TRENDING_DESC", page = page).mapNotNull { it.toSearchResult() }

            // ── mirrors /popular ────────────────────────────────────
            "popular" -> fetchCollection("POPULARITY_DESC", page = page).mapNotNull { it.toSearchResult() }

            // ── mirrors /upcoming ────────────────────────────────────
            "upcoming" -> fetchCollection("POPULARITY_DESC", "NOT_YET_RELEASED", page).mapNotNull { it.toSearchResult() }

            // ── mirrors /recent ──────────────────────────────────────
            "recent" -> fetchCollection("START_DATE_DESC", "RELEASING", page).mapNotNull { it.toSearchResult() }

            // ── mirrors /schedule (airingSchedules root query) ───────
            "schedule" -> {
                val gql = """
                    query (${'$'}page: Int, ${'$'}perPage: Int) {
                        Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                            airingSchedules(notYetAired: true, sort: TIME) {
                                media { $mediaListFields }
                            }
                        }
                    }
                """.trimIndent()
                val text = anilistQuery(gql, mapOf("page" to page, "perPage" to 20))
                AppUtils.parseJson<AnilistScheduleResponse>(text)
                    .data.page.airingSchedules
                    .map { it.media }
                    .distinctBy { it.id }
                    .mapNotNull { it.toSearchResult() }
            }

            // ── mirrors /filter?status=FINISHED&sort=TRENDING_DESC ───
            "finished" -> fetchCollection("TRENDING_DESC", "FINISHED", page).mapNotNull { it.toSearchResult() }

            // ── mirrors /filter?format=MOVIE&sort=POPULARITY_DESC ────
            "movies" -> {
                val gql = """
                    query (${'$'}page: Int, ${'$'}perPage: Int) {
                        Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                            media(type: ANIME, format: MOVIE, sort: [POPULARITY_DESC]) { $mediaListFields }
                        }
                    }
                """.trimIndent()
                val text = anilistQuery(gql, mapOf("page" to page, "perPage" to 20))
                AppUtils.parseJson<AnilistCollectionResponse>(text)
                    .data.page.media.mapNotNull { it.toSearchResult() }
            }

            else -> emptyList()
        }

        return newHomePageResponse(
            list    = HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = request.data != "spotlight"
        )
    }

    // ══════════════════════════════════════════════════════════════
    //  SEARCH — mirrors /search (sort: SEARCH_MATCH)
    // ══════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse> {
        val gql = """
            query (${'$'}search: String, ${'$'}page: Int, ${'$'}perPage: Int) {
                Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH, isAdult: false) {
                        $mediaListFields
                    }
                }
            }
        """.trimIndent()
        val text = anilistQuery(gql, mapOf("search" to query, "page" to 1, "perPage" to 20))
        return AppUtils.parseJson<AnilistCollectionResponse>(text)
            .data.page.media.mapNotNull { it.toSearchResult() }
    }

    // ══════════════════════════════════════════════════════════════
    //  LOAD — mirrors /info/{anilist_id} (full field set: tags,
    //  studios, trailer, characters/voice actors, relations, recs)
    // ══════════════════════════════════════════════════════════════

    /** Builds a playable URL for AniList's `trailer { id site }` shape. */
    private fun trailerUrl(trailer: AnilistTrailer?): String? {
        val id = trailer?.id ?: return null
        return when (trailer.site?.lowercase()) {
            "youtube"     -> "https://www.youtube.com/watch?v=$id"
            "dailymotion" -> "https://www.dailymotion.com/video/$id"
            else          -> null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val anilistId = url.split("/")[4].toInt()

        val gql = """
            query (${'$'}id: Int) {
                Media(id: ${'$'}id, type: ANIME) {
                    $mediaFullFields
                }
            }
        """.trimIndent()
        val text  = anilistQuery(gql, mapOf("id" to anilistId))
        val media = AppUtils.parseJson<AnilistLoadResponse>(text).data.media

        val title   = media.title.english ?: media.title.romaji ?: "Unknown"
        val isMovie = media.format == "MOVIE"
        val plot    = media.description?.replace(Regex("<.*?>"), "")
        val slug    = slugify(media.title.romaji ?: title)

        // ── Tags: genres + non-spoiler tags ranked above 60 ─────────
        val tagList = (media.genres.orEmpty() + (media.tags.orEmpty()
            .filter { it.isMediaSpoiler != true && (it.rank ?: 0) >= 60 }
            .mapNotNull { it.name }))
            .distinct()

        // ── Episodes: try every provider, first non-empty wins ──────
        val episodeData = fetchEpisodesAnyProvider(anilistId)
        val episodes = if (episodeData.isNotEmpty()) {
            episodeData.map { ep ->
                newEpisode("$mainUrl/watch/$anilistId/$slug?ep=${ep.number}") {
                    this.episode     = ep.number
                    this.name        = ep.title
                    this.posterUrl   = ep.image
                    this.description = ep.description
                }
            }
        } else {
            val episodeCount = when {
                media.status == "RELEASING" && media.nextAiringEpisode?.episode != null ->
                    (media.nextAiringEpisode.episode ?: 1) - 1
                media.episodes != null -> media.episodes
                else -> 1
            }
            (1..episodeCount).map { epNum ->
                newEpisode("$mainUrl/watch/$anilistId/$slug?ep=$epNum") {
                    this.episode   = epNum
                    this.posterUrl = media.coverImage.large
                }
            }
        }

        // ── Actors: characters + Japanese voice actor ────────────────
        val actorList = media.characters?.edges?.mapNotNull { edge ->
            val charName = edge.node?.name?.full ?: return@mapNotNull null
            ActorData(
                actor = Actor(charName, edge.node.image?.large),
                roleString = edge.voiceActors?.firstOrNull()?.name?.full
            )
        }

        // ── Recommendations: AniList recs + relations (sequels/prequels etc.) ──
        val recFromRecs = media.recommendations?.nodes?.mapNotNull { it.mediaRecommendation?.toSearchResult() }.orEmpty()
        val recFromRelations = media.relations?.edges
            ?.mapNotNull { it.node }
            ?.mapNotNull { it.toSearchResult() }
            .orEmpty()
        val recommendationList = (recFromRelations + recFromRecs).distinctBy { it.url }.ifEmpty { null }

        val trailer = trailerUrl(media.trailer)
        val yearVal = media.startDate?.year ?: media.seasonYear

        return if (isMovie) {
            newMovieLoadResponse(name = title, url = url, type = TvType.AnimeMovie, dataUrl = url) {
                posterUrl           = media.coverImage.large
                backgroundPosterUrl = media.bannerImage
                this.plot           = plot
                tags                = tagList
                score               = Score.from100(media.averageScore)
                this.year           = yearVal
                this.duration       = media.duration
                this.actors         = actorList
                this.recommendations = recommendationList
                // TODO: addTrailer() isn't resolving against the current CloudStream API jar.
                // Re-enable once you confirm the correct call for your pinned API version
                // (it may now be `this.trailers = mutableListOf(TrailerData(it))` instead).
                // trailer?.let { addTrailer(it) }
            }
        } else {
            newAnimeLoadResponse(name = title, url = url, type = TvType.Anime) {
                posterUrl           = media.coverImage.large
                backgroundPosterUrl = media.bannerImage
                this.plot           = plot
                tags                = tagList
                score               = Score.from100(media.averageScore)
                this.year           = yearVal
                this.duration       = media.duration
                this.actors         = actorList
                this.recommendations = recommendationList
                this.showStatus     = if (media.status == "RELEASING") ShowStatus.Ongoing else ShowStatus.Completed
                // TODO: addTrailer() isn't resolving against the current CloudStream API jar — see note above.
                // trailer?.let { addTrailer(it) }
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  LOAD LINKS — mirrors /sources + /watch/{provider}/{id}/{cat}/{slug}
    //  (same base64+gzip pipe protocol api.py uses internally)
    // ══════════════════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val anilistId = data.split("/")[4].toInt()
        val epNum     = data.substringAfter("?ep=").toIntOrNull() ?: 1

        data class PendingStream(val linkName: String, val stream: StreamItem)
        data class PendingSubtitle(val lang: String, val url: String)
        data class ProviderResult(
            val streams:   List<PendingStream>,
            val subtitles: List<PendingSubtitle>,
            val isSoft:    Boolean
        )

        val results: List<ProviderResult> = coroutineScope {
            streamProviders.map { providerConfig ->
                async {
                    try {
                        val episodeList = fetchEpisodes(anilistId, providerConfig.id)
                        if (episodeList.isEmpty()) return@async ProviderResult(emptyList(), emptyList(), false)

                        val episodeId = episodeList.firstOrNull { it.number == epNum }?.id
                            ?: return@async ProviderResult(emptyList(), emptyList(), false)

                        val sourcesQuery: Map<String, Any> = mapOf(
                            "episodeId" to episodeId,
                            "provider"  to providerConfig.id,
                            "category"  to providerConfig.category,
                            "anilistId" to anilistId
                        )
                        val decoded = pipeGet("sources", sourcesQuery)
                        val resp    = AppUtils.parseJson<SourcesResponse>(decoded)

                        val (rawStreams, rawSubtitles, isSoft) = when {
                            resp.ssub?.streams?.isNotEmpty() == true -> Triple(resp.ssub.streams, resp.ssub.subtitles, true)
                            resp.hsub?.streams?.isNotEmpty() == true -> Triple(resp.hsub.streams, resp.hsub.subtitles, false)
                            resp.streams?.isNotEmpty() == true       -> Triple(resp.streams, resp.subtitles, false)
                            else -> return@async ProviderResult(emptyList(), emptyList(), false)
                        }

                        val seenFiles = mutableSetOf<String>()
                        val subtitles = mutableListOf<PendingSubtitle>()
                        rawSubtitles?.forEach { sub ->
                            if (!sub.file.isNullOrBlank()) {
                                val filename = sub.file.substringAfterLast("/")
                                if (seenFiles.add(filename)) {
                                    subtitles.add(PendingSubtitle(lang = sub.label ?: sub.language ?: "Unknown", url = sub.file))
                                }
                            }
                        }

                        val reordered = run {
                            val engTrack = subtitles.firstOrNull {
                                it.lang.lowercase().contains("english") || it.lang.lowercase() == "en"
                            }
                            if (engTrack != null) listOf(engTrack) + subtitles.filter { it !== engTrack } else subtitles
                        }

                        val toCollect: List<StreamItem> = when (providerConfig.id) {
                            "kiwi" -> {
                                val activeHls   = rawStreams.filter { it.type == "hls"  && it.isActive == true }
                                val activeEmbed = rawStreams.filter { it.type == "embed" && it.isActive == true }
                                activeHls + activeEmbed
                            }
                            "bee", "hop", "dune" -> {
                                val hls   = rawStreams.filter { it.type == "hls" }
                                val embed = rawStreams.filter { it.type == "embed" }
                                hls + embed
                            }
                            "ally" -> rawStreams.filter { it.type == "embed" }.sortedBy { it.priority ?: Int.MAX_VALUE }
                            else -> emptyList()
                        }

                        if (toCollect.isEmpty()) return@async ProviderResult(emptyList(), reordered, isSoft)

                        val pending = toCollect.map { stream ->
                            val serverLabel = stream.fansub ?: stream.server ?: "Stream"
                            val qualityTag  = stream.quality ?: ""
                            val softTag     = if (isSoft) "[S]" else ""
                            PendingStream("${providerConfig.label} $serverLabel $qualityTag $softTag".trim(), stream)
                        }

                        ProviderResult(pending, reordered, isSoft)
                    } catch (ex: Exception) {
                        ProviderResult(emptyList(), emptyList(), false)
                    }
                }
            }.awaitAll()
        }

        val sorted = results.sortedByDescending { it.isSoft }

        sorted.forEach { result ->
            result.subtitles.forEach { sub -> subtitleCallback(SubtitleFile(lang = sub.lang, url = sub.url)) }
        }

        sorted.forEach { result ->
            result.streams.forEach { pending ->
                if (pending.stream.type == "hls") {
                    callback(
                        newExtractorLink(
                            source = name,
                            name   = pending.linkName,
                            url    = pending.stream.url,
                            type   = ExtractorLinkType.M3U8
                        ) {
                            this.referer = pending.stream.referer ?: "$mainUrl/"
                            this.quality = when (pending.stream.quality) {
                                "1080p" -> Qualities.P1080.value
                                "720p"  -> Qualities.P720.value
                                "480p"  -> Qualities.P480.value
                                "360p"  -> Qualities.P360.value
                                else    -> Qualities.Unknown.value
                            }
                        }
                    )
                } else {
                    loadExtractor(
                        url              = pending.stream.url,
                        referer          = pending.stream.referer ?: "$mainUrl/",
                        subtitleCallback = subtitleCallback,
                        callback         = callback
                    )
                }
            }
        }

        return sorted.any { it.streams.isNotEmpty() }
    }

    // ══════════════════════════════════════════════════════════════
    //  DATA CLASSES — AniList
    // ══════════════════════════════════════════════════════════════

    data class AnilistTitle(val romaji: String?, val english: String?, val native: String? = null)
    data class AnilistCoverImage(val large: String?, val extraLarge: String? = null, val color: String? = null)
    data class AnilistStudioNode(val name: String?, val isAnimationStudio: Boolean? = null)
    data class AnilistStudios(val nodes: List<AnilistStudioNode>?)
    data class AnilistNextAiring(val episode: Int?, val airingAt: Long?, val timeUntilAiring: Long?)
    data class AnilistFuzzyDate(val year: Int?, val month: Int?, val day: Int?)
    data class AnilistTag(val name: String?, val rank: Int?, val isMediaSpoiler: Boolean?)
    data class AnilistName(val full: String?, val native: String?)
    data class AnilistImage(val large: String?)
    data class AnilistTrailer(val id: String?, val site: String?, val thumbnail: String? = null)

    data class AnilistCharacterNode(val name: AnilistName?, val image: AnilistImage?)
    data class AnilistVoiceActor(val name: AnilistName?, val image: AnilistImage?)
    data class AnilistCharacterEdge(
        val role: String?,
        val node: AnilistCharacterNode?,
        val voiceActors: List<AnilistVoiceActor>?
    )
    data class AnilistCharacters(val edges: List<AnilistCharacterEdge>?)

    data class AnilistRelatedMedia(
        val id: Int,
        val title: AnilistTitle,
        val coverImage: AnilistCoverImage?,
        val format: String?,
        val type: String? = null,
        val status: String? = null
    )
    data class AnilistRelationEdge(val relationType: String?, val node: AnilistRelatedMedia?)
    data class AnilistRelations(val edges: List<AnilistRelationEdge>?)

    data class AnilistRecommendationNode(val mediaRecommendation: AnilistRelatedMedia?)
    data class AnilistRecommendations(val nodes: List<AnilistRecommendationNode>?)

    /** Mirrors MEDIA_LIST_FIELDS (used for search/collections/schedule) plus the extra
     *  MEDIA_FULL_FIELDS-only fields needed by load(), all nullable so one class covers both. */
    data class AnilistMedia(
        val id: Int,
        val idMal: Int? = null,
        val title: AnilistTitle,
        val description: String? = null,
        val coverImage: AnilistCoverImage,
        val bannerImage: String?,
        val format: String?,
        val season: String? = null,
        val seasonYear: Int? = null,
        val episodes: Int?,
        val duration: Int? = null,
        val status: String?,
        val averageScore: Int?,
        val meanScore: Int? = null,
        val popularity: Int? = null,
        val favourites: Int? = null,
        val trending: Int? = null,
        val genres: List<String>?,
        val tags: List<AnilistTag>? = null,
        val source: String? = null,
        val countryOfOrigin: String? = null,
        val isAdult: Boolean? = null,
        val synonyms: List<String>? = null,
        val siteUrl: String? = null,
        val trailer: AnilistTrailer? = null,
        val studios: AnilistStudios?,
        val nextAiringEpisode: AnilistNextAiring?,
        val startDate: AnilistFuzzyDate? = null,
        val endDate: AnilistFuzzyDate? = null,
        val characters: AnilistCharacters? = null,
        val relations: AnilistRelations? = null,
        val recommendations: AnilistRecommendations? = null
    )

    data class AnilistPage(val media: List<AnilistMedia>)
    data class AnilistCollectionData(@JsonProperty("Page") val page: AnilistPage)
    data class AnilistCollectionResponse(val data: AnilistCollectionData)

    data class AnilistAiringScheduleItem(
        val episode: Int? = null,
        val airingAt: Long? = null,
        val timeUntilAiring: Long? = null,
        val media: AnilistMedia
    )
    data class AnilistSchedulePage(val airingSchedules: List<AnilistAiringScheduleItem>)
    data class AnilistScheduleData(@JsonProperty("Page") val page: AnilistSchedulePage)
    data class AnilistScheduleResponse(val data: AnilistScheduleData)

    data class AnilistLoadData(@JsonProperty("Media") val media: AnilistMedia)
    data class AnilistLoadResponse(val data: AnilistLoadData)

    // ══════════════════════════════════════════════════════════════
    //  DATA CLASSES — Miruro pipe (episodes / sources)
    // ══════════════════════════════════════════════════════════════

    data class PipeEpisodesResponse(val providers: ProvidersData?)
    data class ProvidersData(
        val kiwi: ProviderData?,
        val ally: ProviderData?,
        val bee:  ProviderData?,
        val hop:  ProviderData?,
        val dune: ProviderData?
    )
    data class ProviderData(val episodes: EpisodesData?)
    data class EpisodesData(val sub: List<EpisodeItem>?, val dub: List<EpisodeItem>?)
    data class EpisodeItem(
        val id: String,
        val number: Int,
        val title: String?,
        val description: String?,
        val image: String?,
        val airDate: String?,
        val duration: Int?,
        val filler: Boolean?
    )

    data class SourcesResponse(
        val streams:   List<StreamItem>?,
        val subtitles: List<SubtitleItem>?,
        val download:  String?,
        val ssub:      SsubBlock?,
        val hsub:      SsubBlock?
    )
    data class SsubBlock(
        val streams:   List<StreamItem>?,
        val subtitles: List<SubtitleItem>?,
        val provider:  String?,
        val thumbnail: String?
    )
    data class StreamItem(
        val url:      String,
        val type:     String?,
        val quality:  String?,
        val audio:    String?,
        val fansub:   String?,
        val isActive: Boolean?,
        val server:   String?,
        val priority: Int?,
        @JsonProperty("default")
        val isDefault: Boolean?,
        val referer:  String?,
        val codec:    String?,
        val resolution: ResolutionItem?
    )
    data class ResolutionItem(val width: Int?, val height: Int?)
    data class SubtitleItem(
        val file:     String?,
        val label:    String?,
        val kind:     String?,
        @JsonProperty("default")
        val isDefault: Boolean?,
        val language: String?,
        val format:   String?,
        val encoding: String?
    )
}
