package com.nuvio.app.features.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val PLUGIN_REPOSITORY_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1_000L
internal const val PLUGIN_DEFAULT_EXECUTION_TIMEOUT_MS = 15_000L
internal const val PLUGIN_EXTENDED_EXECUTION_TIMEOUT_MS = 45_000L
internal const val PLUGIN_VERIFIED_ANIME_MAPPING_REQUIRED =
    "Skipped to avoid the wrong episode: this provider needs a verified absolute episode mapping."

/** Keep the caller's timeout aligned with the runtime for providers that enumerate multiple servers. */
internal fun pluginExecutionTimeoutMs(scraperId: String): Long {
    val providerId = scraperId.substringAfterLast(':').lowercase()
    val extendedLookupProviders = listOf("aniflix", "meguanimes", "anikage", "animesalt")
    return if (extendedLookupProviders.any { providerId == it || providerId.startsWith("$it-") }) {
        PLUGIN_EXTENDED_EXECUTION_TIMEOUT_MS
    } else {
        PLUGIN_DEFAULT_EXECUTION_TIMEOUT_MS
    }
}

internal fun isPluginRepositoryRefreshDue(
    lastUpdatedEpochMs: Long,
    nowEpochMs: Long,
): Boolean = lastUpdatedEpochMs <= 0L ||
    nowEpochMs - lastUpdatedEpochMs >= PLUGIN_REPOSITORY_REFRESH_INTERVAL_MS

@Serializable
data class PluginManifest(
    val name: String,
    val version: String,
    val description: String? = null,
    val author: String? = null,
    val scrapers: List<PluginManifestScraper> = emptyList(),
)

@Serializable
data class PluginManifestScraper(
    val id: String,
    val name: String,
    val description: String? = null,
    val version: String,
    val filename: String,
    @SerialName("supportedTypes") val supportedTypes: List<String> = listOf("movie", "tv"),
    val enabled: Boolean = true,
    val hasSettings: Boolean = false,
    val logo: String? = null,
    @SerialName("contentLanguage") val contentLanguage: List<String>? = null,
    @SerialName("supportedPlatforms") val supportedPlatforms: List<String>? = null,
    @SerialName("disabledPlatforms") val disabledPlatforms: List<String>? = null,
    val formats: List<String>? = null,
    @SerialName("supportedFormats") val supportedFormats: List<String>? = null,
    @SerialName("supportsExternalPlayer") val supportsExternalPlayer: Boolean? = null,
    val limited: Boolean? = null,
    /** "season-local" (default) or "absolute" for flat episode catalogs. */
    val animeEpisodeNumbering: String? = null,
)

data class PluginRepositoryItem(
    val manifestUrl: String,
    val name: String,
    val description: String? = null,
    val version: String? = null,
    val scraperCount: Int = 0,
    val lastUpdated: Long = 0L,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
)

data class PluginScraper(
    val id: String,
    val repositoryUrl: String,
    val name: String,
    val description: String,
    val version: String,
    val filename: String,
    val supportedTypes: List<String>,
    val enabled: Boolean,
    val manifestEnabled: Boolean,
    val hasSettings: Boolean = false,
    val logo: String? = null,
    val contentLanguage: List<String> = emptyList(),
    val formats: List<String>? = null,
    val animeEpisodeNumbering: String? = null,
    val code: String,
) {
    fun supportsType(type: String): Boolean {
        val normalizedType = normalizePluginType(type)
        if (normalizedType == "anime") {
            // Movie/TV adapters are not Anime adapters merely because Anime is
            // transported through a TV or movie route. Anime crossover sources
            // must declare anime support, or be a known Anime-specific adapter.
            return supportedTypes.any { normalizePluginType(it) == "anime" } || isAnimeProvider()
        }
        return supportedTypes.map { normalizePluginType(it) }.contains(normalizedType)
    }
}

/** True when this scraper expects anime-specific IDs or absolute episode context. */
internal fun PluginScraper.isAnimeProvider(): Boolean =
    supportedTypes.any { normalizePluginType(it) == "anime" } ||
        repositoryUrl.contains("anime", ignoreCase = true) ||
        id.contains("anime", ignoreCase = true) ||
        name.contains("anime", ignoreCase = true) ||
        listOf(
            "anidap", "anikage", "animesalt", "hianime", "animepahe", "anidb", "anikototv",
            "aniwaves", "anitaku", "anistream", "miruro", "reanime", "animeheaven", "kurage",
            "animedekho", "anime-nexus", "animex", "justanime", "lunarx", "saltanime", "marin", "zoro",
            "yugen", "kickassanime", "allanime", "otaku",
        ).any { marker ->
            id.contains(marker, ignoreCase = true) || name.contains(marker, ignoreCase = true)
        }

private val absoluteEpisodeAnimeProviderIds = setOf(
    "hianime-site", "anikage", "miruro", "reanime", "animeheaven", "kickassanime",
    "anidap", "anistream", "animex", "justanime", "meguanimes",
)

/** Whether this provider expects flat, absolute numbering for Anime episodes. */
internal fun PluginScraper.usesAbsoluteAnimeEpisodeNumbering(): Boolean = when (animeEpisodeNumbering?.trim()?.lowercase()) {
    "absolute" -> true
    "season-local" -> false
    else -> id.substringAfterLast(':').trim().lowercase() in absoluteEpisodeAnimeProviderIds
}

internal fun PluginScraper.animeEpisodeNumberingMode(): String =
    if (usesAbsoluteAnimeEpisodeNumbering()) "absolute" else "season-local"

