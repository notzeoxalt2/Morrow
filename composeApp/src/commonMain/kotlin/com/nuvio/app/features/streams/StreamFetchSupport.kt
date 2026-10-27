package com.nuvio.app.features.streams

import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.plugins.PluginRepositoryItem
import com.nuvio.app.features.plugins.PluginRuntimeResult
import com.nuvio.app.features.plugins.PluginScraper
import com.nuvio.app.features.plugins.isAnimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.streams_plugin_repository_fallback
import org.jetbrains.compose.resources.getString

internal data class InstalledStreamAddonTarget(
    val addonName: String,
    val addonId: String,
    val manifest: AddonManifest,
    val requestTypes: List<String>,
)

internal fun ManagedAddon.streamAddonInstanceId(manifestId: String): String =
    "addon:$manifestId:$manifestUrl"

internal data class PluginProviderGroup(
    val addonId: String,
    val addonName: String,
    val scrapers: List<PluginScraper>,
)

/** Provider display names are presentation only; repository-local IDs identify stream groups. */
internal fun List<AddonStreamGroup>.upsertStreamGroupByProviderId(group: AddonStreamGroup): List<AddonStreamGroup> {
    val index = indexOfFirst { it.addonId == group.addonId }
    return if (index < 0) {
        this + group
    } else {
        mapIndexed { currentIndex, current -> if (currentIndex == index) group else current }
    }
}

internal fun AddonManifest.streamRequestTypes(type: String, videoId: String): List<String> {
    val requestedType = normalizeStreamRequestType(type)
    val advertisedTypes = resources.asSequence()
        .filter { resource ->
            resource.name == "stream" &&
                (resource.idPrefixes.isEmpty() || resource.idPrefixes.any(videoId::startsWith))
        }
        .flatMap { it.types.asSequence() }
        .map(::normalizeStreamRequestType)
        .filter { it in setOf("anime", "tv", "movie") }
        .distinct()
        .toList()
    return if (requestedType == "anime") {
        advertisedTypes
    } else {
        advertisedTypes.filter { it == requestedType }
    }
}

internal suspend fun InstalledStreamAddonTarget.fetchStreams(
    videoId: String,
    forceRefresh: Boolean,
): Result<List<StreamItem>> = coroutineScope {
    val results = requestTypes.map { requestType ->
        async {
            runCatchingUnlessCancelled {
                val url = buildAddonResourceUrl(
                    manifestUrl = manifest.transportUrl,
                    resource = "stream",
                    type = requestType,
                    id = videoId,
                )
                val payload = fetchAddonResponseText(url = url, forceRefresh = forceRefresh)
                StreamParser.parse(
                    payload = payload,
                    addonName = addonName,
                    addonId = addonId,
                    addonLogo = manifest.logoUrl,
                )
            }
        }
    }.awaitAll()

    val streams = results.flatMap { it.getOrDefault(emptyList()) }
    val failures = results.mapNotNull { it.exceptionOrNull()?.message?.takeIf(String::isNotBlank) }
    when {
        results.isEmpty() -> Result.failure(IllegalStateException("No supported stream request types"))
        streams.isNotEmpty() || failures.size < results.size -> Result.success(streams)
        else -> Result.failure(IllegalStateException(failures.joinToString("; ")))
    }
}

private fun normalizeStreamRequestType(type: String): String = when (type.trim().lowercase()) {
    "anime" -> "anime"
    "movie", "movies" -> "movie"
    "tv", "series", "show", "shows" -> "tv"
    else -> type.trim().lowercase()
}

internal sealed interface StreamLoadCompletion {
    data class Addon(val group: AddonStreamGroup) : StreamLoadCompletion
    data class PluginScraper(
        val addonId: String,
        val streams: List<StreamItem>,
        val error: String?,
    ) : StreamLoadCompletion
}

internal fun List<PluginScraper>.toPluginProviderGroups(
    repositories: List<PluginRepositoryItem>,
    groupByRepository: Boolean,
): List<PluginProviderGroup> {
    if (!groupByRepository) {
        val duplicateNames = groupingBy { it.name.trim().lowercase() }.eachCount()
        val duplicateIds = groupingBy { "${it.repositoryUrl.trimEnd('/').lowercase()}|${it.id.lowercase()}" }.eachCount()
        val repoNameByUrl = repositories.associate { it.manifestUrl.trimEnd('/').lowercase() to it.name }
        return map { scraper ->
            val normalizedRepository = scraper.repositoryUrl.trimEnd('/').lowercase()
            val repositoryLabel = repoNameByUrl[normalizedRepository]
                .orEmpty()
                .ifBlank { scraper.repositoryUrl.fallbackRepositoryLabel() }
            val nameIsDuplicated = duplicateNames[scraper.name.trim().lowercase()]?.let { it > 1 } == true
            val groupIdentity = "${scraper.repositoryUrl.trimEnd('/').lowercase()}|${scraper.id.lowercase()}"
            val idIsDuplicated = duplicateIds[groupIdentity]?.let { it > 1 } == true
            val displayName = buildString {
                append(scraper.name)
                if (nameIsDuplicated) append(" · ").append(repositoryLabel)
                if (idIsDuplicated) append(" · ").append(scraper.id)
            }
            PluginProviderGroup(
                addonId = "plugin:$normalizedRepository:${scraper.id.lowercase()}",
                addonName = displayName,
                scrapers = listOf(scraper),
            )
        }
    }

    val repoNameByUrl = repositories.associate { it.manifestUrl to it.name }
    return groupBy { it.repositoryUrl }
        .map { (repositoryUrl, scrapers) ->
            PluginProviderGroup(
                addonId = "plugin-repo:${repositoryUrl.lowercase()}",
                addonName = repoNameByUrl[repositoryUrl].orEmpty().ifBlank { repositoryUrl.fallbackRepositoryLabel() },
                scrapers = scrapers.sortedBy { it.name.lowercase() },
            )
        }
        .sortedBy { it.addonName.lowercase() }
}

