package com.nuvio.app.features.anime

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpPostJsonWithHeaders
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.details.MetaVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@Serializable
data class AniListResponse(
    val data: AniListData? = null,
)

@Serializable
data class AniListData(
    val Page: AniListPage? = null,
    val Media: AniListMediaItem? = null,
)

@Serializable
data class AniListPage(
    val media: List<AniListMediaItem> = emptyList(),
)

@Serializable
data class AniListMediaItem(
    val id: Int = 0,
    val idMal: Int? = null,
    val title: AniListTitle? = null,
    val bannerImage: String? = null,
    val coverImage: AniListCoverImage? = null,
    val description: String? = null,
    val episodes: Int? = null,
    val duration: Int? = null,
    val seasonYear: Int? = null,
    val averageScore: Int? = null,
    val genres: List<String> = emptyList(),
    val status: String? = null,
) {
    val displayTitle: String
        get() = title?.english?.takeIf { it.isNotBlank() }
            ?: title?.userPreferred?.takeIf { it.isNotBlank() }
            ?: title?.romaji?.takeIf { it.isNotBlank() }
            ?: title?.native.orEmpty()
}

@Serializable
data class AniListTitle(
    val english: String? = null,
    val romaji: String? = null,
    val userPreferred: String? = null,
    val native: String? = null,
)

@Serializable
data class AniListCoverImage(
    val extraLarge: String? = null,
    val large: String? = null,
    val color: String? = null,
)

@Serializable
data class AniZipResponse(
    val mappings: AniZipMappings? = null,
    val episodes: Map<String, AniZipEpisode>? = null,
)

@Serializable
data class AniZipMappings(
    val anilist_id: Int? = null,
    val mal_id: Int? = null,
    val thetvdb_id: Int? = null,
    val imdb_id: String? = null,
    val themoviedb_id: String? = null,
    val type: String? = null,
)

@Serializable
data class AniZipEpisode(
    val episodeNumber: Int? = null,
    val episode: String? = null,
    val title: Map<String, String>? = null,
    val image: String? = null,
    val overview: String? = null,
    val summary: String? = null,
) {
    val displayTitle: String?
        get() = title?.get("en")?.takeIf { it.isNotBlank() }
            ?: title?.get("x-jat")?.takeIf { it.isNotBlank() }
            ?: title?.get("ja")
}

@Serializable
data class JikanResponse(
    val data: JikanAnimeData? = null,
)

@Serializable
data class JikanAnimeData(
    val mal_id: Int = 0,
    val score: Double? = null,
    val scored_by: Int? = null,
    val rank: Int? = null,
    val popularity: Int? = null,
    val synopsis: String? = null,
    val background: String? = null,
    val status: String? = null,
    val episodes: Int? = null,
)

/**
 * Anime metadata & ID mapping service ported from SmoothVega's animeMetadata.ts.
 * Integrates AniList GraphQL (graphql.anilist.co), AniZip (api.ani.zip), and Jikan/MAL.
 */
object AnimeMetadataService {
    private val log = Logger.withTag("AnimeMetadataService")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private const val ANILIST_URL = "https://graphql.anilist.co"
    private const val ANILIST_TOKEN = "J9qZpsXQM9K5vUOwu4LfpKDgAgr1gPvwi0f9sz2F"
    private const val ANIZIP_URL = "https://api.ani.zip/mappings"
    private const val JIKAN_URL = "https://api.jikan.moe/v4/anime"
    private const val KITSU_URL = "https://kitsu.io/api/edge/anime"
    private const val TVMAZE_URL = "https://api.tvmaze.com"

    private val searchCache = mutableMapOf<String, List<AniListMediaItem>>()
    private val idCache = mutableMapOf<Int, AniListMediaItem>()
    private val aniZipCache = mutableMapOf<Int, AniZipResponse>()