/** Avoid querying a flat catalog with a season-local number when the mapping is unknown. */
internal fun PluginScraper.requiresVerifiedAbsoluteAnimeEpisode(
    season: Int?,
    absoluteAnimeEpisode: Int?,
    isAnime: Boolean,
): Boolean = isAnime &&
    isAnimeProvider() &&
    usesAbsoluteAnimeEpisodeNumbering() &&
    season != null && season > 1 &&
    absoluteAnimeEpisode?.let { it > 0 } != true

/** Explicit manifest metadata wins; known flat-catalog adapters also survive stale manifests. */
internal fun PluginScraper.episodeArgument(
    episode: Int?,
    absoluteAnimeEpisode: Int?,
    isAnime: Boolean,
    season: Int? = null,
): Int? {
    if (!isAnime || !isAnimeProvider()) return episode
    val usesAbsoluteNumbering = animeEpisodeNumberingMode() == "absolute"
    if (!usesAbsoluteNumbering) return episode
    return absoluteAnimeEpisode?.takeIf { it > 0 }
        ?: episode?.takeIf { season == null || season <= 1 }
}

data class PluginRuntimeResult(
    val title: String,
    val name: String? = null,
    val url: String,
    val quality: String? = null,
    val size: String? = null,
    val language: String? = null,
    val provider: String? = null,
    val type: String? = null,
    val seeders: Int? = null,
    val peers: Int? = null,
    val infoHash: String? = null,
    val headers: Map<String, String>? = null,
    val subtitles: List<PluginSubtitleResult>? = null,
)

@Serializable
data class PluginSubtitleResult(
    val url: String,
    val language: String,
    val name: String? = null,
    val headers: Map<String, String>? = null
)

data class PluginsUiState(
    val pluginsEnabled: Boolean = true,
    val groupStreamsByRepository: Boolean = false,
    val repositories: List<PluginRepositoryItem> = emptyList(),
    val scrapers: List<PluginScraper> = emptyList(),
)

sealed interface AddPluginRepositoryResult {
    data class Success(val repository: PluginRepositoryItem) : AddPluginRepositoryResult
    data class Error(val message: String) : AddPluginRepositoryResult
}

@Serializable
internal data class StoredPluginsState(
    val pluginsEnabled: Boolean = true,
    val groupStreamsByRepository: Boolean = false,
    val repositories: List<StoredPluginRepository> = emptyList(),
    val scrapers: List<StoredPluginScraper> = emptyList(),
)

@Serializable
internal data class StoredPluginRepository(
    val manifestUrl: String,
    val name: String,
    val description: String? = null,
    val version: String? = null,
    val scraperCount: Int = 0,
    val lastUpdated: Long = 0L,
)

@Serializable
internal data class StoredPluginScraper(
    val id: String,
    val repositoryUrl: String,
    val name: String,
    val description: String,
    val version: String,
    val filename: String,
    val supportedTypes: List<String>,
    val enabled: Boolean,
    val manifestEnabled: Boolean,
    val hasSettings: Boolean = false,
    val logo: String? = null,
    val contentLanguage: List<String> = emptyList(),
    val formats: List<String>? = null,
    val animeEpisodeNumbering: String? = null,
    val code: String? = null,
)

internal data class RestoredPluginScraper(
    val scraper: PluginScraper,
    val requiresMigration: Boolean,
)

internal fun PluginsUiState.toStoredPluginsState(): StoredPluginsState =
    StoredPluginsState(
        pluginsEnabled = pluginsEnabled,
        groupStreamsByRepository = groupStreamsByRepository,
        repositories = repositories.map { repository ->
            StoredPluginRepository(
                manifestUrl = repository.manifestUrl,
                name = repository.name,
                description = repository.description,
                version = repository.version,
                scraperCount = repository.scraperCount,
                lastUpdated = repository.lastUpdated,
            )
        },
        scrapers = scrapers.map(PluginScraper::toStoredPluginScraper),
    )

internal fun PluginScraper.toStoredPluginScraper(): StoredPluginScraper =
    StoredPluginScraper(
        id = id,
        repositoryUrl = repositoryUrl,
        name = name,
        description = description,
        version = version,
        filename = filename,
        supportedTypes = supportedTypes,
        enabled = enabled,
        manifestEnabled = manifestEnabled,
        hasSettings = hasSettings,
        logo = logo,
        contentLanguage = contentLanguage,
        formats = formats,
        animeEpisodeNumbering = animeEpisodeNumbering,
        code = null,
    )

internal fun StoredPluginScraper.restorePluginScraper(
    loadCachedCode: (String) -> String?,
): RestoredPluginScraper? {
    val cachedCode = loadCachedCode(id)
    val resolvedCode = cachedCode ?: code ?: return null
    return RestoredPluginScraper(
        scraper = PluginScraper(
            id = id,
            repositoryUrl = repositoryUrl,
            name = name,
            description = description,
            version = version,
            filename = filename,
            supportedTypes = supportedTypes,
            enabled = enabled,
            manifestEnabled = manifestEnabled,
            hasSettings = hasSettings,
            logo = logo,
            contentLanguage = contentLanguage,
            formats = formats,
            animeEpisodeNumbering = animeEpisodeNumbering,
            code = resolvedCode,
        ),
        requiresMigration = code != null,
    )
}

internal fun normalizePluginType(value: String): String =
    when (value.lowercase()) {
        "series", "show", "other" -> "tv"
        else -> value.lowercase()
    }
