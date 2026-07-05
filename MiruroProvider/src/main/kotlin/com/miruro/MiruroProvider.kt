package com.miruro

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import android.util.Base64
import android.util.Log
import android.widget.Toast
import java.util.zip.GZIPInputStream
import java.io.ByteArrayInputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

class MiruroProvider : MainAPI() {

    override var mainUrl = "https://www.miruro.tv"
    override var name    = "Miruro"
    override val hasMainPage        = true
    override var lang               = "en"
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.Anime, TvType.AnimeMovie)

    private val TAG        = "MiruroProvider"
    private val anilistUrl = "https://graphql.anilist.co"
    private val pipeUrl    = "$mainUrl/api/secure/pipe"

    // ── Toast helper ─────────────────────────────────────────────────────────
    // Shows a short on-screen message so you can debug without logcat.
    // CloudStream runs on the main thread for UI; we use the app context.
    private fun toast(msg: String) {
        try {
            val ctx = AcraApplication.context ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {}
    }

    // ── CF bypass headers ─────────────────────────────────────────────────────
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

    private val jsonMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val streamProviders = listOf(
        StreamProviderConfig("kiwi", "sub", "Kiwi"),
        StreamProviderConfig("bee",  "sub", "Bee"),
        StreamProviderConfig("hop",  "sub", "Hop"),
        StreamProviderConfig("dune", "sub", "Dune"),
        StreamProviderConfig("ally", "sub", "Ally")
    )
    data class StreamProviderConfig(val id: String, val category: String, val label: String)

    // ── AniList field fragments ───────────────────────────────────────────────
    private val mediaListFields = """
        id title { romaji english native }
        coverImage { large extraLarge } bannerImage format season seasonYear
        episodes duration status averageScore meanScore popularity favourites
        genres source countryOfOrigin isAdult
        studios(isMain: true) { nodes { name isAnimationStudio } }
        nextAiringEpisode { episode airingAt timeUntilAiring }
        startDate { year month day } endDate { year month day }
    """.trimIndent()

    private val mediaFullFields = """
        id idMal title { romaji english native }
        description(asHtml: false) coverImage { large extraLarge color }
        bannerImage format season seasonYear episodes duration status
        averageScore meanScore popularity favourites trending genres
        tags { name rank isMediaSpoiler } source countryOfOrigin isAdult
        synonyms siteUrl trailer { id site thumbnail }
        studios { nodes { name isAnimationStudio } }
        nextAiringEpisode { episode airingAt timeUntilAiring }
        startDate { year month day } endDate { year month day }
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
                node { id title { romaji english } coverImage { large } format type status }
            }
        }
        recommendations(sort: RATING_DESC, perPage: 10) {
            nodes { mediaRecommendation { id title { romaji english } coverImage { large } format } }
        }
    """.trimIndent()

    // ── AniList query ─────────────────────────────────────────────────────────
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
                throw ErrorLoadingException("AniList rate-limited — retry in a moment.")
            delay(1000L * (1L shl (attempt - 1)))
        }
    }

    private suspend fun fetchCollection(
        sortType: String, status: String? = null, page: Int, perPage: Int = 20
    ): List<AnilistMedia> {
        val statusArg = if (status != null) ", status: $status" else ""
        val gql = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
                Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    media(type: ANIME, sort: [$sortType]$statusArg) { $mediaListFields }
                }
            }
        """.trimIndent()
        return AppUtils.parseJson<AnilistCollectionResponse>(
            anilistQuery(gql, mapOf("page" to page, "perPage" to perPage))
        ).data.page.media
    }

    // ── Pipe helpers ──────────────────────────────────────────────────────────
    private fun encodePipeRequest(path: String, query: Map<String, Any>): String {
        val payload = mapOf(
            "path" to path, "method" to "GET",
            "query" to query, "body" to null, "version" to "0.1.0"
        )
        return Base64.encodeToString(
            jsonMapper.writeValueAsString(payload).toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun decodePipeResponse(text: String): String {
        val trimmed = text.trimEnd()
        val padded  = trimmed + "=".repeat((4 - trimmed.length % 4) % 4)
        val compressed = Base64.decode(padded, Base64.URL_SAFE)
        return GZIPInputStream(ByteArrayInputStream(compressed))
            .bufferedReader(Charsets.UTF_8).readText()
    }

    /**
     * Core pipe call. Toasts the HTTP status so you can see CF blocks on device.
     * On non-200 it toasts the first 120 chars of the response body.
     */
    private suspend fun pipeGet(path: String, query: Map<String, Any>): String {
        val e        = encodePipeRequest(path, query)
        val response = app.get("$pipeUrl?e=$e", headers = cfBypassHeaders)
        val code     = response.code
        Log.d(TAG, "pipeGet[$path] HTTP $code")

        if (code != 200) {
            val body    = response.text.take(120)
            val msg     = "[$path] HTTP $code: $body"
            Log.e(TAG, "pipeGet FAIL: $msg")
            toast("⛔ Pipe $path → HTTP $code\n$body")
            throw ErrorLoadingException(msg)
        }

        return try {
            decodePipeResponse(response.text.trim())
        } catch (ex: Exception) {
            val raw = response.text.take(120)
            val msg = "[$path] decode failed: ${ex.message} | raw: $raw"
            Log.e(TAG, msg, ex)
            toast("⛔ Pipe $path decode fail\n${ex.message}")
            throw ErrorLoadingException(msg)
        }
    }

    // ── ID translation ────────────────────────────────────────────────────────
    private fun translateId(encodedId: String): String {
        return try {
            val padded  = encodedId + "=".repeat((4 - encodedId.length % 4) % 4)
            val decoded = Base64.decode(padded, Base64.URL_SAFE).toString(Charsets.UTF_8)
            if (':' in decoded) decoded else encodedId
        } catch (_: Exception) { encodedId }
    }

    /**
     * Mirrors api.py _inject_source_slugs() for one episode.
     * "animepahe:xyz", provider=kiwi, id=178005, cat=sub, ep=1
     *   → "watch/kiwi/178005/sub/animepahe-1"
     * This full string is then base64-encoded as episodeId in the sources query.
     */
    private fun injectSourceSlug(
        translatedId: String, provider: String,
        anilistId: Int, category: String, epNumber: Int
    ): String {
        val prefix = if (':' in translatedId) translatedId.split(":")[0] else translatedId
        return "watch/$provider/$anilistId/$category/$prefix-$epNumber"
    }

    // ── Episodes ──────────────────────────────────────────────────────────────
    /**
     * One pipe call → translate IDs → inject slugs.
     * Toasts a summary of what was found so you can see it on device.
     */
    private suspend fun fetchAllProviderEpisodes(anilistId: Int): Map<String, List<EpisodeItem>> {
        return try {
            val decoded = pipeGet("episodes", mapOf("anilistId" to anilistId))
            Log.d(TAG, "episodes JSON (first 400): ${decoded.take(400)}")

            val resp  = AppUtils.parseJson<PipeEpisodesResponse>(decoded)
            val provs = resp.providers

            if (provs == null) {
                toast("⚠️ Episodes: providers field is null\n${decoded.take(100)}")
                return emptyMap()
            }

            val result = buildMap<String, List<EpisodeItem>> {
                fun process(name: String, data: ProviderData?) {
                    val rawList = data?.episodes?.sub
                    if (rawList.isNullOrEmpty()) return
                    put(name, rawList.map { ep ->
                        val translated = translateId(ep.id)
                        val slug       = injectSourceSlug(translated, name, anilistId, "sub", ep.number)
                        Log.d(TAG, "  $name ep#${ep.number}: ${ep.id} → $slug")
                        ep.copy(id = slug)
                    })
                }
                process("kiwi", provs.kiwi)
                process("bee",  provs.bee)
                process("hop",  provs.hop)
                process("dune", provs.dune)
                process("ally", provs.ally)
            }

            // ── Toast: tell user what providers/episode counts we got ──────────
            if (result.isEmpty()) {
                toast("⚠️ Episodes: pipe OK but 0 providers parsed\nJSON: ${decoded.take(100)}")
            } else {
                val summary = result.entries.joinToString(" | ") { "${it.key}:${it.value.size}ep" }
                toast("✅ Episodes loaded\n$summary")
            }

            result
        } catch (ex: Exception) {
            Log.e(TAG, "fetchAllProviderEpisodes failed: ${ex.message}", ex)
            // Only toast if it wasn't already toasted inside pipeGet
            if (ex !is ErrorLoadingException) {
                toast("⛔ Episodes parse error\n${ex.message?.take(100)}")
            }
            emptyMap()
        }
    }

    // ── Sources ───────────────────────────────────────────────────────────────
    /**
     * base64-encodes the full slug path as episodeId — mirrors api.py enc_id.
     * Toasts the slug being requested and the stream count returned.
     */
    private suspend fun fetchSources(
        episodeSlugId: String, provider: String, category: String, anilistId: Int
    ): SourcesResponse {
        val encodedEpisodeId = Base64.encodeToString(
            episodeSlugId.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        Log.d(TAG, "fetchSources: slug=$episodeSlugId enc=$encodedEpisodeId")
        toast("🔍 [$provider] fetching\n$episodeSlugId")

        val decoded = pipeGet("sources", mapOf(
            "episodeId" to encodedEpisodeId,
            "provider"  to provider,
            "category"  to category,
            "anilistId" to anilistId
        ))

        Log.d(TAG, "sources JSON (first 400): ${decoded.take(400)}")

        return try {
            val resp = AppUtils.parseJson<SourcesResponse>(decoded)

            // ── Toast: raw stream counts from every block ─────────────────────
            val topCount  = resp.streams?.size ?: 0
            val ssubCount = resp.ssub?.streams?.size ?: 0
            val hsubCount = resp.hsub?.streams?.size ?: 0
            val subCount  = resp.subtitles?.size ?: 0
            toast("[$provider] streams: top=$topCount ssub=$ssubCount hsub=$hsubCount subs=$subCount\n${decoded.take(80)}")

            resp
        } catch (ex: Exception) {
            Log.e(TAG, "sources parse failed: ${ex.message}", ex)
            toast("⛔ [$provider] parse fail\n${ex.message?.take(80)}\nraw: ${decoded.take(80)}")
            throw ex
        }
    }

    // ── URL helpers ───────────────────────────────────────────────────────────
    private fun slugify(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9\\s]"), "").trim().replace(Regex("\\s+"), "-")

    // Robust: regex instead of blind split()[4] — handles slugs with numbers
    private fun extractAnilistId(url: String): Int? =
        Regex("/watch/(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()

    private fun AnilistMedia.toSearchResult(): SearchResponse? {
        val titleStr = title.english ?: title.romaji ?: return null
        return newAnimeSearchResponse(titleStr, "$mainUrl/watch/$id/${slugify(titleStr)}",
            if (format == "MOVIE") TvType.AnimeMovie else TvType.Anime
        ) { posterUrl = coverImage.large }
    }

    private fun AnilistRelatedMedia.toSearchResult(): SearchResponse? {
        val titleStr = title.english ?: title.romaji ?: return null
        return newAnimeSearchResponse(titleStr, "$mainUrl/watch/$id/${slugify(titleStr)}",
            if (format == "MOVIE") TvType.AnimeMovie else TvType.Anime
        ) { posterUrl = coverImage?.large }
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
                    AppUtils.parseJson<AnilistCollectionResponse>(anilistQuery(gql))
                        .data.page.media.mapNotNull { it.toSearchResult() }
                }
            }
            "trending"  -> fetchCollection("TRENDING_DESC", page = page).mapNotNull { it.toSearchResult() }
            "popular"   -> fetchCollection("POPULARITY_DESC", page = page).mapNotNull { it.toSearchResult() }
            "upcoming"  -> fetchCollection("POPULARITY_DESC", "NOT_YET_RELEASED", page).mapNotNull { it.toSearchResult() }
            "recent"    -> fetchCollection("START_DATE_DESC", "RELEASING", page).mapNotNull { it.toSearchResult() }
            "finished"  -> fetchCollection("TRENDING_DESC", "FINISHED", page).mapNotNull { it.toSearchResult() }
            "schedule"  -> {
                val gql = """
                    query (${'$'}page: Int, ${'$'}perPage: Int) {
                        Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                            airingSchedules(notYetAired: true, sort: TIME) { media { $mediaListFields } }
                        }
                    }
                """.trimIndent()
                AppUtils.parseJson<AnilistScheduleResponse>(
                    anilistQuery(gql, mapOf("page" to page, "perPage" to 20))
                ).data.page.airingSchedules.map { it.media }.distinctBy { it.id }
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
                AppUtils.parseJson<AnilistCollectionResponse>(
                    anilistQuery(gql, mapOf("page" to page, "perPage" to 20))
                ).data.page.media.mapNotNull { it.toSearchResult() }
            }
            else -> emptyList()
        }
        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = false),
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
        return AppUtils.parseJson<AnilistCollectionResponse>(
            anilistQuery(gql, mapOf("search" to query, "page" to 1, "perPage" to 20))
        ).data.page.media.mapNotNull { it.toSearchResult() }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LOAD
    // ══════════════════════════════════════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse {
        val anilistId = extractAnilistId(url)
            ?: throw ErrorLoadingException("Cannot extract anilistId from: $url")

        val gql = """
            query (${'$'}id: Int) {
                Media(id: ${'$'}id, type: ANIME) { $mediaFullFields }
            }
        """.trimIndent()
        val media = AppUtils.parseJson<AnilistLoadResponse>(
            anilistQuery(gql, mapOf("id" to anilistId))
        ).data.media

        val title   = media.title.english ?: media.title.romaji ?: "Unknown"
        val isMovie = media.format == "MOVIE"
        val plot    = media.description?.replace(Regex("<.*?>"), "")
        val slug    = slugify(media.title.romaji ?: title)

        val tagList = (media.genres.orEmpty() + (media.tags.orEmpty()
            .filter { it.isMediaSpoiler != true && (it.rank ?: 0) >= 60 }
            .mapNotNull { it.name })).distinct()

        val allProviderEpisodes = fetchAllProviderEpisodes(anilistId)
        val bestEpisodes = streamProviders
            .firstNotNullOfOrNull { cfg -> allProviderEpisodes[cfg.id]?.takeIf { it.isNotEmpty() } }

        val episodes = if (!bestEpisodes.isNullOrEmpty()) {
            bestEpisodes.map { ep ->
                newEpisode("$mainUrl/watch/$anilistId/$slug?ep=${ep.number}") {
                    this.episode     = ep.number
                    this.name        = ep.title
                    this.posterUrl   = ep.image
                    this.description = ep.description
                }
            }
        } else {
            val count = when {
                media.status == "RELEASING" && media.nextAiringEpisode?.episode != null ->
                    (media.nextAiringEpisode.episode ?: 1) - 1
                media.episodes != null -> media.episodes
                else -> 1
            }
            (1..count).map { n ->
                newEpisode("$mainUrl/watch/$anilistId/$slug?ep=$n") {
                    this.episode   = n
                    this.posterUrl = media.coverImage.large
                }
            }
        }

        val actorList = media.characters?.edges?.mapNotNull { edge ->
            val charName = edge.node?.name?.full ?: return@mapNotNull null
            ActorData(Actor(charName, edge.node.image?.large),
                edge.voiceActors?.firstOrNull()?.name?.full)
        }

        val recList = ((media.relations?.edges?.mapNotNull { it.node?.toSearchResult() }.orEmpty())
            + (media.recommendations?.nodes?.mapNotNull { it.mediaRecommendation?.toSearchResult() }.orEmpty()))
            .distinctBy { it.url }.ifEmpty { null }

        val yearVal = media.startDate?.year ?: media.seasonYear

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                posterUrl = media.coverImage.large; backgroundPosterUrl = media.bannerImage
                this.plot = plot; tags = tagList; score = Score.from100(media.averageScore)
                this.year = yearVal; this.duration = media.duration
                this.actors = actorList; recommendations = recList
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                posterUrl = media.coverImage.large; backgroundPosterUrl = media.bannerImage
                this.plot = plot; tags = tagList; score = Score.from100(media.averageScore)
                this.year = yearVal; this.duration = media.duration
                this.actors = actorList; recommendations = recList
                showStatus = if (media.status == "RELEASING") ShowStatus.Ongoing else ShowStatus.Completed
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LOAD LINKS
    // ══════════════════════════════════════════════════════════════════════════
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val anilistId = extractAnilistId(data)
        if (anilistId == null) {
            toast("⛔ loadLinks: no anilistId in\n$data")
            return false
        }
        val epNum = data.substringAfter("?ep=", "").toIntOrNull() ?: 1
        Log.d(TAG, "loadLinks: anilistId=$anilistId epNum=$epNum")
        toast("▶️ loadLinks ep#$epNum id=$anilistId")

        val allProviderEpisodes = fetchAllProviderEpisodes(anilistId)

        if (allProviderEpisodes.isEmpty()) {
            toast("⛔ No providers — episode fetch failed (CF block?)")
            return false
        }

        data class PendingStream(val linkName: String, val stream: StreamItem)
        data class PendingSubtitle(val lang: String, val url: String)
        data class ProviderResult(
            val streams: List<PendingStream>, val subtitles: List<PendingSubtitle>, val isSoft: Boolean
        )

        val results: List<ProviderResult> = coroutineScope {
            streamProviders.map { cfg ->
                async {
                    try {
                        val episodeList = allProviderEpisodes[cfg.id]
                        if (episodeList == null) {
                            Log.d(TAG, "${cfg.id}: not in map")
                            return@async ProviderResult(emptyList(), emptyList(), false)
                        }

                        val ep = episodeList.firstOrNull { it.number == epNum }
                        if (ep == null) {
                            val available = episodeList.map { it.number }
                            Log.d(TAG, "${cfg.id}: ep#$epNum not found, have: $available")
                            toast("⚠️ ${cfg.id}: ep#$epNum not found\nhave: $available")
                            return@async ProviderResult(emptyList(), emptyList(), false)
                        }

                        val resp = fetchSources(ep.id, cfg.id, cfg.category, anilistId)

                        val (rawStreams, rawSubtitles, isSoft) = when {
                            resp.ssub?.streams?.isNotEmpty() == true ->
                                Triple(resp.ssub.streams, resp.ssub.subtitles, true)
                            resp.hsub?.streams?.isNotEmpty() == true ->
                                Triple(resp.hsub.streams, resp.hsub.subtitles, false)
                            resp.streams?.isNotEmpty() == true ->
                                Triple(resp.streams, resp.subtitles, false)
                            else -> {
                                Log.d(TAG, "${cfg.id}: all stream blocks empty")
                                return@async ProviderResult(emptyList(), emptyList(), false)
                            }
                        }

                        // Deduplicate subtitles; English first
                        val seenFiles = mutableSetOf<String>()
                        val subtitles = mutableListOf<PendingSubtitle>()
                        rawSubtitles?.forEach { sub ->
                            if (!sub.file.isNullOrBlank()) {
                                val fname = sub.file.substringAfterLast("/")
                                if (seenFiles.add(fname))
                                    subtitles.add(PendingSubtitle(sub.label ?: sub.language ?: "Unknown", sub.file))
                            }
                        }
                        val eng = subtitles.firstOrNull {
                            it.lang.lowercase().contains("english") || it.lang.lowercase() == "en"
                        }
                        val reordered = if (eng != null) listOf(eng) + subtitles.filter { it !== eng } else subtitles

                        val toCollect: List<StreamItem> = when (cfg.id) {
                            "kiwi" -> {
                                val hls   = rawStreams.filter { it.type == "hls"   && it.isActive == true }
                                val embed = rawStreams.filter { it.type == "embed" && it.isActive != false }
                                hls + embed
                            }
                            "bee", "hop", "dune" ->
                                rawStreams.filter { it.type == "hls" } + rawStreams.filter { it.type == "embed" }
                            "ally" ->
                                rawStreams.filter { it.type == "embed" }.sortedBy { it.priority ?: Int.MAX_VALUE }
                            else -> emptyList()
                        }

                        if (toCollect.isEmpty()) return@async ProviderResult(emptyList(), reordered, isSoft)

                        ProviderResult(
                            toCollect.map { s ->
                                val label = "${cfg.label} ${s.fansub ?: s.server ?: "Stream"} ${s.quality ?: ""} ${if (isSoft) "[S]" else ""}".trim()
                                PendingStream(label, s)
                            },
                            reordered, isSoft
                        )
                    } catch (ex: Exception) {
                        Log.e(TAG, "${cfg.id} loadLinks exception: ${ex.message}", ex)
                        if (ex !is ErrorLoadingException)
                            toast("⛔ ${cfg.id} error\n${ex.message?.take(80)}")
                        ProviderResult(emptyList(), emptyList(), false)
                    }
                }
            }.awaitAll()
        }

        val sorted = results.sortedByDescending { it.isSoft }
        sorted.forEach { r -> r.subtitles.forEach { s -> subtitleCallback(SubtitleFile(s.lang, s.url)) } }
        sorted.forEach { r ->
            r.streams.forEach { p ->
                if (p.stream.type == "hls") {
                    callback(newExtractorLink(name, p.linkName, p.stream.url, ExtractorLinkType.M3U8) {
                        this.referer = p.stream.referer ?: "$mainUrl/"
                        this.quality = when (p.stream.quality) {
                            "1080p" -> Qualities.P1080.value; "720p" -> Qualities.P720.value
                            "480p"  -> Qualities.P480.value;  "360p" -> Qualities.P360.value
                            else    -> Qualities.Unknown.value
                        }
                    })
                } else {
                    loadExtractor(p.stream.url, p.stream.referer ?: "$mainUrl/", subtitleCallback, callback)
                }
            }
        }

        val found = sorted.any { it.streams.isNotEmpty() }
        Log.d(TAG, "loadLinks done: found=$found")
        if (!found) toast("⛔ No streams found from any provider")
        return found
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
    data class AnilistCharacterEdge(val role: String?, val node: AnilistCharacterNode?, val voiceActors: List<AnilistVoiceActor>?)
    data class AnilistCharacters(val edges: List<AnilistCharacterEdge>?)
    data class AnilistRelatedMedia(
        val id: Int, val title: AnilistTitle, val coverImage: AnilistCoverImage?,
        val format: String?, val type: String? = null, val status: String? = null
    )
    data class AnilistRelationEdge(val relationType: String?, val node: AnilistRelatedMedia?)
    data class AnilistRelations(val edges: List<AnilistRelationEdge>?)
    data class AnilistRecommendationNode(val mediaRecommendation: AnilistRelatedMedia?)
    data class AnilistRecommendations(val nodes: List<AnilistRecommendationNode>?)
    data class AnilistMedia(
        val id: Int, val idMal: Int? = null, val title: AnilistTitle,
        val description: String? = null, val coverImage: AnilistCoverImage,
        val bannerImage: String?, val format: String?, val season: String? = null,
        val seasonYear: Int? = null, val episodes: Int?, val duration: Int? = null,
        val status: String?, val averageScore: Int?, val meanScore: Int? = null,
        val popularity: Int? = null, val favourites: Int? = null, val trending: Int? = null,
        val genres: List<String>?, val tags: List<AnilistTag>? = null, val source: String? = null,
        val countryOfOrigin: String? = null, val isAdult: Boolean? = null,
        val synonyms: List<String>? = null, val siteUrl: String? = null,
        val trailer: AnilistTrailer? = null, val studios: AnilistStudios?,
        val nextAiringEpisode: AnilistNextAiring?, val startDate: AnilistFuzzyDate? = null,
        val endDate: AnilistFuzzyDate? = null, val characters: AnilistCharacters? = null,
        val relations: AnilistRelations? = null, val recommendations: AnilistRecommendations? = null
    )
    data class AnilistPage(val media: List<AnilistMedia>)
    data class AnilistCollectionData(@JsonProperty("Page") val page: AnilistPage)
    data class AnilistCollectionResponse(val data: AnilistCollectionData)
    data class AnilistAiringScheduleItem(
        val episode: Int? = null, val airingAt: Long? = null,
        val timeUntilAiring: Long? = null, val media: AnilistMedia
    )
    data class AnilistSchedulePage(val airingSchedules: List<AnilistAiringScheduleItem>)
    data class AnilistScheduleData(@JsonProperty("Page") val page: AnilistSchedulePage)
    data class AnilistScheduleResponse(val data: AnilistScheduleData)
    data class AnilistLoadData(@JsonProperty("Media") val media: AnilistMedia)
    data class AnilistLoadResponse(val data: AnilistLoadData)

    // ══════════════════════════════════════════════════════════════════════════
    //  DATA CLASSES — Miruro pipe
    // ══════════════════════════════════════════════════════════════════════════
    data class PipeEpisodesResponse(val providers: ProvidersData?, val mappings: MappingsData? = null)
    data class MappingsData(val anilistId: Int? = null, val malId: Int? = null, val kitsuId: Int? = null)
    data class ProvidersData(
        @JsonProperty("kiwi") val kiwi: ProviderData?,
        @JsonProperty("ally") val ally: ProviderData?,
        @JsonProperty("bee")  val bee:  ProviderData?,
        @JsonProperty("hop")  val hop:  ProviderData?,
        @JsonProperty("dune") val dune: ProviderData?
    )
    data class ProviderData(val episodes: EpisodesData?)
    data class EpisodesData(
        @JsonProperty("sub") val sub: List<EpisodeItem>?,
        @JsonProperty("dub") val dub: List<EpisodeItem>?
    )
    data class EpisodeItem(
        val id: String, val number: Int, val title: String?,
        val description: String?, val image: String?,
        val airDate: String?, val duration: Int?, val filler: Boolean?
    )
    data class SourcesResponse(
        val streams: List<StreamItem>?, val subtitles: List<SubtitleItem>?,
        val download: String?, val ssub: SsubBlock?, val hsub: SsubBlock?
    )
    data class SsubBlock(
        val streams: List<StreamItem>?, val subtitles: List<SubtitleItem>?,
        val provider: String?, val thumbnail: String?
    )
    data class StreamItem(
        val url: String, val type: String?, val quality: String?,
        val audio: String?, val fansub: String?, val isActive: Boolean?,
        val server: String?, val priority: Int?,
        @JsonProperty("default") val isDefault: Boolean?,
        val referer: String?, val codec: String?, val resolution: ResolutionItem?
    )
    data class ResolutionItem(val width: Int?, val height: Int?)
    data class SubtitleItem(
        val file: String?, val label: String?, val kind: String?,
        @JsonProperty("default") val isDefault: Boolean?,
        val language: String?, val format: String?, val encoding: String?
    )
}
