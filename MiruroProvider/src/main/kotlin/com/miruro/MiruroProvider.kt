package com.miruro

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import android.util.Base64
import java.util.zip.GZIPInputStream
import java.io.ByteArrayInputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * MiruroProvider — standalone Kotlin port of api.py.
 *
 * KEY FIX vs previous version:
 *   api.py's _inject_source_slugs() rewrites each episode's "id" field to the
 *   full watch-path string:
 *       watch/{provider}/{anilistId}/{category}/{prefix}-{number}
 *   The /watch route then passes that full string as the episodeId to
 *   get_sources(), which base64-encodes it before putting it in the pipe query.
 *
 *   The previous Kotlin port passed only the raw translated ID (e.g.
 *   "animepahe:abc123") to fetchSources(), which caused the pipe to return no
 *   streams → "No links found".
 *
 *   The fix: after _deep_translate (translateId), also run _inject_source_slugs
 *   logic so every EpisodeItem.id becomes the full watch-path.  fetchSources()
 *   then base64-encodes that full path, exactly as api.py does.
 */
class MiruroProvider : MainAPI() {

    override var mainUrl = "https://www.miruro.tv"
    override var name = "Miruro"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val anilistUrl = "https://graphql.anilist.co"
    private val pipeUrl    = "$mainUrl/api/secure/pipe"

    // ─────────────────────────────────────────────────────────────────────────
    //  Cloudflare bypass headers — mirrors api.py HEADERS exactly.
    // ─────────────────────────────────────────────────────────────────────────
    private val cfBypassHeaders = mapOf(
        "User-Agent"         to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36",
        "Referer"            to "$mainUrl/",
        "Origin"             to mainUrl,
        "Accept"             to "*/*",
        "Accept-Language"    to "en-US,en;q=0.9",
        "Accept-Encoding"    to "gzip, deflate, br",
        "sec-fetch-site"     to "same-origin",
        "sec-fetch-mode"     to "cors",
        "sec-fetch-dest"     to "empty",
        "sec-ch-ua"          to "\"Chromium\";v=\"110\", \"Not A(Brand\";v=\"24\", \"Google Chrome\";v=\"110\"",
        "sec-ch-ua-mobile"   to "?0",
        "sec-ch-ua-platform" to "\"Windows\""
    )

    private val jsonMapper = jacksonObjectMapper()

    // ─────────────────────────────────────────────────────────────────────────
    //  Provider list
    // ─────────────────────────────────────────────────────────────────────────
    private val streamProviders = listOf(
        StreamProviderConfig("kiwi", "sub", "Kiwi"),
        StreamProviderConfig("bee",  "sub", "Bee"),
        StreamProviderConfig("hop",  "sub", "Hop"),
        StreamProviderConfig("dune", "sub", "Dune"),
        StreamProviderConfig("ally", "sub", "Ally")
    )

    data class StreamProviderConfig(val id: String, val category: String, val label: String)

    // ══════════════════════════════════════════════════════════════════════════
    //  AniList GraphQL field fragments
    // ══════════════════════════════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════════════════════════════
    //  AniList helper — with rate-limit retry
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun anilistQuery(query: String, variables: Map<String, Any?> = emptyMap()): String {
        val maxAttempts = 4
        var attempt = 0
        while (true) {
            val response = app.post(
                anilistUrl,
                json    = mapOf("query" to query, "variables" to variables),
                headers = mapOf("Content-Type" to "application/json")
            )
            val text = response.text
            val rateLimited = response.code == 429
                || text.contains("\"status\":429")
                || text.contains("Too Many Requests")
            if (!rateLimited) return text
            attempt++
            if (attempt >= maxAttempts)
                throw ErrorLoadingException("AniList is rate-limiting — please wait a moment and retry.")
            delay(1000L * (1L shl (attempt - 1)))
        }
    }

