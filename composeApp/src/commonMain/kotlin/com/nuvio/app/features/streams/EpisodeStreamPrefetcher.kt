package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.pluginContentId
import com.nuvio.app.features.providers.offline.OfflineAnimeProviders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * In-memory multi-episode stream cache.
 * Keeps streams for the current and adjacent episodes loaded in memory
 * so switching episodes or opening streams is instant (0ms).
 */
object StreamSessionCache {
    private val log = Logger.withTag("StreamSessionCache")
    private const val MAX_CACHE_AGE_MS = 45 * 60 * 1000L // 45 minutes

    data class CacheEntry(
        val groups: List<AddonStreamGroup>,
        val timestamp: Long,
        val isComplete: Boolean = false,
    )

    private val cache = mutableMapOf<String, CacheEntry>()

    private fun makeKey(type: String, videoId: String, season: Int?, episode: Int?): String =
        "${type.lowercase()}::$videoId::$season::$episode"

    private fun makeMetaKey(parentMetaId: String, season: Int?, episode: Int?): String =
        "meta::$parentMetaId::$season::$episode"

    fun get(
        type: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
        parentMetaId: String? = null,
    ): List<AddonStreamGroup>? {
        val now = epochMs()
        val key = makeKey(type, videoId, season, episode)
        val entry = cache[key] ?: parentMetaId?.let { cache[makeMetaKey(it, season, episode)] }
        if (entry != null) {
            if (now - entry.timestamp < MAX_CACHE_AGE_MS && entry.groups.any { it.streams.isNotEmpty() }) {
                log.d { "Cache hit for $key (${entry.groups.sumOf { it.streams.size }} streams, complete=${entry.isComplete})" }
                return entry.groups.map { it.copy(isLoading = false) }
            } else {
                cache.remove(key)
                parentMetaId?.let { cache.remove(makeMetaKey(it, season, episode)) }
            }
        }
        return null
    }

    fun isComplete(
        type: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
        parentMetaId: String? = null,
    ): Boolean {
        val key = makeKey(type, videoId, season, episode)
        val entry = cache[key] ?: parentMetaId?.let { cache[makeMetaKey(it, season, episode)] } ?: return false
        val now = epochMs()
        return (now - entry.timestamp < MAX_CACHE_AGE_MS) && entry.isComplete && entry.groups.any { it.streams.isNotEmpty() }
    }

    fun has(
        type: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
        parentMetaId: String? = null,
    ): Boolean {
        return get(type, videoId, season, episode, parentMetaId) != null
    }

    fun put(
        type: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
        parentMetaId: String? = null,
        groups: List<AddonStreamGroup>,
        isComplete: Boolean = false,
    ) {
        val validGroups = groups.filter { it.streams.isNotEmpty() }
        if (validGroups.isEmpty()) return

        val key = makeKey(type, videoId, season, episode)
        val existing = cache[key] ?: parentMetaId?.let { cache[makeMetaKey(it, season, episode)] }

        val mergedGroups = if (existing != null) {
            val existingById = existing.groups.associateBy { it.addonId }.toMutableMap()
            validGroups.forEach { newGroup ->
                val prev = existingById[newGroup.addonId]
                if (prev != null) {
                    val combinedStreams = (prev.streams + newGroup.streams).distinctBy { (it.url ?: it.title).orEmpty() }
                    existingById[newGroup.addonId] = prev.copy(streams = combinedStreams, isLoading = false)
                } else {
                    existingById[newGroup.addonId] = newGroup.copy(isLoading = false)
                }
            }
            existingById.values.toList()
        } else {
            validGroups.map { it.copy(isLoading = false) }
        }

        val entry = CacheEntry(
            groups = mergedGroups,
            timestamp = epochMs(),
            isComplete = isComplete || existing?.isComplete == true,
        )
        cache[key] = entry
        if (!parentMetaId.isNullOrBlank()) {
            cache[makeMetaKey(parentMetaId, season, episode)] = entry
        }
        log.d { "Cached streams for $key (${mergedGroups.sumOf { it.streams.size }} streams, complete=${entry.isComplete})" }
    }

    fun clear() {
        cache.clear()
    }
}

