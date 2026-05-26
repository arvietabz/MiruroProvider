package com.miruro

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * MiruroProvider
 *
 * Bypasses miruro.tv's encrypted /api/secure/pipe by calling the same
 * Consumet backend it forwards to: public-miruro-consumet-api.vercel.app
 *
 * Features:
 *  - Sub + Dub support (zoro/gogoanime both carry dub episodes)
 *  - 3 providers tried in parallel: zoro, animepahe, gogoanime
 *  - AniSkip intro/outro timestamps
 *  - Full main page (trending, popular, top-rated, recent, movies)
 *  - Search via AniList advanced-search
 */
class MiruroProvider : MainAPI() {

    override var mainUrl            = "https://www.miruro.tv"
    override var name               = "Miruro"
    override val hasMainPage        = true
    override var lang               = "en"
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.Anime, TvType.AnimeMovie)

    // ── Backends ─────────────────────────────────────────────────────────────
    // Consumet instance miruro.tv itself uses (found in their .env.example)
    private val CONSUMET  = "https://public-miruro-consumet-api.vercel.app"
    private val ANISKIP   = "https://api.aniskip.com/v2"
    private val ANILIST   = "https://graphql.anilist.co"

    // ── Providers tried in order (sub) ───────────────────────────────────────
    // zoro      = hianime.to  → has separate subtitle files (best quality)
    // animepahe = animepahe.com → hard-subbed, good 720p quality
    // gogoanime = anitaku.pe  → also carries dub, wide catalogue
    private val SUB_PROVIDERS = listOf("zoro", "animepahe", "gogoanime")

    // ── Providers that carry dub episodes ────────────────────────────────────
    // zoro episode list contains isDub=true episodes when dub exists
    // gogoanime has separate "-dub" series IDs mapped by consumet
    private val DUB_PROVIDERS = listOf("zoro", "gogoanime")

    // ─── Helpers ──────────────────────────────────────────────────────────────
    private fun slugify(text: String) = text.lowercase()
        .replace(Regex("[^a-z0-9\\s-]"), "").trim()
        .replace(Regex("\\s+"), "-")

    private fun CMedia.toSearchResult(): SearchResponse? {
        val t = title?.english ?: title?.romaji ?: return null
        val type = if (format == "MOVIE") TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(t, "$mainUrl/watch/$id/${slugify(t)}", type) {
            posterUrl = image
        }
    }

    // ─── MAIN PAGE ────────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "TRENDING_DESC/RELEASING"   to "Trending Now",
        "POPULARITY_DESC/RELEASING" to "Popular Airing",
        "SCORE_DESC/FINISHED"       to "Top Rated",
        "TRENDING_DESC/FINISHED"    to "Recently Finished",
        "POPULARITY_DESC/MOVIE"     to "Top Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (sort, statusOrFormat) = request.data.split("/")
        val isMovie = statusOrFormat == "MOVIE"
        val params  = buildMap {
            put("sort",    "[$sort]")
            put("page",    page.toString())
            put("perPage", "20")
            if (isMovie) put("format", "MOVIE") else put("status", statusOrFormat)
        }
        val res   = app.get("$CONSUMET/meta/anilist/advanced-search", params = params)
        val paged = AppUtils.parseJson<CPage>(res.text)
        val items = paged.results?.mapNotNull { it.toSearchResult() } ?: emptyList()
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = paged.hasNextPage == true
        )
    }

    // ─── SEARCH ───────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get(
            "$CONSUMET/meta/anilist/advanced-search",
            params = mapOf("query" to query, "perPage" to "20")
        )
        return AppUtils.parseJson<CPage>(res.text)
            .results?.mapNotNull { it.toSearchResult() } ?: emptyList()
    }

    // ─── LOAD ─────────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        // URL format: https://www.miruro.tv/watch/{anilistId}/{slug}
        val anilistId = url.split("/")[4]

        // Fetch info + episode list from first provider that has episodes
        var info: CInfo? = null
        for (prov in SUB_PROVIDERS) {
            try {
                val r = app.get(
                    "$CONSUMET/meta/anilist/info/$anilistId",
                    params = mapOf("provider" to prov)
                )
                val parsed = AppUtils.parseJson<CInfo>(r.text)
                if (!parsed.episodes.isNullOrEmpty()) { info = parsed; break }
                if (info == null) info = parsed // keep even if empty (for metadata)
            } catch (_: Exception) {}
        }
        info ?: throw ErrorLoadingException("Could not load anime info for $anilistId")

        val title   = info.title?.english ?: info.title?.romaji ?: "Unknown"
        val slug    = slugify(info.title?.romaji ?: title)
        val plot    = info.description?.replace(Regex("<.*?>"), "")
        val isMovie = info.type == "MOVIE"
        val base    = "$mainUrl/watch/$anilistId/$slug"
        val malId   = info.malId

        // ── Split episodes into sub and dub ───────────────────────────────────
        // Consumet marks dub episodes with isDub=true in the same list (zoro)
        val allEps  = info.episodes ?: emptyList()
        val subEps  = allEps.filter { it.isDub != true }
        val dubEps  = allEps.filter { it.isDub == true }

        fun List<CEpisode>.toCloudStreamEps(dub: Boolean): List<Episode> =
            map { ep ->
                // Encode malId for AniSkip lookup + isDub flag so loadLinks knows
                val epUrl = "$base?ep=${ep.number}&epId=${ep.id}" +
                        "&malId=${malId ?: ""}&dub=${if (dub) "1" else "0"}"
                newEpisode(epUrl) {
                    episode     = ep.number
                    name        = ep.title
                    posterUrl   = ep.image
                    description = ep.description
                }
            }

        // Fallback stubs if no episodes from any provider
        fun fallbackEps(dub: Boolean): List<Episode> {
            val count = when {
                info.status == "RELEASING" && info.nextAiringEpisode?.episode != null ->
                    (info.nextAiringEpisode.episode ?: 1) - 1
                info.totalEpisodes != null -> info.totalEpisodes
                else -> 1
            }
            return (1..count).map { n ->
                newEpisode("$base?ep=$n&malId=${malId ?: ""}&dub=${if (dub) "1" else "0"}") {
                    episode   = n
                    posterUrl = info.image
                }
            }
        }

        val subList = subEps.toCloudStreamEps(false).ifEmpty { fallbackEps(false) }
        val dubList = dubEps.toCloudStreamEps(true)

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                posterUrl           = info.image
                backgroundPosterUrl = info.cover
                this.plot           = plot
                tags                = info.genres
                score               = Score.from100(info.rating)
                year                = info.releaseDate?.toIntOrNull()
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                posterUrl           = info.image
                backgroundPosterUrl = info.cover
                this.plot           = plot
                tags                = info.genres
                score               = Score.from100(info.rating)
                year                = info.releaseDate?.toIntOrNull()
                addEpisodes(DubStatus.Subbed, subList)
                if (dubList.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubList)
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
        val epNum  = data.substringAfter("?ep=").substringBefore("&").toIntOrNull() ?: 1
        val epId   = data.substringAfter("epId=").substringBefore("&").takeIf { it.isNotBlank() }
        val malId  = data.substringAfter("malId=").substringBefore("&").takeIf { it.isNotBlank() }
        val isDub  = data.substringAfter("dub=").substringBefore("&") == "1"
        val anilistId = data.split("/")[4]

        // ── 1. AniSkip timestamps (fire-and-forget, best effort) ─────────────
        // FIX: Use query string directly instead of params map to avoid
        // Map<String, Any> type mismatch caused by passing a List value.
        if (malId != null) {
            try {
                val skipRes = app.get(
                    "$ANISKIP/skip-times/$malId/$epNum?types[]=op&types[]=ed&episodeLength=0"
                )
                val skip = AppUtils.parseJson<AniSkipResult>(skipRes.text)
                skip.results?.forEach { item ->
                    val label = when (item.skipType) {
                        "op" -> "Opening (skip)"
                        "ed" -> "Ending (skip)"
                        else -> item.skipType ?: return@forEach
                    }
                    subtitleCallback(SubtitleFile(
                        lang = "skip:${item.skipType}:${item.interval?.startTime?.toInt()}:" +
                               "${item.interval?.endTime?.toInt()}",
                        url  = label
                    ))
                }
            } catch (_: Exception) {}
        }

        // ── 2. Sources from all providers in parallel ─────────────────────────
        data class PendingStream(val name: String, val stream: CSource)
        data class ProvResult(
            val streams:   List<PendingStream>,
            val subtitles: List<CSubtitle>
        )

        val providers = if (isDub) DUB_PROVIDERS else SUB_PROVIDERS

        val results: List<ProvResult> = coroutineScope {
            providers.map { prov ->
                async {
                    try {
                        // Resolve this provider's episode ID for the requested episode
                        val resolvedId: String = if (epId != null && prov == providers.first()) {
                            epId  // use the id we already have from load()
                        } else {
                            // Re-fetch episode list from this provider
                            val epRes = app.get(
                                "$CONSUMET/meta/anilist/info/$anilistId",
                                params = mapOf("provider" to prov)
                            )
                            val epInfo = AppUtils.parseJson<CInfo>(epRes.text)
                            val epList = epInfo.episodes ?: return@async ProvResult(emptyList(), emptyList())
                            epList.firstOrNull { ep ->
                                ep.number == epNum && (isDub == (ep.isDub == true))
                            }?.id ?: return@async ProvResult(emptyList(), emptyList())
                        }

                        // Fetch streaming sources
                        val srcRes = app.get(
                            "$CONSUMET/meta/anilist/watch/$resolvedId",
                            params = mapOf("provider" to prov)
                        )
                        val src = AppUtils.parseJson<CSources>(srcRes.text)

                        val streams = src.sources
                            ?.filter { !it.url.isNullOrBlank() }
                            ?.map { s ->
                                val label = buildString {
                                    append("[")
                                    append(prov.replaceFirstChar { it.uppercase() })
                                    append("] ")
                                    if (isDub) append("[DUB] ")
                                    append(s.quality ?: "Auto")
                                }
                                PendingStream(label, s)
                            } ?: emptyList()

                        ProvResult(streams, src.subtitles ?: emptyList())
                    } catch (_: Exception) {
                        ProvResult(emptyList(), emptyList())
                    }
                }
            }.awaitAll()
        }

        // ── 3. Emit subtitles first (so player correlates before streams) ─────
        val seenSubs = mutableSetOf<String>()
        results.forEach { r ->
            r.subtitles.forEach { sub ->
                val url  = sub.url  ?: return@forEach
                val lang = sub.lang ?: "Unknown"
                if (lang.lowercase() == "thumbnails") return@forEach
                if (seenSubs.add("$lang|$url")) {
                    subtitleCallback(SubtitleFile(lang = lang, url = url))
                }
            }
        }

        // ── 4. Emit stream links ──────────────────────────────────────────────
        var anyEmitted = false
        results.forEach { r ->
            r.streams.forEach { pending ->
                val url = pending.stream.url ?: return@forEach
                if (pending.stream.isM3U8 == true || url.contains(".m3u8")) {
                    callback(newExtractorLink(
                        source = name,
                        name   = pending.name,
                        url    = url,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        referer = "$mainUrl/"
                        quality = when {
                            pending.stream.quality?.contains("1080") == true -> Qualities.P1080.value
                            pending.stream.quality?.contains("720")  == true -> Qualities.P720.value
                            pending.stream.quality?.contains("480")  == true -> Qualities.P480.value
                            pending.stream.quality?.contains("360")  == true -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }
                    })
                    anyEmitted = true
                } else {
                    // Embed URL (ok.ru, mp4upload, etc.) — hand to extractor chain
                    loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
                    anyEmitted = true
                }
            }
        }
        return anyEmitted
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════

    // ── Paged response (advanced-search, trending, popular, etc.) ────────────
    data class CPage(
        val currentPage: Int?,
        val hasNextPage: Boolean?,
        val results: List<CMedia>?
    )

    // ── Lightweight media object (search / browse results) ───────────────────
    data class CMedia(
        val id:          Any?,   // Int or String depending on endpoint
        val title:       CTitle?,
        val image:       String?,
        val rating:      Int?,
        val format:      String?,
        val type:        String?,
        val totalEpisodes: Int?,
        val status:      String?,
        val releaseDate: String?
    )

    // ── Full info object returned by /meta/anilist/info/{id} ─────────────────
    data class CInfo(
        val id:          Any?,
        val malId:       Int?,
        val title:       CTitle?,
        val image:       String?,
        val cover:       String?,
        val description: String?,
        val rating:      Int?,
        val type:        String?,
        val format:      String?,
        val status:      String?,
        val releaseDate: String?,
        val totalEpisodes: Int?,
        val currentEpisode: Int?,
        val genres:      List<String>?,
        val episodes:    List<CEpisode>?,
        val nextAiringEpisode: CNextAiring?,
        // "dubDubbed" is returned by some provider mappings
        val isDubbed:    Boolean?
    )

    data class CTitle(
        val romaji:        String?,
        val english:       String?,
        val native:        String?,
        val userPreferred: String?
    )

    data class CNextAiring(val episode: Int?, val airingAt: Long?)

    // ── Episode object inside /info or /episodes ──────────────────────────────
    data class CEpisode(
        val id:          String,
        val number:      Int,
        val title:       String?,
        val description: String?,
        val image:       String?,
        val airDate:     String?,
        val isFiller:    Boolean?,
        // zoro sets this to true for dub episodes in the same list
        val isDub:       Boolean?
    )

    // ── Sources response from /meta/anilist/watch/{id} ────────────────────────
    data class CSources(
        val headers:   Map<String, String>?,
        val sources:   List<CSource>?,
        val subtitles: List<CSubtitle>?,
        val intro:     CTimestamp?,
        val outro:     CTimestamp?,
        val download:  String?
    )

    data class CSource(
        val url:    String?,
        val isM3U8: Boolean?,
        val quality: String?
    )

    data class CSubtitle(
        val url:  String?,
        val lang: String?
    )

    data class CTimestamp(val start: Double?, val end: Double?)

    // ── AniSkip response ──────────────────────────────────────────────────────
    // GET https://api.aniskip.com/v2/skip-times/{malId}/{ep}?types[]=op&types[]=ed
    data class AniSkipResult(
        val found:   Boolean?,
        val results: List<AniSkipItem>?
    )

    data class AniSkipItem(
        val interval: AniSkipInterval?,
        val skipType: String?,       // "op" | "ed" | "mixed-op" | "mixed-ed" | "recap"
        val skipId:   String?,
        val episodeLength: Double?
    )

    data class AniSkipInterval(
        val startTime: Double?,
        val endTime:   Double?
    )
}