    private const val SEARCH_QUERY = """
        query SearchAnime(${'$'}search: String) {
          Page(page: 1, perPage: 8) {
            media(search: ${'$'}search, type: ANIME, isAdult: false) {
              id
              idMal
              title {
                english
                romaji
                userPreferred
                native
              }
              bannerImage
              coverImage {
                extraLarge
                large
                color
              }
              description(asHtml: false)
              episodes
              duration
              seasonYear
              averageScore
              genres
              status
            }
          }
        }
    """

    private const val ID_QUERY = """
        query AnimeById(${'$'}id: Int) {
          Media(id: ${'$'}id, type: ANIME, isAdult: false) {
            id
            idMal
            title {
              english
              romaji
              userPreferred
              native
            }
            bannerImage
            coverImage {
              extraLarge
              large
              color
            }
            description(asHtml: false)
            episodes
            duration
            seasonYear
            averageScore
            genres
            status
          }
        }
    """

    suspend fun searchAniList(title: String): List<AniListMediaItem> {
        val clean = title.trim()
        if (clean.isBlank()) return emptyList()
        searchCache[clean.lowercase()]?.let { return it }

        return try {
            val payload = buildJsonObject {
                put("query", SEARCH_QUERY)
                putJsonObject("variables") {
                    put("search", clean)
                }
            }.toString()

            val responseText = withTimeoutOrNull(5000L) {
                httpPostJsonWithHeaders(
                    url = ANILIST_URL,
                    body = payload,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                        "Authorization" to "Bearer $ANILIST_TOKEN",
                    )
                )
            } ?: return emptyList()

            val parsed = json.decodeFromString<AniListResponse>(responseText)
            val results = parsed.data?.Page?.media.orEmpty()
            if (results.isNotEmpty()) {
                searchCache[clean.lowercase()] = results
                results.forEach { idCache[it.id] = it }
            }
            results
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            log.w(t) { "AniList search failed for '$title'" }
            emptyList()
        }
    }

    suspend fun getAniListById(id: Int): AniListMediaItem? {
        if (id <= 0) return null
        idCache[id]?.let { return it }

        return try {
            val payload = buildJsonObject {
                put("query", ID_QUERY)
                putJsonObject("variables") {
                    put("id", id)
                }
            }.toString()

            val responseText = withTimeoutOrNull(5000L) {
                httpPostJsonWithHeaders(
                    url = ANILIST_URL,
                    body = payload,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                        "Authorization" to "Bearer $ANILIST_TOKEN",
                    )
                )
            } ?: return null

            val parsed = json.decodeFromString<AniListResponse>(responseText)
            val item = parsed.data?.Media
            if (item != null) {
                idCache[id] = item
            }
            item
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            log.w(t) { "AniList fetch failed for id=$id" }
            null
        }
    }

    suspend fun searchKitsuId(title: String): String? {
        val clean = title.trim()
        if (clean.isBlank()) return null
        return try {
            val encoded = clean.encodeURLParameter()
            val url = "$KITSU_URL?filter[text]=$encoded&page[limit]=1"
            val text = withTimeoutOrNull(4000L) { httpGetText(url) } ?: return null
            val root = json.parseToJsonElement(text).jsonObject
            val data = root["data"]?.jsonArray
            val first = data?.firstOrNull()?.jsonObject
            first?.get("id")?.jsonPrimitive?.content
        } catch (_: Throwable) { null }
    }

    suspend fun getAniZipMappings(anilistId: Int): AniZipResponse? {
        if (anilistId <= 0) return null
        aniZipCache[anilistId]?.let { return it }

        return try {
            val url = "$ANIZIP_URL?anilist_id=$anilistId"
            val text = withTimeoutOrNull(5000L) {
                httpGetText(url)
            } ?: return null

            val parsed = json.decodeFromString<AniZipResponse>(text)
            aniZipCache[anilistId] = parsed
            parsed
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            log.w(t) { "AniZip lookup failed for anilistId=$anilistId" }
            null
        }
    }

    suspend fun getMalDetails(malId: Int): JikanAnimeData? {
        if (malId <= 0) return null
        return try {
            val url = "$JIKAN_URL/$malId"
            val text = withTimeoutOrNull(5000L) {
                httpGetText(url)
            } ?: return null

            val parsed = json.decodeFromString<JikanResponse>(text)
            parsed.data
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            log.w(t) { "Jikan MAL lookup failed for malId=$malId" }
            null
        }
    }

    /**
     * Enriches a MetaDetails item using AniList GraphQL, AniZip mappings, and MAL data.
     * Supplements missing banner images, high-res posters, episode stills, and ratings.
     */
    suspend fun enrichMeta(meta: MetaDetails, lookupId: String): MetaDetails {
        val extractedAniListId = extractAniListId(lookupId)
            ?: extractAniListId(meta.id)
            ?: searchAniList(meta.name).firstOrNull()?.id

        if (extractedAniListId == null || extractedAniListId <= 0) {
            return meta
        }

        val aniListMedia = getAniListById(extractedAniListId)
        val aniZip = getAniZipMappings(extractedAniListId)
        val malId = aniListMedia?.idMal ?: aniZip?.mappings?.mal_id
        val malData = malId?.let { getMalDetails(it) }

        var background = meta.background
        if (background.isNullOrBlank()) {
            background = aniListMedia?.bannerImage
        }

        var poster = meta.poster
        if (poster.isNullOrBlank()) {
            poster = aniListMedia?.coverImage?.extraLarge
                ?: aniListMedia?.coverImage?.large
        }

        var description = meta.description
        if (description.isNullOrBlank()) {
            description = aniListMedia?.description ?: malData?.synopsis
        }

        val ratings = meta.externalRatings.toMutableList()
        aniListMedia?.averageScore?.let { score ->
            ratings.add(
                MetaExternalRating(
                    source = "AniList",
                    value = score / 10.0,
                    isCertified = score >= 80,
                )
            )
        }
        malData?.score?.let { malScore ->
            ratings.add(
                MetaExternalRating(
                    source = "MyAnimeList",
                    value = malScore,
                    isCertified = malScore >= 8.0,
                )
            )
        }

        val genres = if (meta.genres.isEmpty() && aniListMedia != null) {
            aniListMedia.genres
        } else {
            meta.genres
        }

        // Enrich videos with episode stills and canonical titles from AniZip
        val enrichedVideos = if (aniZip?.episodes != null && meta.videos.isNotEmpty()) {
            meta.videos.map { video ->
                val epKey = (video.episode ?: 1).toString()
                val zipEp = aniZip.episodes[epKey]
                    ?: aniZip.episodes.values.find { it.episodeNumber == video.episode }

                if (zipEp != null) {
                    video.copy(
                        title = if (video.title.startsWith("Episode ", ignoreCase = true) && !zipEp.displayTitle.isNullOrBlank()) {
                            "Episode ${video.episode}: ${zipEp.displayTitle}"
                        } else video.title,
                        thumbnail = video.thumbnail ?: zipEp.image,
                        overview = video.overview ?: zipEp.overview ?: zipEp.summary,
                    )
                } else {
                    video
                }
            }
        } else {
            meta.videos
        }

        val resolvedImdbId = meta.imdbId?.takeIf { it.isNotBlank() }
            ?: aniZip?.mappings?.imdb_id?.takeIf { it.isNotBlank() }

        return meta.copy(
            imdbId = resolvedImdbId,
            background = background,
            poster = poster,
            description = description,
            externalRatings = ratings.distinctBy { it.source },
            genres = genres,
            videos = enrichedVideos,
        )
    }

    fun extractAniListId(value: String): Int? {
        val clean = value.trim()
        val regex = Regex("""(?:anilist:|mal:|kitsu:)?(\d+)""")
        if (clean.startsWith("anilist:", ignoreCase = true)) {
            return clean.substringAfter(':').toIntOrNull()
        }
        val match = regex.find(clean)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
}
