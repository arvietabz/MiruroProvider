package com.miruro

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import android.util.Base64
import java.util.zip.GZIPInputStream
import java.io.ByteArrayInputStream

class MiruroProvider : MainAPI() {

    override var mainUrl = "https://www.miruro.tv"
    override var name = "Miruro"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val anilistUrl = "https://graphql.anilist.co"
    private val pipeUrl = "$mainUrl/api/secure/pipe"
    private val provider = "kiwi"

    // ─── HELPER: slugify ──────────────────────────────────────────
    private fun slugify(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
    }

    // ─── HELPER: build pipe payload ───────────────────────────────
    // Encodes a JSON payload to base64 for the pipe API
    private fun buildPipeParam(path: String, query: Map<String, Any>): String {
        val payload = """{"path":"$path","method":"GET","query":${
            query.entries.joinToString(",", "{", "}") { (k, v) ->
                if (v is String) "\"$k\":\"$v\""
                else "\"$k\":$v"
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

    // ─── MAIN PAGE ────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "TRENDING_DESC"   to "Trending Now",
        "POPULARITY_DESC" to "Popular This Season",
        "SCORE_DESC"      to "Top Rated",
        "START_DATE_DESC" to "Newest"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val graphqlQuery = """
            query {
              Page(page: $page, perPage: 20) {
                media(sort: ${request.data}, type: ANIME, isAdult: false) {
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

        val mediaList = response.parsed<AnilistSearchResponse>().data.page.media
        val home = mediaList.mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    // ─── HELPER: media to search card ─────────────────────────────
    private fun AnilistMedia.toSearchResult(): SearchResponse? {
        val title = this.title.english ?: this.title.romaji ?: return null
        val slug  = slugify(this.title.romaji ?: title)
        val watchUrl = "$mainUrl/watch/${this.id}/$slug"
        return newAnimeSearchResponse(title, watchUrl, TvType.Anime) {
            posterUrl = this@toSearchResult.coverImage.large
        }
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

        return response.parsed<AnilistSearchResponse>()
            .data.page.media
            .mapNotNull { it.toSearchResult() }
    }

    // ─── LOAD ─────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        // url = https://www.miruro.tv/watch/147105/witch-hat-atelier
        val anilistId = url.split("/")[4].toInt()

        // ── Fetch AniList metadata ──
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
        val media   = anilistResponse.parsed<AnilistLoadResponse>().data.media
        val title   = media.title.english ?: media.title.romaji ?: "Unknown"
        val isMovie = media.format == "MOVIE"
        val plot    = media.description?.replace(Regex("<.*?>"), "")
        val slug    = slugify(media.title.romaji ?: title)

        // ── Fetch real episode data from Miruro pipe API ──
        val e = buildPipeParam(
            path  = "episodes",
            query = mapOf("anilistId" to anilistId, "provider" to provider)
        )
        val pipeResponse = app.get(
            "$pipeUrl?e=$e",
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "Origin"  to mainUrl
            )
        )

        // Parse episodes from kiwi provider, sub track
        val episodeData = try {
            val decoded = decodePipeResponse(pipeResponse.text)
            decoded.parsed<PipeEpisodesResponse>()
                .providers?.kiwi?.episodes?.sub ?: emptyList()
        } catch (ex: Exception) {
            emptyList()
        }

        // Build episode list — use real data if available, fallback to numbered
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
            // Fallback: numbered episodes only
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
        // data = https://www.miruro.tv/watch/147105/witch-hat-atelier?ep=1
        val anilistId = data.split("/")[4].toInt()
        val epNum     = data.substringAfter("?ep=").toIntOrNull() ?: 1

        // Step 1: get episode list to find the episodeId for this ep number
        val e = buildPipeParam(
            path  = "episodes",
            query = mapOf("anilistId" to anilistId, "provider" to provider)
        )
        val pipeResponse = app.get(
            "$pipeUrl?e=$e",
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "Origin"  to mainUrl
            )
        )

        val episodeList = try {
            val decoded = decodePipeResponse(pipeResponse.text)
            decoded.parsed<PipeEpisodesResponse>()
                .providers?.kiwi?.episodes?.sub ?: emptyList()
        } catch (ex: Exception) {
            return false
        }

        val episodeId = episodeList
            .firstOrNull { it.number == epNum }
            ?.id ?: return false

        // Step 2: get video sources using episodeId
        val sourcesE = buildPipeParam(
            path  = "sources",
            query = mapOf(
                "episodeId"  to episodeId,
                "provider"   to provider,
                "category"   to "sub",
                "anilistId"  to anilistId
            )
        )
        val sourcesResponse = app.get(
            "$pipeUrl?e=$sourcesE",
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "Origin"  to mainUrl
            )
        )

        val streams = try {
            val decoded = decodePipeResponse(sourcesResponse.text)
            decoded.parsed<SourcesResponse>().streams ?: emptyList()
        } catch (ex: Exception) {
            return false
        }

        // Step 3: pass HLS streams to CloudStream
        streams.filter { it.type == "hls" && it.isActive == true }.forEach { stream ->
            callback(
                ExtractorLink(
                    source  = name,
                    name    = "${stream.fansub ?: "Unknown"} ${stream.quality ?: ""}".trim(),
                    url     = stream.url,
                    referer = stream.referer ?: "$mainUrl/",
                    quality = when (stream.quality) {
                        "1080p" -> Qualities.P1080.value
                        "720p"  -> Qualities.P720.value
                        "480p"  -> Qualities.P480.value
                        "360p"  -> Qualities.P360.value
                        else    -> Qualities.Unknown.value
                    },
                    isM3u8  = true
                )
            )
        }

        return true
    }

    // ─── DATA CLASSES: AniList ────────────────────────────────────
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

    // ─── DATA CLASSES: Pipe API ───────────────────────────────────
    data class PipeEpisodesResponse(val providers: ProvidersData?)
    data class ProvidersData(val kiwi: ProviderData?)
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

    data class SourcesResponse(val streams: List<StreamItem>?)
    data class StreamItem(
        val url: String,
        val type: String?,
        val quality: String?,
        val audio: String?,
        val fansub: String?,
        val isActive: Boolean?,
        val referer: String?
    )
}
