package com.nuvio.app.features.anime

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpPostJsonWithHeaders
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.details.MetaVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    val format: String? = null,
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
    val titles: Map<String, String?> = emptyMap(),
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
    val seasonNumber: Int? = null,
    val absoluteEpisodeNumber: Int? = null,
    val episode: String? = null,
    val title: Map<String, String?>? = null,
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

@Serializable
data class TenraiEpisodeResponse(
    val data: TenraiEpisodeData? = null,
)

@Serializable
data class TenraiEpisodeData(
    val mal_id: Int = 0,
    val filler: Boolean? = null,
    val recap: Boolean? = null,
)

enum class AnimeEpisodeClassification {
    Filler,
    Recap,
}

internal data class AnimeMalEpisodeLookup(
    val malId: Int,
    val episode: Int,
)

/** Resolve the MAL episode coordinate without confusing a TMDB season-local index with an anime episode. */
internal fun resolveAnimeMalEpisodeLookup(
    malId: Int?,
    episodeMalId: Int?,
    season: Int?,
    episode: Int?,
    absoluteEpisode: Int?,
): AnimeMalEpisodeLookup? {
    val localEpisode = episode?.takeIf { it > 0 } ?: return null
    val verifiedAbsoluteEpisode = absoluteEpisode?.takeIf { it > 0 }
    val resolvedMalId = episodeMalId?.takeIf { it > 0 }
        ?: malId?.takeIf { it > 0 && (season == null || season <= 1 || verifiedAbsoluteEpisode != null) }
        ?: return null
    // AniZip's per-season MAL ID uses season-local numbering. Only use the
    // cross-season absolute number when looking up the overall series MAL ID.
    val resolvedEpisode = if (episodeMalId != null) {
        localEpisode
    } else {
        verifiedAbsoluteEpisode
            ?: localEpisode.takeIf { season == null || season <= 1 }
    }
        ?: return null
    return AnimeMalEpisodeLookup(malId = resolvedMalId, episode = resolvedEpisode)
}

/**
 * Anime metadata & ID mapping service ported from SmoothVega's animeMetadata.ts.
 * Integrates AniList GraphQL (graphql.anilist.co), AniZip (api.ani.zip), and Tenrai/MAL.
 */
object AnimeMetadataService {
    private val log = Logger.withTag("AnimeMetadataService")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private const val ANILIST_URL = "https://graphql.anilist.co"
    private const val ANILIST_TOKEN = "J9qZpsXQM9K5vUOwu4LfpKDgAgr1gPvwi0f9sz2F"
    private const val ANIZIP_URL = "https://api.ani.zip/mappings"
    private const val TENRAI_URL = "https://api.tenrai.org/v1/anime"
    private const val KITSU_URL = "https://kitsu.io/api/edge/anime"
    private const val TVMAZE_URL = "https://api.tvmaze.com"

    private val searchCache = mutableMapOf<String, List<AniListMediaItem>>()
    private val idCache = mutableMapOf<Int, AniListMediaItem>()
    private val aniZipCache = mutableMapOf<Int, AniZipResponse>()
    private val episodeClassificationCache = mutableMapOf<String, AnimeEpisodeClassification?>()

