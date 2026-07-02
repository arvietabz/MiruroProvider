package com.allmanga

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class AllmangaProvider : MainAPI() {

    override var mainUrl = "https://allmanga.to"
    override var name = "Allmanga"
    override val hasMainPage = false
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val anilistUrl = "https://graphql.anilist.co"
    private val allmangaGraphql = "https://api.allmanga.to/api"

    // ─── 1. SEARCH: Query AniList Directly ───────────────────────
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
            headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json")
        ).text

        val json = AppUtils.parseJson<AnilistSearchResponse>(response)
        
        return json.data?.Page?.media?.mapNotNull { media ->
            val titleStr = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
            val isMovie = media.format == "MOVIE"
            val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime
            
            newAnimeSearchResponse(titleStr, "$anilistUrl/watch/${media.id}", tvType) {
                posterUrl = media.coverImage?.large
            }
        } ?: emptyList()
    }

    // ─── 2. LOAD: Fetch Metadata & Map to Allmanga ───────────────
    override suspend fun load(url: String): LoadResponse {
        val anilistId = url.substringAfter("/watch/").toIntOrNull() 
            ?: throw Exception("Invalid AniList ID parsing")

        // Fetch AniList metadata
        val metadataQuery = """
            query {
              Media(id: $anilistId, type: ANIME) {
                title { english romaji }
                description
                coverImage { extraLarge }
                bannerImage
                format
              }
            }
        """.trimIndent()
        
        val anilistRes = app.post(anilistUrl, json = mapOf("query" to metadataQuery)).text
        val anilistMeta = AppUtils.parseJson<AnilistMetaResponse>(anilistRes).data?.Media
        
        val title = anilistMeta?.title?.english ?: anilistMeta?.title?.romaji ?: "Unknown Title"
        val queryTitle = anilistMeta?.title?.romaji ?: title

        // Map to Allmanga internal ID
        val allmangaSearchQuery = """
            query(${'$'}search: SearchInput) {
              shows(search: ${'$'}search, limit: 1) {
                edges { _id name }
              }
            }
        """.trimIndent()

        val searchVariables = mapOf("search" to mapOf("query" to queryTitle, "allowAdult" to false))
        val allmangaSearchRes = app.post(
            allmangaGraphql,
            json = mapOf("query" to allmangaSearchQuery, "variables" to searchVariables)
        ).text

        val shows = AppUtils.parseJson<AllmangaSearchData>(allmangaSearchRes).data?.shows?.edges
        val allmangaId = shows?.firstOrNull()?._id ?: throw Error("Anime not found on Allmanga")

        // Fetch Episode List
        val episodeQuery = """
            query(${'$'}showId: String!) {
              show(id: ${'$'}showId) {
                _id
                availableEpisodesDetail
              }
            }
        """.trimIndent()

        val epRes = app.post(
            allmangaGraphql,
            json = mapOf("query" to episodeQuery, "variables" to mapOf("showId" to allmangaId))
        ).text

        val showDetails = AppUtils.parseJson<AllmangaShowData>(epRes).data?.show
        
        val subList = mutableListOf<Episode>()
        val dubList = mutableListOf<Episode>()
        val subEpisodes = showDetails?.availableEpisodesDetail?.sub ?: emptyList()
        val dubEpisodes = showDetails?.availableEpisodesDetail?.dub ?: emptyList()

        subEpisodes.forEach { epNum ->
            subList.add(newEpisode("$allmangaId/sub/$epNum") {
                this.name = "Episode $epNum"
                this.episode = epNum.toIntOrNull()
            })
        }
        
        dubEpisodes.forEach { epNum ->
            dubList.add(newEpisode("$allmangaId/dub/$epNum") {
                this.name = "Episode $epNum (Dub)"
                this.episode = epNum.toIntOrNull()
            })
        }

        // Fix: Explicitly map lists into a DubStatus Map structure matching Cloudstream expectations
        val episodeMap = mutableMapOf<DubStatus, List<Episode>>()
        if (subList.isNotEmpty()) episodeMap[DubStatus.Subbed] = subList
        if (dubList.isNotEmpty()) episodeMap[DubStatus.Dubbed] = dubList

        return if (anilistMeta?.format == "MOVIE") {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, "$allmangaId/sub/1") {
                posterUrl = anilistMeta.coverImage?.extraLarge
                backgroundPosterUrl = anilistMeta.bannerImage
                plot = anilistMeta.description?.replace(Regex("<.*?>"), "")
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                posterUrl = anilistMeta?.coverImage?.extraLarge
                backgroundPosterUrl = anilistMeta?.bannerImage
                plot = anilistMeta?.description?.replace(Regex("<.*?>"), "")
                this.episodes = episodeMap
            }
        }
    }

    // ─── 3. LINKS: Decrypt URLs & Delegate to Cloudstream Extractors 
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("/")
        if (parts.size < 3) return false
        
        val showId = parts[0]
        val type = parts[1] 
        val epNum = parts[2]

        val sourceQuery = """
            query(${'$'}showId: String!, ${'$'}translationType: VaildTranslationTypeEnumType!, ${'$'}episodeString: String!) {
              episode(showId: ${'$'}showId, translationType: ${'$'}translationType, episodeString: ${'$'}episodeString) {
                sourceUrls
              }
            }
        """.trimIndent()

        val response = app.post(
            allmangaGraphql,
            json = mapOf(
                "query" to sourceQuery, 
                "variables" to mapOf("showId" to showId, "translationType" to type, "episodeString" to epNum)
            )
        ).text

        val sourceUrls = AppUtils.parseJson<AllmangaEpisodeData>(response).data?.episode?.sourceUrls ?: return false
        val requiredReferer = "https://allanimenews.com/"

        sourceUrls.forEach { source ->
            val rawUrl = source.sourceUrl
            val decodedUrl = decodeAllmangaUrl(rawUrl)
            val finalUrl = if (decodedUrl.startsWith("//")) "https:$decodedUrl" else decodedUrl

            // Delegate known hosts to Cloudstream's native internal extractors
            loadExtractor(
                url = finalUrl,
                referer = mainUrl,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
            
            // Fallback for direct streams or custom servers (like Uni)
            if (finalUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(
                    name = "$name - ${source.sourceName ?: "HLS"}",
                    url = finalUrl,
                    referer = mainUrl
                ).forEach(callback)
            } else {
                // Fix: Adjusted property from 'url' to 'streamUrl' to match your explicit dependency definitions
                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name - ${source.sourceName ?: "Direct MP4"}",
                        streamUrl = finalUrl,
                        referer = requiredReferer,
                        quality = Qualities.P1080.value,
                        isM3u8 = false
                    )
                )
            }
        }

        return true
    }

    // ─── HELPER: Decode Allmanga Embed Links ──────────────────────
    private fun decodeAllmangaUrl(url: String): String {
        if (url.startsWith("--")) {
            val hexString = url.removePrefix("--")
            return try {
                String(hexString.chunked(2).map { 
                    it.toInt(16).toByte() 
                }.toByteArray())
            } catch (e: Exception) {
                url
            }
        }
        return url
    }

    // ─── DATA CLASSES FOR JSON PARSING ───────────────────────────
    
    // AniList Core Models
    data class AnilistSearchResponse(val data: PageData?)
    data class PageData(@JsonProperty("Page") val Page: MediaPage?)
    data class MediaPage(val media: List<MediaItem>?)
    data class MediaItem(val id: Int, val title: TitleBlock?, val coverImage: ImageBlock?, val format: String?)
    
    data class AnilistMetaResponse(val data: MetaData?)
    data class MetaData(@JsonProperty("Media") val Media: FullMediaItem?)
    data class FullMediaItem(val title: TitleBlock?, val description: String?, val coverImage: ImageBlock?, val bannerImage: String?, val format: String?)
    data class TitleBlock(val english: String?, val romaji: String?)
    data class ImageBlock(val large: String?, val extraLarge: String?)

    // Allmanga Native GraphQL Models
    data class AllmangaSearchData(val data: ShowSearchData?)
    data class ShowSearchData(val shows: ShowsEdges?)
    data class ShowsEdges(val edges: List<ShowEdge>?)
    data class ShowEdge(val _id: String, val name: String)

    data class AllmangaShowData(val data: ShowDataBlock?)
    data class ShowDataBlock(val show: ShowDetail?)
    data class ShowDetail(val _id: String, val availableEpisodesDetail: AvailableEpisodes?)
    data class AvailableEpisodes(val sub: List<String>?, val dub: List<String>?)

    data class AllmangaEpisodeData(val data: EpisodeDataBlock?)
    data class EpisodeDataBlock(val episode: EpisodeDetail?)
    data class EpisodeDetail(val sourceUrls: List<SourceUrl>?)
    data class SourceUrl(val sourceUrl: String, val sourceName: String?)
}