/**
 * Background prefetcher for adjacent episodes (e.g. Ep 2 and Ep 3 while watching Ep 1).
 */
object EpisodeStreamPrefetcher {
    private val log = Logger.withTag("EpisodePrefetcher")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var prefetchJob: Job? = null

    data class EpisodeTarget(
        val season: Int,
        val episode: Int,
        val videoId: String,
    )

    fun prefetchNextEpisodes(
        currentType: String,
        currentVideoId: String,
        parentMetaId: String?,
        currentSeason: Int?,
        currentEpisode: Int?,
        metaVideos: List<MetaVideo> = emptyList(),
    ) {
        if (currentSeason == null || currentEpisode == null) return

        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            // Delay so current episode's streams have 100% priority
            delay(5000L)

            val targets = resolveTargets(
                currentVideoId = currentVideoId,
                parentMetaId = parentMetaId,
                currentSeason = currentSeason,
                currentEpisode = currentEpisode,
                metaVideos = metaVideos,
            )

            for (target in targets) {
                if (StreamSessionCache.isComplete(currentType, target.videoId, target.season, target.episode, parentMetaId)) {
                    log.d { "Prefetch skip: already fully cached S${target.season}E${target.episode}" }
                    continue
                }

                log.i { "Prefetching streams for next episode: S${target.season}E${target.episode} (${target.videoId})" }
                runCatching {
                    prefetchSingleEpisode(
                        type = currentType,
                        videoId = target.videoId,
                        season = target.season,
                        episode = target.episode,
                        parentMetaId = parentMetaId,
                    )
                }.onFailure { e ->
                    log.w(e) { "Prefetch failed for S${target.season}E${target.episode}" }
                }

                delay(1500L)
            }
        }
    }

    private fun resolveTargets(
        currentVideoId: String,
        parentMetaId: String?,
        currentSeason: Int,
        currentEpisode: Int,
        metaVideos: List<MetaVideo>,
    ): List<EpisodeTarget> {
        val targets = mutableListOf<EpisodeTarget>()

        for (offset in 1..2) {
            val targetEp = currentEpisode + offset

            // 1. Try to find in metaVideos
            val fromMeta = metaVideos.firstOrNull { it.season == currentSeason && it.episode == targetEp }
            if (fromMeta != null && fromMeta.id.isNotBlank()) {
                targets.add(EpisodeTarget(currentSeason, targetEp, fromMeta.id))
                continue
            }

            // 2. Synthesize from currentVideoId or parentMetaId
            val synthesizedId = when {
                currentVideoId.startsWith("tt") && currentVideoId.contains(":") -> {
                    val imdbBase = currentVideoId.substringBefore(":")
                    "$imdbBase:$currentSeason:$targetEp"
                }
                parentMetaId?.startsWith("tt") == true -> {
                    val imdbBase = parentMetaId.substringBefore(":")
                    "$imdbBase:$currentSeason:$targetEp"
                }
                currentVideoId.startsWith("kitsu:") -> {
                    val kitsuBase = currentVideoId.substringBeforeLast(":")
                    "$kitsuBase:$targetEp"
                }
                else -> null
            }

            if (synthesizedId != null) {
                targets.add(EpisodeTarget(currentSeason, targetEp, synthesizedId))
            }
        }

        return targets
    }

    private suspend fun prefetchSingleEpisode(
        type: String,
        videoId: String,
        season: Int,
        episode: Int,
        parentMetaId: String?,
    ) {
        val resolvedParentId = parentMetaId?.takeIf { it.isNotBlank() }
            ?: videoId.substringBefore(':').takeIf { it.isNotBlank() }
            ?: videoId
        val meta = MetaDetailsRepository.getActiveMeta(resolvedParentId)
            ?: MetaDetailsRepository.getActiveMeta(videoId)
        val cleanTitle = meta?.name ?: videoId.substringBefore(':')
        val mediaLookupId = meta?.imdbId ?: when {
            videoId.startsWith("tt") -> videoId.substringBefore(":")
            resolvedParentId.startsWith("tt") -> resolvedParentId
            else -> null
        }
        val metaYear = meta?.releaseInfo?.take(4)

        val collectedGroups = mutableListOf<AddonStreamGroup>()

        val isAnime = type.equals("anime", ignoreCase = true) ||
            videoId.startsWith("kitsu:") || videoId.startsWith("mal:") || videoId.startsWith("anilist:") ||
            resolvedParentId.startsWith("kitsu:") || resolvedParentId.startsWith("mal:") || resolvedParentId.startsWith("anilist:")

        val offlineJob = if (isAnime) {
            scope.launch {
                runCatching {
                    OfflineAnimeProviders.fetchAllStreams(
                        title = cleanTitle,
                        mediaLookupId = mediaLookupId,
                        type = type,
                        year = metaYear,
                        season = season,
                        episode = episode,
                        onGroupLoaded = { group ->
                            if (group.streams.isNotEmpty()) {
                                synchronized(collectedGroups) {
                                    collectedGroups.add(group.copy(isLoading = false))
                                }
                            }
                        }
                    )
                }
            }
        } else null

        // 2. Stremio Addons
        val installedAddons = AddonRepository.uiState.value.addons.enabledAddons()
        val streamAddons = installedAddons.mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            if (!manifest.supportsStream(type, videoId)) return@mapNotNull null
            InstalledStreamAddonTarget(
                addonName = addon.displayTitle.ifBlank { manifest.name },
                addonId = addon.streamAddonInstanceId(manifest.id),
                manifest = manifest,
            )
        }

        val addonJobs = streamAddons.map { addon ->
            scope.launch {
                runCatching {
                    withTimeoutOrNull(10_000L) {
                        val url = buildAddonResourceUrl(
                            manifestUrl = addon.manifest.transportUrl,
                            resource = "stream",
                            type = type,
                            id = videoId,
                        )
                        val payload = fetchAddonResponseText(url = url, forceRefresh = false)
                        val parsed = StreamParser.parse(
                            payload = payload,
                            addonName = addon.addonName,
                            addonId = addon.addonId,
                            addonLogo = addon.manifest.logoUrl,
                        )
                        if (parsed.isNotEmpty()) {
                            synchronized(collectedGroups) {
                                collectedGroups.add(
                                    AddonStreamGroup(
                                        addonName = addon.addonName,
                                        addonId = addon.addonId,
                                        streams = parsed,
                                        isLoading = false,
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Community Plugins
        val pluginJobs = if (AppFeaturePolicy.pluginsEnabled) {
            val pluginScrapers = PluginRepository.getEnabledScrapersForType(type)
            val pluginUiState = PluginRepository.uiState.value
            val providerGroups = pluginScrapers.toPluginProviderGroups(
                repositories = pluginUiState.repositories,
                groupByRepository = pluginUiState.groupStreamsByRepository,
            )
            val semaphore = Semaphore(2)
            providerGroups.flatMap { pGroup ->
                pGroup.scrapers.map { scraper ->
                    scope.launch {
                        runCatching {
                            semaphore.withPermit {
                                withTimeoutOrNull(15_000L) {
                                    PluginRepository.executeScraper(
                                        scraper = scraper,
                                        tmdbId = pluginContentId(videoId, season, episode),
                                        mediaType = type,
                                        season = season,
                                        episode = episode,
                                    ).getOrNull()?.let { results ->
                                        if (results.isNotEmpty()) {
                                            val items = results.map {
                                                it.toStreamItem(
                                                    scraper = scraper,
                                                    addonName = pGroup.addonName,
                                                    addonId = pGroup.addonId,
                                                )
                                            }
                                            synchronized(collectedGroups) {
                                                collectedGroups.add(
                                                    AddonStreamGroup(
                                                        addonName = pGroup.addonName,
                                                        addonId = pGroup.addonId,
                                                        streams = items,
                                                        isLoading = false,
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else emptyList()

        offlineJob?.join()
        addonJobs.forEach { it.join() }
        pluginJobs.forEach { it.join() }

        if (collectedGroups.isNotEmpty()) {
            StreamSessionCache.put(
                type = type,
                videoId = videoId,
                season = season,
                episode = episode,
                parentMetaId = parentMetaId,
                groups = collectedGroups,
                isComplete = true,
            )
            log.i { "Successfully prefetched and cached ${collectedGroups.sumOf { it.streams.size }} streams for S${season}E${episode}" }
        }
    }
}
