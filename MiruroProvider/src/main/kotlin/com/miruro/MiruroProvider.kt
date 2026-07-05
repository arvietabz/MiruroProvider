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
import kotlinx.coroutines.delay

/**
 * MiruroProvider — standalone CloudStream extension.
 *
 * Two upstreams used directly (no self-hosted API):
 *   1. AniList GraphQL  — metadata (search, collections, info)
 *   2. Miruro /api/secure/pipe — episodes + video sources
 *      (request  = base64url(json payload), no gzip)
 *      (response = base64url(gzip(json)))
 *
 * Cloudflare bypass: /api/secure/pipe is behind CF Bot Management.
 * We replicate the full Chrome header set that api.py's curl_cffi
 * sends — especially sec-fetch-site: same-origin — which is the
 * primary CF signal Miruro's ruleset checks.
 * Residential / non-datacenter IPs are required (same constraint as api.py).
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
    //  Cloudflare bypass headers — mirrors api.py's HEADERS dict exactly.
    //
    //  Critical fields:
    //    • sec-fetch-site: same-origin  → tells CF this is a same-site XHR
    //    • sec-fetch-mode: cors
    //    • sec-fetch-dest: empty
    //    • Origin / Referer pointing to miruro.tv
    //    • Chrome User-Agent + matching sec-ch-ua triplet
    //
    //  api.py also uses curl_cffi impersonate="chrome110" for TLS fingerprint
    //  spoofing (JA3/JA4). OkHttp cannot replicate that, but the header set
    //  below handles the CF check from residential/non-datacenter IPs.
    // ─────────────────────────────────────────────────────────────────────────
    private val cfHeaders = mapOf(
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

    // ─── PROVIDER CONFIG ──────────────────────────────────────────────────────
    private val providers = listOf(
        ProviderConfig("bee",  "sub", "Bee",  hasSsub = true),
        ProviderConfig("hop",  "sub", "Hop",  hasSsub = true),
        ProviderConfig("dune", "sub", "Dune", hasSsub = true),
        ProviderConfig("kiwi", "sub", "Kiwi", hasSsub = false),
        ProviderConfig("ally", "sub", "Ally", hasSsub = false)
    )

    data class ProviderConfig(
        val id: String,
        val category: String,
        val label: String,
        val hasSsub: Boolean
    )

    // ─── AniList GraphQL field fragments ──────────────────────────────────────

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

    // ─── AniList query with rate-limit retry ──────────────────────────────────

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
                throw ErrorLoadingException("AniList rate limit — please wait a moment and retry.")
            delay(1000L * (1L shl (attempt - 1)))
        }
    }

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

    // ─── Pipe helpers ─────────────────────────────────────────────────────────

    /**
     * Encodes the pipe request payload.
     * Mirrors api.py's _encode_pipe_request():
     *   base64.urlsafe_b64encode(json.dumps(payload).encode()).decode().rstrip('=')
     *
     * NOTE: episode IDs fetched from the episodes pipe are used verbatim here —
     * do NOT re-encode them. api.py encodes them only because its /sources
     * endpoint receives a plain-text ID from an external HTTP client; we already
     * have the raw ID from the pipe and pass it directly.
     */
    private fun buildPipeParam(path: String, query: Map<String, Any>): String {
        // Build JSON manually to avoid an extra Jackson dependency import;
        // values are controlled (Int, String, Boolean, List<String>) so this is safe.
        val queryJson = query.entries.joinToString(",", "{", "}") { (k, v) ->
            when (v) {
                is String  -> "\"$k\":\"$v\""
                is Boolean -> "\"$k\":$v"
                is List<*> -> "\"$k\":${v.joinToString(",", "[", "]") { "\"$it\"" }}"
                else       -> "\"$k\":$v"
            }
        }
        val payload = """{"path":"$path","method":"GET","query":$queryJson,"body":null,"version":"0.1.0"}"""
        return Base64.encodeToString(
            payload.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    /**
     * Decodes the pipe response.
     * Mirrors api.py's _decode_pipe_response():
     *   encoded_str += '=' * (4 - len(encoded_str) % 4)   ← re-add stripped padding
     *   base64.urlsafe_b64decode(encoded_str) → gzip.decompress → json
     */
    private fun decodePipeResponse(text: String): String {
        val stripped = text.trim()
        val padded   = stripped + "=".repeat((4 - stripped.length % 4) % 4)
        val bytes    = Base64.decode(padded, Base64.URL_SAFE)
        return GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).readText()
    }

    /**
     * Sends a GET to the Miruro pipe with CF-bypass headers and returns decoded JSON.
     */
    private suspend fun pipeGet(path: String, query: Map<String, Any>): String {
        val e        = buildPipeParam(path, query)
        val response = app.get("$pipeUrl?e=$e", headers = cfHeaders)
        if (response.code != 200) {
            throw ErrorLoadingException(
                "Pipe request failed (HTTP ${response.code}). " +
                "CF may be blocking this IP — residential/non-datacenter IPs required."
            )
        }
        return decodePipeResponse(response.text.trim())
    }

    /**
     * Fetches episode list for a given provider.
     * Passes both anilistId AND provider in the query — this is what the old
     * (working) provider did and what the pipe backend actually expects.
     */
    private suspend fun fetchEpisodes(anilistId: Int, provider: String): List<EpisodeItem> {
        return try {
            val decoded = pipeGet(
                "episodes",
                mapOf("anilistId" to anilistId, "provider" to provider)
            )
            val resp = AppUtils.parseJson<PipeEpisodesResponse>(decoded)
            val eps  = resp.providers?.let { p ->
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

    // ─── URL helpers ──────────────────────────────────────────────────────────

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

    private fun trailerUrl(trailer: AnilistTrailer?): String? {
        val id = trailer?.id ?: return null
        return when (trailer.site?.lowercase()) {
            "youtube"     -> "https://www.youtube.com/watch?v=$id"
            "dailymotion" -> "https://www.dailymotion.com/video/$id"
            else          -> null
        }
    }

    // ─── MAIN PAGE ────────────────────────────────────────────────────────────

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

    // ─── SEARCH ───────────────────────────────────────────────────────────────

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

    // ─── LOAD ─────────────────────────────────────────────────────────────────

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

        // Tags: genres + non-spoiler tags ranked ≥ 60
        val tagList = (media.genres.orEmpty() + (media.tags.orEmpty()
            .filter { it.isMediaSpoiler != true && (it.rank ?: 0) >= 60 }
            .mapNotNull { it.name }))
            .distinct()

        // Episodes: try kiwi first, fall back through other providers
        val episodeData = providers.firstNotNullOfOrNull { cfg ->
            fetchEpisodes(anilistId, cfg.id).takeIf { it.isNotEmpty() }
        } ?: emptyList()

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

        // Characters → voice actors
        val actorList = media.characters?.edges?.mapNotNull { edge ->
            val charName = edge.node?.name?.full ?: return@mapNotNull null
            ActorData(
                actor      = Actor(charName, edge.node.image?.large),
                roleString = edge.voiceActors?.firstOrNull()?.name?.full
            )
        }

        // Recommendations: relations first, then AniList recs
        val recFromRelations = media.relations?.edges
            ?.mapNotNull { it.node }
            ?.mapNotNull { it.toSearchResult() }.orEmpty()
        val recFromRecs = media.recommendations?.nodes
            ?.mapNotNull { it.mediaRecommendation?.toSearchResult() }.orEmpty()
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

    // ─── LOAD LINKS ───────────────────────────────────────────────────────────

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
            providers.map { providerConfig ->
                async {
                    try {
                        // Fetch episode list for this provider (with provider key — mirrors old working code)
                        val episodeList = fetchEpisodes(anilistId, providerConfig.id)
                        if (episodeList.isEmpty()) return@async ProviderResult(emptyList(), emptyList(), false)

                        val episodeId = episodeList
                            .firstOrNull { it.number == epNum }
                            ?.id ?: return@async ProviderResult(emptyList(), emptyList(), false)

                        // Send the episode ID verbatim — no extra base64 encoding.
                        // api.py's enc_id step only applies when the ID arrives as a
                        // plain-text HTTP param from an external client; we already
                        // have the raw pipe ID here.
                        val sourcesQuery: Map<String, Any> = mapOf(
                            "episodeId" to episodeId,
                            "provider"  to providerConfig.id,
                            "category"  to providerConfig.category,
                            "anilistId" to anilistId
                        )

                        // Use cfHeaders for the sources request too — same CF-protected endpoint
                        val sourcesE = buildPipeParam("sources", sourcesQuery)
                        val sourcesResponse = app.get("$pipeUrl?e=$sourcesE", headers = cfHeaders)
                        val decoded = decodePipeResponse(sourcesResponse.text.trim())
                        val resp    = AppUtils.parseJson<SourcesResponse>(decoded)

                        // Resolve stream block: ssub > hsub > flat
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
                                    subtitles.add(PendingSubtitle(
                                        lang = sub.label ?: sub.language ?: "Unknown",
                                        url  = sub.file
                                    ))
                                }
                            }
                        }
                        val engTrack  = subtitles.firstOrNull {
                            it.lang.lowercase().contains("english") || it.lang.lowercase() == "en"
                        }
                        val reordered = if (engTrack != null) {
                            listOf(engTrack) + subtitles.filter { it !== engTrack }
                        } else subtitles

                        // Per-provider stream selection
                        val toCollect: List<StreamItem> = when (providerConfig.id) {
                            "kiwi" -> {
                                val hls   = rawStreams.filter { it.type == "hls"   && it.isActive == true }
                                val embed = rawStreams.filter { it.type == "embed" && it.isActive == true }
                                hls + embed
                            }
                            "bee", "hop", "dune" -> {
                                rawStreams.filter { it.type == "hls" } + rawStreams.filter { it.type == "embed" }
                            }
                            "ally" -> rawStreams.filter { it.type == "embed" }.sortedBy { it.priority ?: Int.MAX_VALUE }
                            else   -> emptyList()
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

    // ─── DATA CLASSES: AniList ────────────────────────────────────────────────

    data class AnilistTitle(val romaji: String?, val english: String?, val native: String? = null)
    data class AnilistCoverImage(val large: String?, val extraLarge: String? = null, val color: String? = null)
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

    data class AnilistStudios(val nodes: List<AnilistStudioNode>?)
    data class AnilistStudioNode(val name: String?, val isAnimationStudio: Boolean? = null)

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

    // ─── DATA CLASSES: Miruro pipe ────────────────────────────────────────────

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