internal fun List<AddonStreamGroup>.toEmptyStateReason(anyLoading: Boolean): StreamsEmptyStateReason? {
    if (anyLoading || any { it.streams.isNotEmpty() }) {
        return null
    }

    return if (isNotEmpty() && all { !it.error.isNullOrBlank() }) {
        StreamsEmptyStateReason.StreamFetchFailed
    } else {
        StreamsEmptyStateReason.NoStreamsFound
    }
}

internal suspend fun <T> runCatchingUnlessCancelled(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

internal fun PluginRuntimeResult.toStreamItem(
    scraper: PluginScraper,
    addonName: String = scraper.name,
    addonId: String = "plugin:${scraper.id}",
    includeScraperNameInSubtitle: Boolean = false,
): StreamItem {
    val subtitleParts = listOfNotNull(
        scraper.name.takeIf { includeScraperNameInSubtitle && it.isNotBlank() },
        quality?.takeIf { it.isNotBlank() },
        size?.takeIf { it.isNotBlank() },
        language?.takeIf { it.isNotBlank() },
    )
    val requestHeaders = headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val headerName = key.trim()
            val headerValue = value.trim()
            if (headerName.isBlank() || headerValue.isBlank() || headerName.equals("Range", ignoreCase = true)) {
                null
            } else {
                headerName to headerValue
            }
        }
        .toMap()

    return StreamItem(
        name = name ?: title,
        title = title,
        description = subtitleParts.joinToString(" • ").ifBlank { null },
        url = url,
        infoHash = infoHash,
        sourceName = scraper.name,
        addonName = addonName,
        addonId = addonId,
        addonLogo = scraper.logo?.takeIf { it.isNotBlank() } ?: providerLogoUrl(scraper.name),
        streamType = normalizeStreamType(type),
        behaviorHints = if (requestHeaders.isEmpty()) {
            StreamBehaviorHints()
        } else {
            StreamBehaviorHints(
                notWebReady = true,
                proxyHeaders = StreamProxyHeaders(request = requestHeaders),
            )
        },
        externalSubtitles = subtitles?.map {
            StreamSubtitle(
                url = it.url,
                language = it.language,
                name = it.name,
                headers = it.headers
            )
        } ?: emptyList()
    )
}

/** Reject a result only when it explicitly identifies a different season/episode. */
internal fun StreamItem.matchesRequestedEpisode(season: Int?, episode: Int?): Boolean =
    matchesRequestedEpisode(season, episode, listOfNotNull(name, title, description).joinToString(" "))

internal fun PluginRuntimeResult.matchesRequestedEpisode(season: Int?, episode: Int?): Boolean =
    matchesRequestedEpisode(season, episode, listOfNotNull(name, title, quality, size).joinToString(" "))

private fun matchesRequestedEpisode(season: Int?, episode: Int?, metadata: String): Boolean {
    if (season == null || episode == null) return true
    val explicitEpisode = Regex("(?i)\\bS(\\d{1,2})[\\s._-]*E(\\d{1,3})\\b|\\b(\\d{1,2})x(\\d{1,3})\\b")
        .find(metadata) ?: return true
    val groups = explicitEpisode.groupValues
    val foundSeason = (groups[1].ifBlank { groups[3] }).toIntOrNull() ?: return true
    val foundEpisode = (groups[2].ifBlank { groups[4] }).toIntOrNull() ?: return true
    return foundSeason == season && foundEpisode == episode
}

internal fun List<StreamItem>.sortedForGroupedDisplay(): List<StreamItem> =
    sortedWith(
        compareBy<StreamItem>(
            { providerDisplayName(it.addonName).lowercase() },
            { it.sourceName.orEmpty().lowercase() },
            { it.serverDisplayKey() },
            { it.audioGroup().ordinal },
            { -Regex("(2160|1440|1080|720|480|360)p", RegexOption.IGNORE_CASE).find(it.streamLabel + " " + it.streamSubtitle.orEmpty())?.groupValues?.get(1).let { it?.toIntOrNull() ?: 0 } },
            { it.streamLabel.lowercase() },
            { it.streamSubtitle.orEmpty().lowercase() },
        ),
    )

private fun String.fallbackRepositoryLabel(): String {
    val withoutQuery = substringBefore("?")
    val withoutManifest = withoutQuery.removeSuffix("/manifest.json")
    val host = withoutManifest.substringAfter("://", withoutManifest).substringBefore('/')
    return host.ifBlank {
        withoutManifest.substringAfterLast('/').ifBlank {
            runBlocking { getString(Res.string.streams_plugin_repository_fallback) }
        }
    }
}

/** Manifest capabilities, rather than provider names, determine source filters. */
internal fun sourceCategories(types: List<String>): Set<String> = types.mapNotNull {
    when (it.lowercase()) {
        "anime" -> "anime"
        "movie", "movies" -> "movies"
        "tv", "series", "show", "shows" -> "series"
        else -> null
    }
}.toSet()

/** Anime adapters often advertise legacy movie/TV routes; use adapter identity as a fallback capability. */
internal fun PluginScraper.sourceCategories(): Set<String> =
    sourceCategories(supportedTypes) + if (isAnimeProvider()) setOf("anime") else emptySet()

internal fun AddonStreamGroup.matchesSourceCategory(category: String): Boolean =
    if (category == "movies-series") sourceCategories.any { it == "movies" || it == "series" }
    else category in sourceCategories
