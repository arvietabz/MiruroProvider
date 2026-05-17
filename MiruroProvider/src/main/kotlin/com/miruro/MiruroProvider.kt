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

class MiruroProvider : MainAPI() {

    override var mainUrl = "https://www.miruro.tv"
    override var name = "Miruro"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val anilistUrl = "https://graphql.anilist.co"
    private val pipeUrl = "$mainUrl/api/secure/pipe"

    // ─── PROVIDER CONFIG ──────────────────────────────────────────
    // kiwi: HLS + embed pairs per quality; emit only isActive==true ones
    // ally: embed-only streams (ok.ru, mp4upload, bysekoze, allanime)
    private val providers = listOf(
        ProviderConfig("kiwi", "sub", "Kiwi"),
        ProviderConfig("ally", "sub", "Ally")
    )

    data class ProviderConfig(val id: String, val category: String, val label: String)

    // ─── HELPER: slugify ──────────────────────────────────────────
    private fun slugify(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
    }

    // ─── HELPER: BrowseMedia → SearchResponse ─────────────────────
    private fun BrowseMedia.toSearchResult(isMovie: Boolean = format == "MOVIE"): SearchResponse? {
        val titleStr = title.english ?: title.romaji ?: return null
        val slug     = slugify(titleStr)
        val watchUrl = "$mainUrl/watch/$id/$slug"
        val tvType   = if (isMovie) TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(titleStr, watchUrl, tvType) {
            posterUrl = coverImage.large
        }
    }

    // ─── HELPER: AnilistMedia → SearchResponse ────────────────────
    private fun AnilistMedia.toSearchResult(): SearchResponse? {
        val titleStr = title.english ?: title.romaji ?: return null
        val slug     = slugify(titleStr)
        val watchUrl = "$mainUrl/watch/$id/$slug"
        return newAnimeSearchResponse(titleStr, watchUrl, TvType.Anime) {
            posterUrl = coverImage.large
        }
    }

    // ─── HELPER: build pipe payload ───────────────────────────────
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

    // ─── HELPER: decode pipe response ─────────────────────────────
    private fun decodePipeResponse(text: String): String {
        val bytes = Base64.decode(text, Base64.URL_SAFE)
        return GZIPInputStream(ByteArrayInputStream(bytes))
            .bufferedReader()
            .readText()
    }