    private suspend fun fetchCollection(
        sortType: String,
        status: String? = null,
        page: Int,
        perPage: Int = 20
    ): List<AnilistMedia> {
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

    // ══════════════════════════════════════════════════════════════════════════
    //  Miruro Pipe helpers
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Mirrors api.py _encode_pipe_request().
     * JSON-serialises the payload then base64url-encodes without padding.
     */
    private fun encodePipeRequest(path: String, query: Map<String, Any>): String {
        val payload = mapOf(
            "path"    to path,
            "method"  to "GET",
            "query"   to query,
            "body"    to null,
            "version" to "0.1.0"
        )
        val json = jsonMapper.writeValueAsString(payload)
        return Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    /**
     * Mirrors api.py _decode_pipe_response().
     * Re-pads the base64url string, decodes, then gunzips.
     */
    private fun decodePipeResponse(text: String): String {
        val trimmed = text.trimEnd()
        val padded  = trimmed + "=".repeat((4 - trimmed.length % 4) % 4)
        val compressed = Base64.decode(padded, Base64.URL_SAFE)
        return GZIPInputStream(ByteArrayInputStream(compressed))
            .bufferedReader(Charsets.UTF_8)
            .readText()
    }

    /**
     * Mirrors api.py _translate_id().
     * Episode IDs from the pipe are base64url-encoded "originalId:suffix" strings.
     * Decodes them to get the raw provider ID like "animepahe:abc123".
     */
    private fun translateId(encodedId: String): String {
        return try {
            val padded  = encodedId + "=".repeat((4 - encodedId.length % 4) % 4)
            val decoded = Base64.decode(padded, Base64.URL_SAFE).toString(Charsets.UTF_8)
            if (':' in decoded) decoded else encodedId
        } catch (e: Exception) {
            encodedId
        }
    }

    /**
     * Mirrors api.py _inject_source_slugs() for a single episode.
     *
     * api.py rewrites ep["id"] to:
     *     "watch/{provider}/{anilistId}/{category}/{prefix}-{number}"
     *
     * This is the episodeId that gets base64-encoded and sent to the sources
     * pipe.  Without this rewrite the pipe returns no streams.
     *
     * Example:
     *   rawTranslatedId = "animepahe:abc123"
     *   provider        = "kiwi"
     *   anilistId       = 178005
     *   category        = "sub"
     *   epNumber        = 1
     *   → "watch/kiwi/178005/sub/animepahe-1"
     */
    private fun injectSourceSlug(
        rawTranslatedId: String,
        provider: String,
        anilistId: Int,
        category: String,
        epNumber: Int
    ): String {
        val prefix = if (':' in rawTranslatedId) rawTranslatedId.split(":")[0] else rawTranslatedId
        return "watch/$provider/$anilistId/$category/$prefix-$epNumber"
    }

    /**
     * Mirrors api.py pipeGet helper.
     * Sends GET to /api/secure/pipe with CF-bypass headers, returns decoded JSON.
     */
    private suspend fun pipeGet(path: String, query: Map<String, Any>): String {
        val e        = encodePipeRequest(path, query)
        val response = app.get("$pipeUrl?e=$e", headers = cfBypassHeaders)
        if (response.code != 200) {
            throw ErrorLoadingException(
                "Pipe request failed (HTTP ${response.code}). " +
                "If 403, Cloudflare is blocking this IP. " +
                "Residential / non-datacenter IPs are required."
            )
        }
        return decodePipeResponse(response.text.trim())
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Episodes
    //
    //  Fetches all providers in one pipe call, then:
    //    1. _deep_translate each episode id  (translateId)
    //    2. _inject_source_slugs             (injectSourceSlug)
    //
    //  After step 2, EpisodeItem.id == "watch/{provider}/{anilistId}/{cat}/{slug}"
    //  which is exactly what fetchSources() must base64-encode and send.
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Returns a map of providerName → list of EpisodeItems whose .id fields
     * have already been through both _deep_translate AND _inject_source_slugs,
     * matching api.py's /episodes response exactly.
     */
    private suspend fun fetchAllProviderEpisodes(anilistId: Int): Map<String, List<EpisodeItem>> {
        return try {
            val decoded = pipeGet("episodes", mapOf("anilistId" to anilistId))
            val resp    = AppUtils.parseJson<PipeEpisodesResponse>(decoded)
            val provs   = resp.providers ?: return emptyMap()

            buildMap {
                fun processProvider(providerName: String, data: ProviderData?) {
                    val category = "sub"
                    val rawList  = data?.episodes?.sub ?: return
                    val injected = rawList.map { ep ->
                        // Step 1: _deep_translate
                        val translatedId = translateId(ep.id)
                        // Step 2: _inject_source_slugs
                        val slugId = injectSourceSlug(translatedId, providerName, anilistId, category, ep.number)
                        ep.copy(id = slugId)
                    }
                    if (injected.isNotEmpty()) put(providerName, injected)
                }

                processProvider("kiwi", provs.kiwi)
                processProvider("bee",  provs.bee)
                processProvider("hop",  provs.hop)
                processProvider("dune", provs.dune)
                processProvider("ally", provs.ally)
            }
        } catch (ex: Exception) {
            emptyMap()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Sources
    //
    //  api.py get_sources():
    //    enc_id = base64.urlsafe_b64encode(episodeId.encode()).decode().rstrip('=')
    //    pipe query = { episodeId: enc_id, provider, category, anilistId }
    //
    //  episodeId here is the FULL slug path produced by _inject_source_slugs,
    //  e.g. "watch/kiwi/178005/sub/animepahe-1".
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun fetchSources(
        episodeSlugId: String,   // already the full "watch/…" path from injectSourceSlug
        provider: String,
        category: String,
        anilistId: Int
    ): SourcesResponse {
        // base64url-encode the full slug path, no padding — mirrors api.py enc_id
        val encodedEpisodeId = Base64.encodeToString(
            episodeSlugId.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val query = mapOf(
            "episodeId" to encodedEpisodeId,
            "provider"  to provider,
            "category"  to category,
            "anilistId" to anilistId
        )
        val decoded = pipeGet("sources", query)
        return AppUtils.parseJson<SourcesResponse>(decoded)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  URL helpers
    // ══════════════════════════════════════════════════════════════════════════

    private fun slugify(text: String): String =
        text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")

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

    // ══════════════════════════════════════════════════════════════════════════
    //  MAIN PAGE
    // ══════════════════════════════════════════════════════════════════════════

    override val mainPage = mainPageOf(
        "recent"    to "Recently Aired",
        "trending"  to "Trending Now",
        "finished"  to "Recently Finished",
        "spotlight" to "Spotlight",
        "popular"   to "All-Time Popular",
        "upcoming"  to "Upcoming",
        "schedule"  to "Airing Schedule",
        "movies"    to "Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home: List<SearchResponse> = when (request.data) {

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

            "trending"  -> fetchCollection("TRENDING_DESC", page = page).mapNotNull { it.toSearchResult() }
            "popular"   -> fetchCollection("POPULARITY_DESC", page = page).mapNotNull { it.toSearchResult() }
            "upcoming"  -> fetchCollection("POPULARITY_DESC", "NOT_YET_RELEASED", page).mapNotNull { it.toSearchResult() }
            "recent"    -> fetchCollection("START_DATE_DESC", "RELEASING", page).mapNotNull { it.toSearchResult() }
            "finished"  -> fetchCollection("TRENDING_DESC", "FINISHED", page).mapNotNull { it.toSearchResult() }

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

    // ══════════════════════════════════════════════════════════════════════════
    //  SEARCH
    // ══════════════════════════════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════════════════════════════
    //  LOAD
    // ══════════════════════════════════════════════════════════════════════════

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

        val tagList = (media.genres.orEmpty() + (media.tags.orEmpty()
            .filter { it.isMediaSpoiler != true && (it.rank ?: 0) >= 60 }
            .mapNotNull { it.name }))
            .distinct()

        // One pipe call — episodes already have fully-injected slug IDs
        val allProviderEpisodes = fetchAllProviderEpisodes(anilistId)
        val bestProviderEpisodes = streamProviders.firstNotNullOfOrNull { cfg ->
            allProviderEpisodes[cfg.id]?.takeIf { it.isNotEmpty() }
        }

        val episodes = if (!bestProviderEpisodes.isNullOrEmpty()) {
            bestProviderEpisodes.map { ep ->
                // Store the injected slug ID in the episode data URL so
                // loadLinks() can retrieve it without a second pipe call.
                // Format: {mainUrl}/watch/{anilistId}/{slug}?ep={number}
                // The actual ep.id (the slug path) is embedded as epid param.
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

        val actorList = media.characters?.edges?.mapNotNull { edge ->
            val charName = edge.node?.name?.full ?: return@mapNotNull null
            ActorData(
                actor      = Actor(charName, edge.node.image?.large),
                roleString = edge.voiceActors?.firstOrNull()?.name?.full
            )
        }

        val recFromRecs = media.recommendations?.nodes
            ?.mapNotNull { it.mediaRecommendation?.toSearchResult() }.orEmpty()
        val recFromRelations = media.relations?.edges
            ?.mapNotNull { it.node }
            ?.mapNotNull { it.toSearchResult() }.orEmpty()
        val recommendationList = (recFromRelations + recFromRecs).distinctBy { it.url }.ifEmpty { null }

        val yearVal = media.startDate?.year ?: media.seasonYear

        return if (isMovie) {
            newMovieLoadResponse(name = title, url = url, type = TvType.AnimeMovie, dataUrl = url) {
                posterUrl            = media.coverImage.large
                backgroundPosterUrl  = media.bannerImage
                this.plot            = plot
                tags                 = tagList
                score                = Score.from100(media.averageScore)
                this.year            = yearVal
                this.duration        = media.duration
                this.actors          = actorList
                this.recommendations = recommendationList
            }
        } else {
            newAnimeLoadResponse(name = title, url = url, type = TvType.Anime) {
                posterUrl            = media.coverImage.large
                backgroundPosterUrl  = media.bannerImage
                this.plot            = plot
                tags                 = tagList
                score                = Score.from100(media.averageScore)
                this.year            = yearVal
                this.duration        = media.duration
                this.actors          = actorList
                this.recommendations = recommendationList
                this.showStatus      = if (media.status == "RELEASING") ShowStatus.Ongoing else ShowStatus.Completed
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LOAD LINKS
    //
    //  Flow (mirrors api.py /watch/{provider}/{anilistId}/{category}/{slug}):
    //    1. fetchAllProviderEpisodes() — one pipe call, returns episodes with
    //       fully-injected slug IDs (e.g. "watch/kiwi/178005/sub/animepahe-1")
    //    2. For each provider, find the episode matching epNum
    //    3. fetchSources(ep.id, …) — ep.id IS the slug path; we base64-encode it
    //    4. Resolve stream block (ssub > hsub > top-level), emit links
    // ══════════════════════════════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val anilistId = data.split("/")[4].toInt()
        val epNum     = data.substringAfter("?ep=").toIntOrNull() ?: 1

        // One pipe call for all providers — episodes already have slug IDs
        val allProviderEpisodes = fetchAllProviderEpisodes(anilistId)

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
                        val episodeList = allProviderEpisodes[providerConfig.id]
                            ?: return@async ProviderResult(emptyList(), emptyList(), false)

                        // ep.id is already the full slug path after fetchAllProviderEpisodes
                        val ep = episodeList.firstOrNull { it.number == epNum }
                            ?: return@async ProviderResult(emptyList(), emptyList(), false)

                        // fetchSources base64-encodes ep.id (the slug path) — matches api.py
                        val resp = fetchSources(
                            episodeSlugId = ep.id,
                            provider      = providerConfig.id,
                            category      = providerConfig.category,
                            anilistId     = anilistId
                        )

                        // Resolve which stream block to use (ssub > hsub > top-level)
                        val (rawStreams, rawSubtitles, isSoft) = when {
                            resp.ssub?.streams?.isNotEmpty() == true ->
                                Triple(resp.ssub.streams, resp.ssub.subtitles, true)
                            resp.hsub?.streams?.isNotEmpty() == true ->
                                Triple(resp.hsub.streams, resp.hsub.subtitles, false)
                            resp.streams?.isNotEmpty() == true ->
                                Triple(resp.streams, resp.subtitles, false)
                            else -> return@async ProviderResult(emptyList(), emptyList(), false)
                        }

                        // Deduplicate subtitles by filename; English first
                        val seenFiles = mutableSetOf<String>()
                        val subtitles = mutableListOf<PendingSubtitle>()
                        rawSubtitles?.forEach { sub ->
                            if (!sub.file.isNullOrBlank()) {
                                val filename = sub.file.substringAfterLast("/")
                                if (seenFiles.add(filename)) {
                                    subtitles.add(PendingSubtitle(sub.label ?: sub.language ?: "Unknown", sub.file))
                                }
                            }
                        }
                        val engTrack = subtitles.firstOrNull {
                            it.lang.lowercase().contains("english") || it.lang.lowercase() == "en"
                        }
                        val reordered = if (engTrack != null)
                            listOf(engTrack) + subtitles.filter { it !== engTrack }
                        else subtitles

                        // Provider-specific stream selection
                        val toCollect: List<StreamItem> = when (providerConfig.id) {
                            "kiwi" -> {
                                // isActive == true for HLS; for embed use != false to handle nulls
                                val hls   = rawStreams.filter { it.type == "hls"   && it.isActive == true }
                                val embed = rawStreams.filter { it.type == "embed" && it.isActive != false }
                                hls + embed
                            }
                            "bee", "hop", "dune" -> {
                                rawStreams.filter { it.type == "hls" } +
                                rawStreams.filter { it.type == "embed" }
                            }
                            "ally" -> {
                                rawStreams.filter { it.type == "embed" }
                                    .sortedBy { it.priority ?: Int.MAX_VALUE }
                            }
                            else -> emptyList()
                        }

                        if (toCollect.isEmpty()) return@async ProviderResult(emptyList(), reordered, isSoft)

                        val pending = toCollect.map { stream ->
                            val serverLabel = stream.fansub ?: stream.server ?: "Stream"
                            val qualityTag  = stream.quality ?: ""
                            val softTag     = if (isSoft) "[S]" else ""
                            PendingStream(
                                "${providerConfig.label} $serverLabel $qualityTag $softTag".trim(),
                                stream
                            )
                        }

                        ProviderResult(pending, reordered, isSoft)
                    } catch (ex: Exception) {
                        ProviderResult(emptyList(), emptyList(), false)
                    }
                }
            }.awaitAll()
        }

        // Soft-subbed providers first
        val sorted = results.sortedByDescending { it.isSoft }

        sorted.forEach { result ->
            result.subtitles.forEach { sub ->
                subtitleCallback(SubtitleFile(lang = sub.lang, url = sub.url))
            }
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

    // ══════════════════════════════════════════════════════════════════════════
    //  DATA CLASSES — AniList
    // ══════════════════════════════════════════════════════════════════════════

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
        val type: String?   = null,
        val status: String? = null
    )
    data class AnilistRelationEdge(val relationType: String?, val node: AnilistRelatedMedia?)
    data class AnilistRelations(val edges: List<AnilistRelationEdge>?)

    data class AnilistRecommendationNode(val mediaRecommendation: AnilistRelatedMedia?)
    data class AnilistRecommendations(val nodes: List<AnilistRecommendationNode>?)

    data class AnilistMedia(
        val id: Int,
        val idMal: Int?             = null,
        val title: AnilistTitle,
        val description: String?    = null,
        val coverImage: AnilistCoverImage,
        val bannerImage: String?,
        val format: String?,
        val season: String?         = null,
        val seasonYear: Int?        = null,
        val episodes: Int?,
        val duration: Int?          = null,
        val status: String?,
        val averageScore: Int?,
        val meanScore: Int?         = null,
        val popularity: Int?        = null,
        val favourites: Int?        = null,
        val trending: Int?          = null,
        val genres: List<String>?,
        val tags: List<AnilistTag>? = null,
        val source: String?         = null,
        val countryOfOrigin: String? = null,
        val isAdult: Boolean?       = null,
        val synonyms: List<String>? = null,
        val siteUrl: String?        = null,
        val trailer: AnilistTrailer? = null,
        val studios: AnilistStudios?,
        val nextAiringEpisode: AnilistNextAiring?,
        val startDate: AnilistFuzzyDate?  = null,
        val endDate: AnilistFuzzyDate?    = null,
        val characters: AnilistCharacters? = null,
        val relations: AnilistRelations?   = null,
        val recommendations: AnilistRecommendations? = null
    )

    data class AnilistPage(val media: List<AnilistMedia>)
    data class AnilistCollectionData(@JsonProperty("Page") val page: AnilistPage)
    data class AnilistCollectionResponse(val data: AnilistCollectionData)

    data class AnilistAiringScheduleItem(
        val episode: Int?        = null,
        val airingAt: Long?      = null,
        val timeUntilAiring: Long? = null,
        val media: AnilistMedia
    )
    data class AnilistSchedulePage(val airingSchedules: List<AnilistAiringScheduleItem>)
    data class AnilistScheduleData(@JsonProperty("Page") val page: AnilistSchedulePage)
    data class AnilistScheduleResponse(val data: AnilistScheduleData)

    data class AnilistLoadData(@JsonProperty("Media") val media: AnilistMedia)
    data class AnilistLoadResponse(val data: AnilistLoadData)

    // ══════════════════════════════════════════════════════════════════════════
    //  DATA CLASSES — Miruro pipe (episodes / sources)
    // ══════════════════════════════════════════════════════════════════════════

    data class PipeEpisodesResponse(val providers: ProvidersData?, val mappings: MappingsData? = null)
    data class MappingsData(
        val anilistId: Int? = null,
        val malId: Int?     = null,
        val kitsuId: Int?   = null
    )
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