    private const val SEARCH_QUERY = """
        query SearchAnime(${'$'}search: String) {
          Page(page: 1, perPage: 8) {
            media(search: ${'$'}search, type: ANIME, isAdult: false) {
              id
              idMal
              format
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
            format
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
            val url = "$TENRAI_URL/$malId"
            val text = withTimeoutOrNull(5000L) {
                httpGetText(url)
            } ?: return null

            val parsed = json.decodeFromString<JikanResponse>(text)
            parsed.data
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            log.w(t) { "Tenrai MAL lookup failed for malId=$malId" }
            null
        }
    }

    /** Returns a community classification for one exact MAL episode, or null when unknown/canon. */
    suspend fun getEpisodeClassification(malId: Int, episode: Int): AnimeEpisodeClassification? {
        if (malId <= 0 || episode <= 0) return null
        val cacheKey = "$malId:$episode"
        if (episodeClassificationCache.containsKey(cacheKey)) return episodeClassificationCache[cacheKey]

        return try {
            val response = withTimeoutOrNull(3_500L) {
                httpGetText("$TENRAI_URL/$malId/episodes/$episode")
            } ?: return null
            val payload = json.decodeFromString<TenraiEpisodeResponse>(response).data ?: return null
            if (payload.mal_id != episode) return null
            classifyAnimeEpisode(payload.filler, payload.recap).also {
                episodeClassificationCache[cacheKey] = it
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log.w(error) { "Tenrai episode classification failed for $cacheKey" }
            null
        }
    }

    internal fun classifyAnimeEpisode(filler: Boolean?, recap: Boolean?): AnimeEpisodeClassification? =
        when {
            filler == true -> AnimeEpisodeClassification.Filler
            recap == true -> AnimeEpisodeClassification.Recap
            else -> null
        }

    /**
     * Enriches a MetaDetails item using AniList GraphQL, AniZip mappings, and MAL data.
     * Supplements missing banner images, high-res posters, episode stills, and ratings.
     */
    suspend fun enrichMeta(meta: MetaDetails, lookupId: String): MetaDetails {
        val extractedAniListId = getMappingsForLookupId(lookupId)?.mappings?.anilist_id
            ?: getMappingsForLookupId(meta.id)?.mappings?.anilist_id
            ?: searchAniList(meta.name).singleOrNull { candidate ->
                val titles = listOf(candidate.title?.english, candidate.title?.romaji, candidate.title?.native, candidate.title?.userPreferred)
                val normalized = meta.name.lowercase().filter(Char::isLetterOrDigit)
                titles.any { !it.isNullOrBlank() && it.lowercase().filter(Char::isLetterOrDigit) == normalized }
            }?.id

        if (extractedAniListId == null || extractedAniListId <= 0) {
            return meta
        }

        val aniListMedia = getAniListById(extractedAniListId)
        val animeCandidates = (listOfNotNull(aniListMedia) + searchAniList(meta.name))
            .distinctBy { it.id }
            .filter { candidate ->
                val format = candidate.format?.uppercase()
                val title = listOfNotNull(candidate.title?.english, candidate.title?.romaji, candidate.title?.native)
                    .joinToString(" ").lowercase().filter(Char::isLetterOrDigit)
                val baseTitle = meta.name.lowercase().filter(Char::isLetterOrDigit)
                format in setOf("TV", "TV_SHORT", "ONA", "OVA") &&
                    title.isNotBlank() && baseTitle.isNotBlank() &&
                    (title.contains(baseTitle) || baseTitle.contains(title))
            }
            .take(4)
        val animeMappings = coroutineScope {
            animeCandidates.map { candidate ->
                async { candidate.id to getAniZipMappings(candidate.id) }
            }.awaitAll().mapNotNull { (id, mappings) -> mappings?.let { id to mappings } }.toMap()
        }
        val aniZip = animeMappings[extractedAniListId] ?: getAniZipMappings(extractedAniListId)
        val mappedSeasonEpisodes = animeMappings.values
            .flatMap { mappings ->
                mappings.episodes.orEmpty().values.map { episode ->
                    episode to mappings.mappings?.mal_id
                }
            }
            .filter { (episode, _) ->
                (episode.seasonNumber ?: 0) > 0 && (episode.episodeNumber ?: 0) > 0
            }
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
        val enrichedVideos = if ((mappedSeasonEpisodes.isNotEmpty() || aniZip?.episodes != null) && meta.videos.isNotEmpty()) {
            meta.videos.map { video ->
                val seasonMappedMatches = if (video.season != null && video.episode != null) {
                    mappedSeasonEpisodes.filter { (episode, _) ->
                        episode.seasonNumber == video.season && episode.episodeNumber == video.episode
                    }
                } else {
                    emptyList()
                }
                val consistentSeasonMapping = seasonMappedMatches
                    .distinctBy { it.first.absoluteEpisodeNumber to it.second }
                    .singleOrNull()
                val resolvedMapping = consistentSeasonMapping
                    ?: if (video.season == null && video.episode != null) {
                        mappedSeasonEpisodes.singleOrNull { (episode, _) ->
                            episode.absoluteEpisodeNumber == video.episode && (episode.absoluteEpisodeNumber ?: 0) > 0
                        } ?: aniZip?.episodes?.get(video.episode.toString())?.let { it to aniZip?.mappings?.mal_id }
                    } else {
                        aniZip?.episodes?.values?.singleOrNull {
                            it.seasonNumber == video.season && it.episodeNumber == video.episode
                        }?.let { it to aniZip?.mappings?.mal_id }
                    }
                val (zipEp, episodeMalId) = resolvedMapping ?: (null to null)

                if (zipEp != null) {
                    video.copy(
                        title = if (video.title.startsWith("Episode ", ignoreCase = true) && !zipEp.displayTitle.isNullOrBlank()) {
                            "Episode ${video.episode}: ${zipEp.displayTitle}"
                        } else video.title,
                        absoluteEpisodeNumber = zipEp.absoluteEpisodeNumber ?: video.absoluteEpisodeNumber,
                        animeMalId = episodeMalId ?: video.animeMalId,
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
            malId = malId ?: meta.malId,
            imdbId = resolvedImdbId,
            background = background,
            poster = poster,
            description = description,
            externalRatings = ratings.distinctBy { it.source },
            genres = genres,
            videos = enrichedVideos,
        )
    }

    /** Only explicit AniList IDs belong to the AniList namespace. */
    fun extractAniListId(value: String): Int? =
        Regex("^anilist:(\\d+)(?:[:/].*)?$", RegexOption.IGNORE_CASE)
            .matchEntire(value.trim())?.groupValues?.get(1)?.toIntOrNull()

    private val lookupMappingCache = mutableMapOf<String, AniZipResponse>()

    suspend fun getMappingsForLookupId(lookupId: String): AniZipResponse? {
        val match = Regex("^(anilist|mal|kitsu):(\\d+)(?:[:/].*)?$", RegexOption.IGNORE_CASE)
            .matchEntire(lookupId.trim()) ?: return null
        val namespace = match.groupValues[1].lowercase()
        val id = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        if (namespace == "anilist") return getAniZipMappings(id)
        val key = "$namespace:$id"
        lookupMappingCache[key]?.let { return it }
        return try {
            val body = withTimeoutOrNull(5000L) { httpGetText("$ANIZIP_URL?" + namespace + "_id=$id") } ?: return null
            val mapped = json.decodeFromString<AniZipResponse>(body)
            if (mapped.mappings?.anilist_id == null) return null
            lookupMappingCache[key] = mapped
            mapped
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log.w(error) { "Anime ID mapping failed for $key" }
            null
        }
    }
}
