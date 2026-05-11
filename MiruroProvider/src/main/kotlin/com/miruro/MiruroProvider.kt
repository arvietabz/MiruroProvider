package com.miruro

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MiruroProvider : MainAPI() {

    override var mainUrl = "https://www.miruro.tv"
    override var name = "Miruro"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val anilistUrl = "https://graphql.anilist.co"

    // ─── SEARCH ──────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val graphqlQuery = """
            query {
              Page(perPage: 20) {
                media(search: "$query", type: ANIME, isAdult: false) {
                  id
                  title { romaji english }
                  coverImage { large }
                  format
                }
              }
            }
        """.trimIndent()

        val response = app.post(
            anilistUrl,
            json = mapOf("query" to graphqlQuery),
            headers = mapOf("Content-Type" to "application/json")
        )

        // CloudStream's parser maps JSON keys case-insensitively
        val results = response.parsed<AnilistSearchResponse>()

        return results.data.page.media.mapNotNull { media ->
            val title = media.title.english ?: media.title.romaji ?: return@mapNotNull null
            val watchUrl = "$mainUrl/watch/${media.id}"

            newAnimeSearchResponse(
                name = title,
                url  = watchUrl,
                type = TvType.Anime
            ) {
                posterUrl = media.coverImage.large
            }
        }
    }

    // ─── LOAD ─────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val anilistId = url.trimEnd('/').split("/").last()

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
              }
            }
        """.trimIndent()

        val response = app.post(
            anilistUrl,
            json = mapOf("query" to graphqlQuery),
            headers = mapOf("Content-Type" to "application/json")
        )

        val media = response.parsed<AnilistLoadResponse>().data.media

        val title = media.title.english ?: media.title.romaji ?: "Unknown"
        val isMovie = media.format == "MOVIE"

        val episodeCount = media.episodes ?: 1
        val episodes = (1..episodeCount).map { epNum ->
            newEpisode("$url?ep=$epNum") {
                episode = epNum
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
                plot                = media.description?.replace(Regex("<.*?>"), "")
                tags                = media.genres
                score               = media.averageScore?.let { Score(it, ScoreType.POINTS_10) }
            }
        } else {
            newAnimeLoadResponse(
                name = title,
                url  = url,
                type = TvType.Anime
            ) {
                posterUrl           = media.coverImage.large
                backgroundPosterUrl = media.bannerImage
                plot                = media.description?.replace(Regex("<.*?>"), "")
                tags                = media.genres
                score               = media.averageScore?.let { Score(it, ScoreType.POINTS_10) }
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
        return false
    }

    // ─── DATA CLASSES ─────────────────────────────────────────────
    // CloudStream's parser is case-insensitive so "Page" in JSON
    // maps fine to "page" in the data class, no @SerializedName needed

    data class AnilistSearchResponse(val data: SearchData)
    data class SearchData(val page: PageData)
    data class PageData(val media: List<MediaItem>)
    data class MediaItem(
        val id: Int,
        val title: TitleData,
        val coverImage: CoverData,
        val format: String?
    )

    data class AnilistLoadResponse(val data: LoadData)
    data class LoadData(val media: MediaDetail)
    data class MediaDetail(
        val id: Int,
        val title: TitleData,
        val description: String?,
        val coverImage: CoverData,
        val bannerImage: String?,
        val episodes: Int?,
        val format: String?,
        val genres: List<String>?,
        val averageScore: Int?
    )

    data class TitleData(val romaji: String?, val english: String?)
    data class CoverData(val large: String?)
}