package com.animelibrary

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.delay

/**
 * AnimeLibraryProvider — metadata-only anime browser.
 *
 * Sources everything from AniList's public GraphQL API: trending / popular /
 * upcoming / recent / schedule lists, search, full anime details (synopsis,
 * cast, studios, relations, recommendations, trailer), and episode
 * titles/thumbnails (from AniList's own `streamingEpisodes` field, which
 * lists officially licensed platforms the show streams on).
 *
 * This provider intentionally does NOT fetch, resolve, or return any video
 * stream/M3U8/embed links. `loadLinks` is a no-op by design — this is a
 * library/tracker, not a player.
 */
class AnimeLibraryProvider : MainAPI() {

    override var mainUrl = "https://anilist.co"
    override var name = "Anime Library"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val anilistUrl = "https://graphql.anilist.co"

    // ══════════════════════════════════════════════════════════════
    //  ANILIST — shared GraphQL fragments
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
        streamingEpisodes { title thumbnail url site }
    """.trimIndent()

    /**
     * Real GraphQL variables, no string interpolation of user input.
     *
     * AniList responds to rate-limited requests with either HTTP 429, or (annoyingly) HTTP 200
     * with a GraphQL body of `{"data": null, "errors": [{"status": 429, ...}]}`. We retry with
     * backoff here so every caller (getMainPage, search, load, etc.) benefits automatically.
     */
    private suspend fun anilistQuery(query: String, variables: Map<String, Any?> = emptyMap()): String {
        val maxAttempts = 4
        var attempt = 0
        while (true) {
            val response = app.post(
                anilistUrl,
                json = mapOf("query" to query, "variables" to variables),
                headers = mapOf("Content-Type" to "application/json")
            )
            val text = response.text
            val isRateLimited = response.code == 429 || text.contains("\"status\":429") || text.contains("Too Many Requests")

            if (!isRateLimited) return text

            attempt++
            if (attempt >= maxAttempts) {
                throw ErrorLoadingException("AniList is rate-limiting requests right now — please wait a bit and try again.")
            }
            delay(1000L * (1L shl (attempt - 1))) // 1s, 2s, 4s backoff
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

    private fun slugify(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
    }

    /** Internal detail-page URL, e.g. anilibrary://20/naruto — not a Miruro or streaming URL. */
    private fun AnilistMedia.toSearchResult(): SearchResponse? {
        val titleStr = title.english ?: title.romaji ?: return null
        val slug     = slugify(titleStr)
        val detailUrl = "$mainUrl/anime/$id/$slug"
        val tvType   = if (format == "MOVIE") TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(titleStr, detailUrl, tvType) {
            posterUrl = coverImage.large
        }
    }

    private fun AnilistRelatedMedia.toSearchResult(): SearchResponse? {
        val titleStr = title.english ?: title.romaji ?: return null
        val slug     = slugify(titleStr)
        val detailUrl = "$mainUrl/anime/$id/$slug"
        val tvType   = if (format == "MOVIE") TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(titleStr, detailUrl, tvType) {
            posterUrl = coverImage?.large
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  MAIN PAGE — trending / popular / upcoming / recent / schedule
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

            "trending" -> fetchCollection("TRENDING_DESC", page = page).mapNotNull { it.toSearchResult() }
            "popular"  -> fetchCollection("POPULARITY_DESC", page = page).mapNotNull { it.toSearchResult() }
            "upcoming" -> fetchCollection("POPULARITY_DESC", "NOT_YET_RELEASED", page).mapNotNull { it.toSearchResult() }
            "recent"   -> fetchCollection("START_DATE_DESC", "RELEASING", page).mapNotNull { it.toSearchResult() }

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

            "finished" -> fetchCollection("TRENDING_DESC", "FINISHED", page).mapNotNull { it.toSearchResult() }

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
    //  SEARCH
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
    //  LOAD — full anime details + episode titles (no video links)
    // ══════════════════════════════════════════════════════════════

    private fun trailerUrl(trailer: AnilistTrailer?): String? {
        val id = trailer?.id ?: return null
        return when (trailer.site?.lowercase()) {
            "youtube"     -> "https://www.youtube.com/watch?v=$id"
            "dailymotion" -> "https://www.dailymotion.com/video/$id"
            else          -> null
        }
    }

    /** Pulls a leading episode number out of AniList's streamingEpisodes titles,
     *  which are formatted like "Episode 12 - The Title Here". */
    private fun parseEpisodeNumber(rawTitle: String?): Int? {
        val match = rawTitle?.let { Regex("""Episode\s+(\d+)""", RegexOption.IGNORE_CASE).find(it) }
        return match?.groupValues?.get(1)?.toIntOrNull()
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

        val tagList = (media.genres.orEmpty() + (media.tags.orEmpty()
            .filter { it.isMediaSpoiler != true && (it.rank ?: 0) >= 60 }
            .mapNotNull { it.name }))
            .distinct()

        // ── Episode titles/thumbnails from AniList's own streamingEpisodes field
        //    (official platforms only) — `data` is set to a detail marker, never a
        //    video URL. loadLinks() below never returns a playable source. ──
        val streamingEps = media.streamingEpisodes.orEmpty()
        val episodes = if (streamingEps.isNotEmpty()) {
            streamingEps.mapIndexed { index, ep ->
                val epNum = parseEpisodeNumber(ep.title) ?: (index + 1)
                newEpisode("$mainUrl/anime/$anilistId/$slug/episode-$epNum") {
                    this.episode   = epNum
                    this.name      = ep.title
                    this.posterUrl = ep.thumbnail
                }
            }.sortedBy { it.episode }
        } else {
            val episodeCount = when {
                media.status == "RELEASING" && media.nextAiringEpisode?.episode != null ->
                    (media.nextAiringEpisode.episode ?: 1) - 1
                media.episodes != null -> media.episodes
                else -> 1
            }
            (1..episodeCount).map { epNum ->
                newEpisode("$mainUrl/anime/$anilistId/$slug/episode-$epNum") {
                    this.episode   = epNum
                    this.posterUrl = media.coverImage.large
                }
            }
        }

        val actorList = media.characters?.edges?.mapNotNull { edge ->
            val charName = edge.node?.name?.full ?: return@mapNotNull null
            ActorData(
                actor = Actor(charName, edge.node.image?.large),
                roleString = edge.voiceActors?.firstOrNull()?.name?.full
            )
        }

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
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  LOAD LINKS — intentionally a no-op.
    //  This provider is a metadata/tracker library only. It never
    //  fetches, resolves, or serves any video stream, embed, or
    //  M3U8 link for any provider.
    // ══════════════════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
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

    /** Officially licensed streaming platform listing — sourced directly from AniList,
     *  not scraped. Used only for episode titles/thumbnails, never for a video URL. */
    data class AnilistStreamingEpisode(val title: String?, val thumbnail: String?, val url: String?, val site: String?)

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
        val recommendations: AnilistRecommendations? = null,
        val streamingEpisodes: List<AnilistStreamingEpisode>? = null
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
}