    // ─── HELPER: pipe GET request ─────────────────────────────────
    private suspend fun pipeGet(path: String, query: Map<String, Any>): String {
        val e = buildPipeParam(path, query)
        val response = app.get(
            "$pipeUrl?e=$e",
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "Origin"  to mainUrl
            )
        )
        return decodePipeResponse(response.text)
    }

    // ─── HELPER: fetch episode list for a given provider ──────────
    // /episodes returns: { "providers": { "kiwi": { "episodes": { "sub": [...] } }, "ally": { ... } } }
    private suspend fun fetchEpisodes(anilistId: Int, provider: String): List<EpisodeItem> {
        return try {
            val decoded = pipeGet(
                "episodes",
                mapOf("anilistId" to anilistId, "provider" to provider)
            )

            val fromWrapper = try {
                val resp = AppUtils.parseJson<PipeEpisodesResponse>(decoded)
                resp.providers?.let { p ->
                    when (provider) {
                        "kiwi" -> p.kiwi?.episodes
                        "ally" -> p.ally?.episodes
                        else   -> null
                    }
                }?.let { eps -> eps.sub }
            } catch (e: Exception) { null }

            fromWrapper?.takeIf { it.isNotEmpty() } ?: emptyList()
        } catch (ex: Exception) {
            emptyList()
        }
    }

    // ─── MAIN PAGE ────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "recently_updated"  to "Recently Updated",
        "trending_now"      to "Trending Now",
        "recently_finished" to "Recently Finished",
        "top_movies"        to "Top Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home: List<SearchResponse> = when (request.data) {

            "recently_updated" -> {
                val now     = System.currentTimeMillis() / 1000
                val weekAgo = now - 7 * 24 * 3600
                val weekEnd = now + 7 * 24 * 3600
                val startAt = (weekAgo / 86400) * 86400
                val endAt   = (weekEnd / 86400) * 86400

                val decoded = pipeGet(
                    "schedule",
                    mapOf(
                        "startAt" to startAt,
                        "endAt"   to endAt,
                        "sort"    to listOf("TIME_DESC")
                    )
                )
                val items = AppUtils.parseJson<List<ScheduleItem>>(decoded)
                items
                    .distinctBy { it.media.id }
                    .mapNotNull { it.media.toSearchResult() }
            }

            "trending_now" -> {
                val decoded = pipeGet(
                    "search/browse",
                    mapOf(
                        "type"    to "ANIME",
                        "status"  to "RELEASING",
                        "sort"    to "TRENDING_DESC",
                        "page"    to page,
                        "perPage" to 20
                    )
                )
                AppUtils.parseJson<List<BrowseMedia>>(decoded)
                    .mapNotNull { it.toSearchResult() }
            }

            "recently_finished" -> {
                val sixMonthsAgo = run {
                    val cal = java.util.Calendar.getInstance()
                    cal.add(java.util.Calendar.MONTH, -6)
                    val y = cal.get(java.util.Calendar.YEAR)
                    val m = cal.get(java.util.Calendar.MONTH) + 1
                    val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
                    y * 10000 + m * 100 + d
                }
                val decoded = pipeGet(
                    "search/browse",
                    mapOf(
                        "type"            to "ANIME",
                        "status"          to "FINISHED",
                        "sort"            to "TRENDING_DESC",
                        "endDate_greater" to sixMonthsAgo,
                        "page"            to page,
                        "perPage"         to 20
                    )
                )
                AppUtils.parseJson<List<BrowseMedia>>(decoded)
                    .mapNotNull { it.toSearchResult() }
            }

            "top_movies" -> {
                val offset  = (page - 1) * 20
                val decoded = pipeGet(
                    "search",
                    mapOf(
                        "format" to "MOVIE",
                        "sort"   to "SCORE_DESC",
                        "limit"  to 20,
                        "offset" to offset
                    )
                )
                AppUtils.parseJson<List<BrowseMedia>>(decoded)
                    .mapNotNull { it.toSearchResult(isMovie = true) }
            }

            else -> emptyList()
        }

        return newHomePageResponse(
            list    = HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = true
        )
    }

    // ─── SEARCH ───────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val graphqlQuery = """
            query {
              Page(perPage: 20) {
                media(search: "$query", type: ANIME, isAdult: false) {
                  id
                  title { romaji english }
                  coverImage { large }
                  format
                  episodes
                  status
                }
              }
            }
        """.trimIndent()

        val response = app.post(
            anilistUrl,
            json    = mapOf("query" to graphqlQuery),
            headers = mapOf("Content-Type" to "application/json")
        )

        return AppUtils.parseJson<AnilistSearchResponse>(response.text)
            .data.page.media
            .mapNotNull { it.toSearchResult() }
    }

    // ─── LOAD ─────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val anilistId = url.split("/")[4].toInt()

        val graphqlQuery = """
            query {
              Media(id: $anilistId, type: ANIME) {
                id
                title { romaji english }
                description
                coverImage { large }
                bannerImage
                episodes
                format
                genres
                averageScore
                status
                season
                seasonYear
                studios { nodes { name } }
                nextAiringEpisode { episode }
              }
            }
        """.trimIndent()

        val anilistResponse = app.post(
            anilistUrl,
            json    = mapOf("query" to graphqlQuery),
            headers = mapOf("Content-Type" to "application/json")
        )
        val media   = AppUtils.parseJson<AnilistLoadResponse>(anilistResponse.text).data.media
        val title   = media.title.english ?: media.title.romaji ?: "Unknown"
        val isMovie = media.format == "MOVIE"
        val plot    = media.description?.replace(Regex("<.*?>"), "")
        val slug    = slugify(media.title.romaji ?: title)

        // Use kiwi as the primary source for episode metadata (titles, thumbnails, etc.)
        val episodeData = fetchEpisodes(anilistId, "kiwi")

        val episodes = if (episodeData.isNotEmpty()) {
            episodeData.map { ep: EpisodeItem ->
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

        return if (isMovie) {
            newMovieLoadResponse(
                name    = title,
                url     = url,
                type    = TvType.AnimeMovie,
                dataUrl = url
            ) {
                posterUrl           = media.coverImage.large
                backgroundPosterUrl = media.bannerImage
                this.plot           = plot
                tags                = media.genres
                score               = Score.from100(media.averageScore)
            }
        } else {
            newAnimeLoadResponse(
                name = title,
                url  = url,
                type = TvType.Anime
            ) {
                posterUrl           = media.coverImage.large
                backgroundPosterUrl = media.bannerImage
                this.plot           = plot
                tags                = media.genres
                score               = Score.from100(media.averageScore)
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    // ─── LOAD LINKS ───────────────────────────────────────────────
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
            val subtitles: List<PendingSubtitle>
        )

        // ── Fetch all providers CONCURRENTLY, preserving list order ──────────
        // coroutineScope launches each provider in parallel; awaitAll() waits
        // for every deferred before we emit anything, so the first callback()
        // call is always Bee Vidstream-2 regardless of network timing.
        val results: List<ProviderResult> = coroutineScope {
            providers.map { providerConfig ->
                async {
                    try {
                        // 1. Episode list
                        val episodeList = fetchEpisodes(anilistId, providerConfig.id)
                        if (episodeList.isEmpty()) return@async ProviderResult(emptyList(), emptyList())

                        val episodeId = episodeList
                            .firstOrNull { it.number == epNum }
                            ?.id ?: return@async ProviderResult(emptyList(), emptyList())

                        // 2. Sources query — kiwi and ally both use the basic 3 params
                        val sourcesQuery: Map<String, Any> = mapOf(
                            "episodeId" to episodeId,
                            "provider"  to providerConfig.id,
                            "category"  to providerConfig.category
                        )

                        val sourcesE = buildPipeParam("sources", sourcesQuery)
                        val sourcesResponse = app.get(
                            "$pipeUrl?e=$sourcesE",
                            headers = mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl)
                        )

                        val decoded = decodePipeResponse(sourcesResponse.text)
                        val resp    = AppUtils.parseJson<SourcesResponse>(decoded)

                        // 3. Subtitles — deduplicate by language code
                        val seenLangs  = mutableSetOf<String>()
                        val subtitles  = mutableListOf<PendingSubtitle>()
                        resp.subtitles?.forEach { sub ->
                            if (!sub.file.isNullOrBlank()) {
                                val key = sub.language ?: sub.label ?: "unknown"
                                if (seenLangs.add(key)) {
                                    subtitles.add(PendingSubtitle(
                                        lang = sub.label ?: sub.language ?: "Unknown",
                                        url  = sub.file
                                    ))
                                }
                            }
                        }

                        // 4. Streams
                        val streams = resp.streams ?: return@async ProviderResult(emptyList(), subtitles)

                        val toCollect: List<StreamItem> = when (providerConfig.id) {
                            "kiwi" -> {
                                // kiwi: isActive==true HLS first, then isActive==true embeds as fallback
                                val activeHls   = streams.filter { it.type == "hls"   && it.isActive == true }
                                val activeEmbed = streams.filter { it.type == "embed"  && it.isActive == true }
                                activeHls + activeEmbed
                            }
                            "ally" -> {
                                // ally: embed-only, sorted by priority ascending (1 = best)
                                streams.filter { it.type == "embed" }
                                    .sortedBy { it.priority ?: Int.MAX_VALUE }
                            }
                            else -> emptyList()
                        }

                        if (toCollect.isEmpty()) return@async ProviderResult(emptyList(), subtitles)

                        val pending = toCollect.map { stream ->
                            val serverLabel = stream.fansub ?: stream.server ?: "Unknown"
                            val qualityTag  = stream.quality ?: ""
                            val linkName    = "${providerConfig.label} $serverLabel $qualityTag".trim()
                            PendingStream(linkName, stream)
                        }

                        ProviderResult(pending, subtitles)

                    } catch (ex: Exception) {
                        ProviderResult(emptyList(), emptyList())
                    }
                }
            }.awaitAll()
        }

        // ── Emit in provider list order (kiwi → ally) ────────────────────────
        // Subtitles first so CloudStream can correlate them before streams arrive
        results.forEach { result ->
            result.subtitles.forEach { sub ->
                subtitleCallback(SubtitleFile(lang = sub.lang, url = sub.url))
            }
        }

        results.forEach { result ->
            result.streams.forEach { pending ->
                if (pending.stream.type == "hls") {
                    // HLS streams: emit directly with quality hint
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
                    // Embed streams (ally): hand off to CloudStream's
                    // built-in extractor chain — handles ok.ru, mp4upload, etc.
                    loadExtractor(
                        url              = pending.stream.url,
                        referer          = pending.stream.referer ?: "$mainUrl/",
                        subtitleCallback = subtitleCallback,
                        callback         = callback
                    )
                }
            }
        }

        return results.any { it.streams.isNotEmpty() }
    }

    // ─── DATA CLASSES: Schedule API ───────────────────────────────
    data class ScheduleItem(
        val episode: Int,
        val airingAt: Long,
        val timeUntilAiring: Long,
        val media: BrowseMedia
    )

    // ─── DATA CLASSES: Browse/Search API ─────────────────────────
    data class BrowseMedia(
        val id: Int,
        val idMal: Int?,
        val title: BrowseTitle,
        val coverImage: BrowseCoverImage,
        val bannerImage: String?,
        val format: String?,
        val status: String?,
        val episodes: Int?,
        val averageScore: Int?,
        val popularity: Int?,
        val genres: List<String>?,
        val type: String?,
        val isAdult: Boolean?,
        val nextAiringEpisode: BrowseNextAiring?,
        val dubLanguages: List<String>?
    )

    data class BrowseTitle(
        val native: String?,
        val romaji: String?,
        val english: String?,
        val userPreferred: String?
    )

    data class BrowseCoverImage(
        val color: String?,
        val large: String?,
        val medium: String?,
        val extraLarge: String?
    )

    data class BrowseNextAiring(
        val episode: Int?,
        val airingAt: Long?,
        val timeUntilAiring: Long?
    )

    // ─── DATA CLASSES: AniList (search + load) ───────────────────
    data class AnilistSearchResponse(val data: SearchData)
    data class SearchData(@JsonProperty("Page") val page: PageData)
    data class PageData(val media: List<AnilistMedia>)
    data class AnilistMedia(
        val id: Int,
        val title: TitleData,
        val coverImage: CoverData,
        val format: String?,
        val episodes: Int?,
        val status: String?
    )

    data class AnilistLoadResponse(val data: LoadData)
    data class LoadData(@JsonProperty("Media") val media: MediaDetail)
    data class MediaDetail(
        val id: Int,
        val title: TitleData,
        val description: String?,
        val coverImage: CoverData,
        val bannerImage: String?,
        val episodes: Int?,
        val format: String?,
        val genres: List<String>?,
        val averageScore: Int?,
        val status: String?,
        val season: String?,
        val seasonYear: Int?,
        val studios: StudiosData?,
        val nextAiringEpisode: NextAiringEpisode?
    )

    data class StudiosData(val nodes: List<StudioNode>?)
    data class StudioNode(val name: String?)
    data class NextAiringEpisode(val episode: Int?)
    data class TitleData(val romaji: String?, val english: String?)
    data class CoverData(val large: String?)

    // ─── DATA CLASSES: Pipe Episodes API ─────────────────────────
    // The /episodes endpoint returns a "providers" object where each key
    // is a provider name, and the value contains an "episodes" object
    // with optional "sub" list.
    data class PipeEpisodesResponse(val providers: ProvidersData?)
    data class ProvidersData(
        val kiwi: ProviderData?,
        val ally: ProviderData?
    )
    data class ProviderData(val episodes: EpisodesData?)
    data class EpisodesData(
        val sub: List<EpisodeItem>?,
        val dub: List<EpisodeItem>?
    )
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

    // ─── DATA CLASSES: Pipe Sources API ──────────────────────────
    // kiwi fields: fansub, isActive, quality
    // ally fields: server, priority  (embed-only, no isActive)
    // Both have:   url, type, referer
    data class SourcesResponse(
        val streams:   List<StreamItem>?,
        val subtitles: List<SubtitleItem>?,
        val download:  String?
    )
    data class StreamItem(
        val url:      String,
        val type:     String?,
        val quality:  String?,
        val audio:    String?,
        // kiwi-style
        val fansub:   String?,
        val isActive: Boolean?,
        // ally-style
        val server:   String?,
        val priority: Int?,
        @JsonProperty("default")
        val isDefault: Boolean?,
        // shared
        val referer:  String?
    )
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
