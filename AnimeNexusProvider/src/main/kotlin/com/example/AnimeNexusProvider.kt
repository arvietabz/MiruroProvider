package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URLEncoder

class AnimeNexusProvider : MainAPI() {
    override var mainUrl = "https://anime.nexus"
    private val apiUrl = "https://api.anime.nexus"
    private val assetsUrl = "https://assets.anime.nexus"
    override var name = "AnimeNexus"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    private val mapper = jacksonObjectMapper()

    // ── Data classes ──────────────────────────────────────────────────────────

    data class Poster(
        val resized: Map<String, String>? = null
    )

    data class Genre(
        val name: String? = null,
        val code: String? = null
    )

    data class VideoMeta(
        @JsonProperty("subtitle_languages") val subtitleLanguages: List<String>? = null,
        @JsonProperty("audio_languages") val audioLanguages: List<String>? = null,
        val qualities: Map<String, Int>? = null,
        val status: String? = null
    )

    data class Episode(
        val id: String? = null,
        val slug: String? = null,
        val title: String? = null,
        val number: Int? = null,
        val duration: Int? = null,
        @JsonProperty("video_meta") val videoMeta: VideoMeta? = null,
        @JsonProperty("is_filler") val isFiller: Int? = null,
        @JsonProperty("is_recap") val isRecap: Int? = null
    )

    data class Show(
        val id: String? = null,
        val slug: String? = null,
        val name: String? = null,
        @JsonProperty("name_alt") val nameAlt: String? = null,
        val description: String? = null,
        val poster: Poster? = null,
        val genres: List<Genre>? = null,
        val type: String? = null,
        val status: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        val episode: Episode? = null  // embedded in latest/popular responses
    )

    data class ListResponse(val data: List<Show>? = null)

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun posterUrl(poster: Poster?): String? {
        val path = poster?.resized?.get("640x960") ?: poster?.resized?.values?.firstOrNull()
            ?: return null
        return "$assetsUrl$path"
    }

    private fun showToSearchResponse(show: Show): AnimeSearchResponse {
        return newAnimeSearchResponse(
            name = show.name ?: "Unknown",
            url = "$mainUrl/shows/${show.slug}",
            type = when (show.type) {
                "Movie" -> TvType.AnimeMovie
                "OVA", "ONA", "Special", "TV Special" -> TvType.OVA
                else -> TvType.Anime
            }
        ) {
            this.posterUrl = posterUrl(show.poster)
        }
    }

    // ── Main Page ──────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$apiUrl/api/anime/featured" to "Featured",
        "$apiUrl/api/anime/popular?period=day&limit=15" to "Popular Today",
        "$apiUrl/api/anime/latest?perPage=15&page=1" to "Latest Episodes",
        "$apiUrl/api/anime/seasonal" to "Currently Airing",
        "$apiUrl/api/anime/recent?page=1" to "Recently Added",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.contains("/latest") -> {
                val base = request.data.substringBefore("?")
                "$base?perPage=15&page=$page"
            }
            request.data.contains("/recent") -> {
                val base = request.data.substringBefore("?")
                "$base?page=$page"
            }
            request.data.contains("/popular") -> {
                request.data
            }
            else -> request.data
        }

        val response = app.get(url).text
        val parsed = mapper.readValue<ListResponse>(response)
        val list = parsed.data?.map { showToSearchResponse(it) } ?: emptyList()
        val hasNextPage = list.isNotEmpty() &&
            (request.data.contains("/latest") || request.data.contains("/recent"))
        return newHomePageResponse(request.name, list, hasNextPage)
    }

    // ── Search ─────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        // FIX: replaced non-existent encodeUri() with URLEncoder.encode()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val response = app.get("$apiUrl/api/anime/search?q=$encoded").text
        val parsed = mapper.readValue<ListResponse>(response)
        return parsed.data?.map { showToSearchResponse(it) } ?: emptyList()
    }

    // ── Load (show detail page) ────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast("/")

        val showResponse = app.get("$apiUrl/api/anime/$slug").text
        val show = mapper.readValue<Show>(showResponse)

        val episodesResponse = app.get("$apiUrl/api/anime/$slug/episodes").text
        val episodes = listOf<com.lagradost.cloudstream3.Episode>() // placeholder

        return newAnimeLoadResponse(
            name = show.name ?: return null,
            url = url,
            type = when (show.type) {
                "Movie" -> TvType.AnimeMovie
                "OVA", "ONA", "Special", "TV Special" -> TvType.OVA
                else -> TvType.Anime
            }
        ) {
            this.plot = show.description
            this.posterUrl = posterUrl(show.poster)
            this.tags = show.genres?.mapNotNull { it.name }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ── Load Links (video sources) ─────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false // placeholder until endpoint is confirmed
    }
}
