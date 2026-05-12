package com.miruro

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class MiruroProvider : MainAPI() {

    override var mainUrl = "https://www.miruro.tv"
    override var name = "Miruro"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val anilistUrl = "https://graphql.anilist.co"

    // ─── HELPER ───────────────────────────────────────────────────
    private fun slugify(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
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

        val mediaList = response.parsed<AnilistSearchResponse>()
            .data.page.media

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

    // ─── HELPER: convert a media item to a search card ────────────
    private fun AnilistMedia.toSearchResult(): SearchResponse? {
        val title = this.title.english ?: this.title.romaji ?: return null
        val slug = slugify(this.title.romaji ?: title)
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
        // split("/") = ["https:", "", "www.miruro.tv", "watch", "147105", "witch-hat-atelier"]
        // index 4 = anilist ID
        val anilistId = url.split("/")[4]

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
                studios { nodes { name isMain } }
              }
            }
        """.trimIndent()

        val response = app.post(
            anilistUrl,
            json    = mapOf("query" to graphqlQuery),
            headers = mapOf("Content-Type" to "application/json")
        )

        val media   = response.parsed<AnilistLoadResponse>().data.media
        val title   = media.title.english ?: media.title.romaji ?: "Unknown"
        val isMovie = media.format == "MOVIE"
        val plot    = media.description?.replace(Regex("<.*?>"), "")
        val slug    = slugify(media.title.romaji ?: title)

        val episodeCount = media.episodes ?: 1
        val episodes = (1..episodeCount).map { epNum ->
            newEpisode("$mainUrl/watch/${media.id}/$slug?ep=$epNum") {
                this.episode   = epNum
                this.posterUrl = media.coverImage.large
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
        // TODO: video sources — next step
        return false
    }

    // ─── DATA CLASSES ─────────────────────────────────────────────
    data class AnilistSearchResponse(val data: SearchData)
    data class SearchData(@JsonProperty("Page") val page: PageData)
    data class LoadData(@JsonProperty("Media") val media: MediaDetail)
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
        val studios: StudiosData?
    )

    data class StudiosData(val nodes: List<StudioNode>?)
    data class StudioNode(val name: String?, val isMain: Boolean?)
    data class TitleData(val romaji: String?, val english: String?)
    data class CoverData(val large: String?)
}
