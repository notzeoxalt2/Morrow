package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DebridStreamPresentation
import com.nuvio.app.features.debrid.DirectDebridStreamPreparer
import com.nuvio.app.features.debrid.LocalDebridAvailabilityService
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.PluginsUiState
import com.nuvio.app.features.plugins.normalizePluginType
import com.nuvio.app.features.plugins.pluginContentId
import com.nuvio.app.features.providers.offline.OfflineAnimeProviders
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.InstalledStreamAddonTarget
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamBadgePresentation
import com.nuvio.app.features.streams.StreamBadgeSettingsRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamLoadCompletion
import com.nuvio.app.features.streams.StreamParser
import com.nuvio.app.features.streams.StreamSessionCache
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.streams.runCatchingUnlessCancelled
import com.nuvio.app.features.streams.sortedForGroupedDisplay
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.nuvio.app.features.streams.toEmptyStateReason
import com.nuvio.app.features.streams.toPluginProviderGroups
import com.nuvio.app.features.streams.toStreamItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

/**
 * Dedicated stream fetcher for use inside the player (sources & episodes panels).
 * Uses its own state so it doesn't interfere with the main [StreamsRepository].
 */
object PlayerStreamsRepository {
    private val log = Logger.withTag("PlayerStreamsRepo")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // source panel
    private val _sourceState = MutableStateFlow(StreamsUiState())
    val sourceState: StateFlow<StreamsUiState> = _sourceState.asStateFlow()
    private var sourceJob: Job? = null
    private var sourceRequestKey: String? = null

    // episode streams panel
    private val _episodeStreamsState = MutableStateFlow(StreamsUiState())
    val episodeStreamsState: StateFlow<StreamsUiState> = _episodeStreamsState.asStateFlow()
    private var episodeStreamsJob: Job? = null
    private var episodeStreamsRequestKey: String? = null

    fun loadSources(
        type: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
        forceRefresh: Boolean = false,
    ) {
        fetchStreams(
            panelName = "sources",
            type = type,
            videoId = videoId,
            season = season,
            episode = episode,
            forceRefresh = forceRefresh,
            stateFlow = _sourceState,
            requestKeyHolder = { sourceRequestKey },
            setRequestKey = { sourceRequestKey = it },
            jobHolder = { sourceJob },
            setJob = { sourceJob = it },
        )
    }

    fun loadEpisodeStreams(
        type: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
        forceRefresh: Boolean = false,
    ) {
        fetchStreams(
            panelName = "episodeStreams",
            type = type,
            videoId = videoId,
            season = season,
            episode = episode,
            forceRefresh = forceRefresh,
            stateFlow = _episodeStreamsState,
            requestKeyHolder = { episodeStreamsRequestKey },
            setRequestKey = { episodeStreamsRequestKey = it },
            jobHolder = { episodeStreamsJob },
            setJob = { episodeStreamsJob = it },
        )
    }

    fun stopSourcesLoading() {
        PluginRepository.setLocalPluginSearchPaused(true)
        cancelSourceJob()
    }

    fun pauseSearchForPlayback() {
        PluginRepository.setLocalPluginSearchPaused(true)
        cancelSourceJob()
        cancelEpisodeStreamsJob()
    }

    private fun cancelSourceJob() {
        val job = sourceJob ?: return
        job.cancel()
        sourceJob = null
        sourceRequestKey = null
        _sourceState.update { current ->
            current.copy(
                isAnyLoading = false,
                groups = current.groups.map { group ->
                    if (group.isLoading) group.copy(isLoading = false) else group
                },
            )
        }
    }

    private fun cancelEpisodeStreamsJob() {
        val job = episodeStreamsJob ?: return
        job.cancel()
        episodeStreamsJob = null
        episodeStreamsRequestKey = null
        _episodeStreamsState.update { current ->
            current.copy(
                isAnyLoading = false,
                groups = current.groups.map { group ->
                    if (group.isLoading) group.copy(isLoading = false) else group
                },
            )
        }
    }

    fun selectSourceFilter(addonId: String?) {
        _sourceState.update { it.copy(selectedFilter = addonId) }
    }

    fun selectEpisodeStreamsFilter(addonId: String?) {
        _episodeStreamsState.update { it.copy(selectedFilter = addonId) }
    }

    fun clearEpisodeStreams() {
        PluginRepository.setLocalPluginSearchPaused(true)
        episodeStreamsJob?.cancel()
        episodeStreamsRequestKey = null
        _episodeStreamsState.value = StreamsUiState()
    }

    fun clearAll() {
        PluginRepository.setLocalPluginSearchPaused(true)
        sourceJob?.cancel()
        sourceRequestKey = null
        _sourceState.value = StreamsUiState()
        clearEpisodeStreams()
    }

    private fun fetchStreams(
        panelName: String,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean,
        stateFlow: MutableStateFlow<StreamsUiState>,
        requestKeyHolder: () -> String?,
        setRequestKey: (String?) -> Unit,
        jobHolder: () -> Job?,
        setJob: (Job) -> Unit,
    ) {
        val pluginUiState = if (AppFeaturePolicy.pluginsEnabled) {
            PluginRepository.initialize()
            PluginRepository.uiState.value
        } else {
            PluginsUiState(pluginsEnabled = false)
        }
        val requestKey = "$type::$videoId::$season::$episode::pluginsGrouped=${pluginUiState.groupStreamsByRepository}"
        PluginRepository.setLocalPluginSearchPaused(false)
        val current = stateFlow.value
        if (
            !forceRefresh &&
            requestKeyHolder() == requestKey &&
            (current.groups.isNotEmpty() || current.emptyStateReason != null || current.isAnyLoading)
        ) {
            log.d { "skip $panelName request=$requestKey reason=already-active ${current.streamDiagnostics()}" }
            return
        }

        log.d { "start $panelName request=$requestKey force=$forceRefresh previous=${current.streamDiagnostics()}" }
        setRequestKey(requestKey)
        jobHolder()?.cancel()
        stateFlow.value = StreamsUiState()

        val streamBadgeRules = StreamBadgeSettingsRepository.snapshot()
        val embeddedStreams = MetaDetailsRepository.findEmbeddedStreams(videoId)
        if (embeddedStreams.isNotEmpty()) {
            log.d { "using embedded $panelName request=$requestKey streams=${embeddedStreams.size}" }
            val group = AddonStreamGroup(
                addonName = embeddedStreams.first().addonName,
                addonId = "embedded",
                streams = embeddedStreams,
                isLoading = false,
            )
            val presentedGroup = StreamBadgePresentation.apply(
                groups = listOf(group),
                rules = streamBadgeRules,
            ).firstOrNull() ?: group
            stateFlow.value = StreamsUiState(
                groups = listOf(presentedGroup),
                activeAddonIds = setOf("embedded"),
                isAnyLoading = false,
            )
            log.d { "finish $panelName request=$requestKey reason=embedded ${stateFlow.value.streamDiagnostics()}" }
            return
        }

        val debridSettings = DebridSettingsRepository.snapshot()
        val isCacheComplete = if (!forceRefresh) {
            StreamSessionCache.isComplete(
                type = type,
                videoId = videoId,
                season = season,
                episode = episode,
            )
        } else false

        val cachedSessionStreams = if (!forceRefresh) {
            StreamSessionCache.get(
                type = type,
                videoId = videoId,
                season = season,
                episode = episode,
            )
        } else null

        if (isCacheComplete && cachedSessionStreams != null && cachedSessionStreams.isNotEmpty()) {
            log.i { "PlayerStreamsRepo instant complete cache hit: ${cachedSessionStreams.size} stream groups for $type $videoId (S$season:E$episode)" }
            val presentedGroups = cachedSessionStreams.map { group ->
                val badgeGroup = StreamBadgePresentation.apply(
                    groups = listOf(group),
                    rules = streamBadgeRules,
                ).firstOrNull() ?: group
                DebridStreamPresentation.apply(
                    groups = listOf(badgeGroup),
                    settings = debridSettings,
                ).firstOrNull() ?: badgeGroup
            }
            stateFlow.value = StreamsUiState(
                groups = presentedGroups,
                activeAddonIds = presentedGroups.map { it.addonId }.toSet(),
                isAnyLoading = false,
            )
            return
        }

        val installedAddons = AddonRepository.uiState.value.addons.enabledAddons()
        PlayerSettingsRepository.ensureLoaded()
        val playerSettings = PlayerSettingsRepository.uiState.value
        val resolvedParentId = videoId.substringBefore(':').takeIf { it.isNotBlank() } ?: videoId
        val meta = MetaDetailsRepository.getActiveMeta(resolvedParentId)
            ?: MetaDetailsRepository.getActiveMeta(videoId)
        val isAnime = type.equals("anime", ignoreCase = true) ||
            videoId.startsWith("kitsu:") || videoId.startsWith("mal:") || videoId.startsWith("anilist:") ||
            resolvedParentId.startsWith("kitsu:") || resolvedParentId.startsWith("mal:") || resolvedParentId.startsWith("anilist:") ||
            meta?.genres?.any { it.equals("Anime", ignoreCase = true) || it.contains("anime", ignoreCase = true) } == true ||
            (meta?.genres?.any { it.contains("animation", ignoreCase = true) } == true &&
                (meta.country?.contains("JP", ignoreCase = true) == true ||
                 meta.country?.contains("Japan", ignoreCase = true) == true ||
                 meta.language?.equals("ja", ignoreCase = true) == true ||
                 meta.language?.contains("Japanese", ignoreCase = true) == true))

        val effectiveType = if (isAnime) "anime" else type
        val pluginScrapers = if (AppFeaturePolicy.pluginsEnabled) {
            PluginRepository.getEnabledScrapersForType(effectiveType).filter { scraper ->
                val isAnimeScraper = scraper.supportedTypes.any { normalizePluginType(it) == "anime" } ||
                    scraper.repositoryUrl.contains("anime", ignoreCase = true) ||
                    scraper.id.contains("anime", ignoreCase = true) ||
                    scraper.name.contains("anime", ignoreCase = true) ||
                    listOf("anikage", "animesalt", "hianime", "animepahe", "anidb", "anikototv", "aniwaves", "anitaku", "miruro", "reanime", "animeheaven", "kurage", "animedekho", "anime-nexus", "lunarx", "saltanime", "marin", "zoro", "yugen", "kickassanime", "allanime", "otaku").any {
                        scraper.id.contains(it, ignoreCase = true) || scraper.name.contains(it, ignoreCase = true)
                    }
                if (isAnime) true else !isAnimeScraper
            }
        } else {
            emptyList()
        }
        val pluginProviderGroups = pluginScrapers.toPluginProviderGroups(
            repositories = pluginUiState.repositories,
            groupByRepository = pluginUiState.groupStreamsByRepository,
        )

        if (installedAddons.isEmpty() && pluginProviderGroups.isEmpty()) {
            stateFlow.value = StreamsUiState(
                isAnyLoading = false,
                emptyStateReason = com.nuvio.app.features.streams.StreamsEmptyStateReason.NoAddonsInstalled,
            )
            log.d { "finish $panelName request=$requestKey reason=no-addons ${stateFlow.value.streamDiagnostics()}" }
            return
        }

        val streamAddons = installedAddons
            .mapNotNull { addon ->
                val manifest = addon.manifest ?: return@mapNotNull null
                val supportsRequestedStream = manifest.resources.any { resource ->
                    resource.name == "stream" &&
                        resource.types.contains(type) &&
                        (resource.idPrefixes.isEmpty() ||
                            resource.idPrefixes.any { videoId.startsWith(it) })
                }
                if (!supportsRequestedStream) return@mapNotNull null

                InstalledStreamAddonTarget(
                    addonName = addon.displayTitle.ifBlank { manifest.name },
                    addonId = addon.streamAddonInstanceId(manifest.id),
                    manifest = manifest,
                )
            }

        val offlineAnimeGroups = if (isAnime) {
            listOf(
                AddonStreamGroup(
                    addonName = "AnimeDekho",
                    addonId = "offline:animedekho",
                    streams = emptyList(),
                    isLoading = true,
                ),
                AddonStreamGroup(
                    addonName = "SaltAnime",
                    addonId = "offline:saltanime",
                    streams = emptyList(),
                    isLoading = true,
                ),
                AddonStreamGroup(
                    addonName = "HiAnime",
                    addonId = "offline:hianime",
                    streams = emptyList(),
                    isLoading = true,
                ),
                AddonStreamGroup(
                    addonName = "Anikage",
                    addonId = "offline:anikage",
                    streams = emptyList(),
                    isLoading = true,
                ),
                AddonStreamGroup(
                    addonName = "Miruro",
                    addonId = "offline:miruro",
                    streams = emptyList(),
                    isLoading = true,
                ),
                AddonStreamGroup(
                    addonName = "AnimePahe",
                    addonId = "offline:animepahe",
                    streams = emptyList(),
                    isLoading = true,
                ),
                AddonStreamGroup(
                    addonName = "AnimeX",
                    addonId = "offline:animex",
                    streams = emptyList(),
                    isLoading = true,
                ),
                AddonStreamGroup(
                    addonName = "AnimeTVPlus",
                    addonId = "offline:animetvplus",
                    streams = emptyList(),
                    isLoading = true,
                ),
                AddonStreamGroup(
                    addonName = "Aniflix",
                    addonId = "offline:aniflix",
                    streams = emptyList(),
                    isLoading = true,
                ),
                AddonStreamGroup(
                    addonName = "AniWaves",
                    addonId = "offline:aniwaves",
                    streams = emptyList(),
                    isLoading = true,
                ),
                AddonStreamGroup(
                    addonName = "Kaa",
                    addonId = "offline:kaa",
                    streams = emptyList(),
                    isLoading = true,
                ),
                AddonStreamGroup(
                    addonName = "AnimeHeaven",
                    addonId = "offline:animeheaven",
                    streams = emptyList(),
                    isLoading = true,
                ),
                AddonStreamGroup(
                    addonName = "JustAnime",
                    addonId = "offline:justanime",
                    streams = emptyList(),
                    isLoading = true,
                ),
                AddonStreamGroup(
                    addonName = "KissKH",
                    addonId = "offline:kisskh",
                    streams = emptyList(),
                    isLoading = true,
                ),
            )
        } else {
            emptyList()
        }

        if (streamAddons.isEmpty() && pluginProviderGroups.isEmpty() && offlineAnimeGroups.isEmpty()) {
            stateFlow.value = StreamsUiState(
                isAnyLoading = false,
                emptyStateReason = com.nuvio.app.features.streams.StreamsEmptyStateReason.NoCompatibleAddons,
            )
            log.d {
                "finish $panelName request=$requestKey reason=no-compatible-addons " +
                    "installed=${installedAddons.size} ${stateFlow.value.streamDiagnostics()}"
            }
            return
        }

        val installedAddonOrder = streamAddons.map { it.addonName } + offlineAnimeGroups.map { it.addonName }
        log.d {
            "targets $panelName request=$requestKey installed=${installedAddons.size} " +
                "compatible=${streamAddons.size} plugins=${pluginScrapers.size}"
        }
        val initialGroups = StreamAutoPlaySelector.orderAddonStreams((streamAddons.map { addon ->
            AddonStreamGroup(
                addonName = addon.addonName,
                addonId = addon.addonId,
                streams = emptyList(),
                isLoading = true,
            )
        } + pluginProviderGroups.map { providerGroup ->
            AddonStreamGroup(
                addonName = providerGroup.addonName,
                addonId = providerGroup.addonId,
                streams = emptyList(),
                isLoading = true,
            )
        } + offlineAnimeGroups).distinctBy { it.addonName.lowercase().trim() }, installedAddonOrder)
        val isInitiallyLoading = initialGroups.any { it.isLoading }
        stateFlow.value = StreamsUiState(
            groups = initialGroups,
            activeAddonIds = initialGroups.map { it.addonId }.toSet(),
            isAnyLoading = isInitiallyLoading,
        )
        log.d { "state $panelName request=$requestKey stage=initial ${stateFlow.value.streamDiagnostics()}" }

        val job = scope.launch {
            val installedAddonIds = streamAddons.map { it.addonId }.toSet()
            val installedAddonNames = installedAddonOrder.toSet()
            val pluginRemainingByAddonId = pluginProviderGroups
                .associate { it.addonId to it.scrapers.size }
                .toMutableMap()
            val pluginFirstErrorByAddonId = mutableMapOf<String, String>()
            val totalTasks = streamAddons.size + pluginProviderGroups.sumOf { it.scrapers.size }
            val completions = Channel<StreamLoadCompletion>(capacity = Channel.BUFFERED)
            val debridAvailabilityJobs = mutableListOf<Job>()

            fun publishCompletion(completion: StreamLoadCompletion) {
                if (completions.trySend(completion).isFailure) {
                    log.d { "Ignoring late player stream load completion after channel close" }
                }
            }

            fun presentStreamGroup(group: AddonStreamGroup): AddonStreamGroup {
                val badgeGroup = StreamBadgePresentation.apply(
                    groups = listOf(group),
                    rules = streamBadgeRules,
                ).firstOrNull() ?: group
                return DebridStreamPresentation.apply(
                    groups = listOf(badgeGroup),
                    settings = debridSettings,
                ).firstOrNull() ?: badgeGroup
            }

            fun publishStreamGroup(group: AddonStreamGroup) {
                var nextState: StreamsUiState? = null
                stateFlow.update { current ->
                    val exists = current.groups.any {
                        it.addonId == group.addonId || it.addonName.equals(group.addonName, ignoreCase = true)
                    }
                    val updatedList = if (exists) {
                        current.groups.map { currentGroup ->
                            if (currentGroup.addonId == group.addonId || currentGroup.addonName.equals(group.addonName, ignoreCase = true)) group else currentGroup
                        }
                    } else {
                        current.groups + group
                    }
                    val updated = StreamAutoPlaySelector.orderAddonStreams(
                        groups = updatedList.distinctBy { it.addonName.lowercase().trim() },
                        installedOrder = installedAddonOrder,
                    )
                    val anyLoading = updated.any { it.isLoading }
                    if (!anyLoading || updated.any { it.streams.isNotEmpty() }) {
                        StreamSessionCache.put(
                            type = type,
                            videoId = videoId,
                            season = season,
                            episode = episode,
                            groups = updated,
                            isComplete = !anyLoading,
                        )
                    }
                    current.copy(
                        groups = updated,
                        isAnyLoading = anyLoading,
                        emptyStateReason = updated.toEmptyStateReason(anyLoading),
                    ).also { nextState = it }
                }
                nextState?.let { state ->
                    log.d {
                        "state $panelName request=$requestKey stage=publish addon=${group.addonName} " +
                            "streams=${group.streams.size} loading=${group.isLoading} " +
                            "error=${!group.error.isNullOrBlank()} ${state.streamDiagnostics()}"
                    }
                }
            }

            fun publishStreamGroupAfterCacheCheck(group: AddonStreamGroup) {
                if (group.addonId !in installedAddonIds || group.streams.isEmpty()) {
                    publishStreamGroup(presentStreamGroup(group))
                    return
                }

                val eligibleGroupIds = setOf(group.addonId)
                val shouldWaitForCacheCheck = LocalDebridAvailabilityService.hasPendingCacheCheck(
                    groups = listOf(group),
                    eligibleGroupIds = eligibleGroupIds,
                )
                if (!shouldWaitForCacheCheck) {
                    publishStreamGroup(presentStreamGroup(group))
                    return
                }

                val checkingGroup = LocalDebridAvailabilityService.markChecking(
                    groups = listOf(group),
                    eligibleGroupIds = eligibleGroupIds,
                ).firstOrNull() ?: group

                val availabilityJob = launch {
                    val availabilityGroup = LocalDebridAvailabilityService.annotateCachedAvailability(
                        groups = listOf(checkingGroup),
                        eligibleGroupIds = eligibleGroupIds,
                    ).firstOrNull() ?: checkingGroup
                    publishStreamGroup(presentStreamGroup(availabilityGroup))
                }
                debridAvailabilityJobs += availabilityJob
            }

            streamAddons.forEach { addon ->
                launch {
                    val url = buildAddonResourceUrl(
                        manifestUrl = addon.manifest.transportUrl,
                        resource = "stream",
                        type = type,
                        id = videoId,
                    )

                    val displayName = addon.addonName
                    val group = runCatchingUnlessCancelled {
                        log.d { "fetch $panelName request=$requestKey addon=$displayName" }
                        val payload = fetchAddonResponseText(
                            url = url,
                            forceRefresh = forceRefresh,
                        )
                        val parsed = StreamParser.parse(
                            payload = payload,
                            addonName = displayName,
                            addonId = addon.addonId,
                            addonLogo = addon.manifest.logoUrl,
                        )
                        parsed
                    }.fold(
                        onSuccess = { streams ->
                            log.d { "fetched $panelName request=$requestKey addon=$displayName streams=${streams.size}" }
                            AddonStreamGroup(displayName, addon.addonId, streams, isLoading = false)
                        },
                        onFailure = { err ->
                            log.w(err) { "failed $panelName request=$requestKey addon=$displayName" }
                            AddonStreamGroup(displayName, addon.addonId, emptyList(), isLoading = false, error = err.message)
                        },
                    )
                    publishCompletion(StreamLoadCompletion.Addon(group))
                }
            }

            val resolvedParentId = videoId.substringBefore(':').takeIf { it.isNotBlank() } ?: videoId
            val meta = MetaDetailsRepository.getActiveMeta(resolvedParentId)
                ?: MetaDetailsRepository.getActiveMeta(videoId)
            val cleanTitle = meta?.name ?: videoId.substringBefore(':')
            val mediaLookupId = meta?.imdbId ?: when {
                videoId.startsWith("tt") -> videoId.substringBefore(":")
                resolvedParentId.startsWith("tt") -> resolvedParentId
                else -> null
            }
            val metaYear = meta?.releaseInfo?.take(4)
            val isAnime = type.equals("anime", ignoreCase = true) ||
                videoId.startsWith("kitsu:") || videoId.startsWith("mal:") || videoId.startsWith("anilist:") ||
                resolvedParentId.startsWith("kitsu:") || resolvedParentId.startsWith("mal:") || resolvedParentId.startsWith("anilist:")

            if (isAnime) {
                launch {
                    OfflineAnimeProviders.fetchAllStreams(
                        title = cleanTitle,
                        mediaLookupId = mediaLookupId,
                        type = type,
                        year = metaYear,
                        season = season,
                        episode = episode,
                        onGroupLoaded = { group ->
                            publishStreamGroup(presentStreamGroup(group))
                        }
                    )
                }
            }

            val pluginSemaphore = Semaphore(permits = 20)
            pluginProviderGroups.forEach { providerGroup ->
                val includeScraperNameInSubtitle = false
                providerGroup.scrapers.forEach { scraper ->
                    launch {
                        log.d { "fetch $panelName request=$requestKey plugin=${scraper.name}" }
                        val completion = try {
                            pluginSemaphore.withPermit {
                                withTimeoutOrNull(25_000L) {
                                    val targetContentId = pluginContentId(
                                        videoId = videoId,
                                        season = season,
                                        episode = episode,
                                    )
                                    PluginRepository.executeScraper(
                                        scraper = scraper,
                                        tmdbId = targetContentId,
                                        mediaType = type,
                                        season = season,
                                        episode = episode,
                                    ).fold(
                                        onSuccess = { results ->
                                            log.d { "fetched $panelName request=$requestKey plugin=${scraper.name} streams=${results.size}" }
                                            StreamLoadCompletion.PluginScraper(
                                                addonId = providerGroup.addonId,
                                                streams = results.map { result ->
                                                    result.toStreamItem(
                                                        scraper = scraper,
                                                        addonName = providerGroup.addonName,
                                                        addonId = providerGroup.addonId,
                                                        includeScraperNameInSubtitle = includeScraperNameInSubtitle,
                                                    )
                                                },
                                                error = null,
                                            )
                                        },
                                        onFailure = { error ->
                                            log.w(error) { "failed $panelName request=$requestKey plugin=${scraper.name}" }
                                            StreamLoadCompletion.PluginScraper(
                                                addonId = providerGroup.addonId,
                                                streams = emptyList(),
                                                error = error.message ?: getString(Res.string.streams_failed_to_load_scraper, scraper.name),
                                            )
                                        },
                                    )
                                } ?: StreamLoadCompletion.PluginScraper(
                                    addonId = providerGroup.addonId,
                                    streams = emptyList(),
                                    error = "Scraper timed out",
                                )
                            }
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            StreamLoadCompletion.PluginScraper(
                                addonId = providerGroup.addonId,
                                streams = emptyList(),
                                error = t.message ?: "Scraper failed",
                            )
                        }
                        publishCompletion(completion)
                    }
                }
            }

            repeat(totalTasks) {
                when (val completion = completions.receive()) {
                    is StreamLoadCompletion.Addon -> {
                        publishStreamGroupAfterCacheCheck(completion.group)
                    }

                    is StreamLoadCompletion.PluginScraper -> {
                        val remaining = (pluginRemainingByAddonId[completion.addonId] ?: 1) - 1
                        pluginRemainingByAddonId[completion.addonId] = remaining.coerceAtLeast(0)
                        if (!completion.error.isNullOrBlank() && pluginFirstErrorByAddonId[completion.addonId].isNullOrBlank()) {
                            pluginFirstErrorByAddonId[completion.addonId] = completion.error
                        }

                        stateFlow.update { current ->
                            val updated = StreamAutoPlaySelector.orderAddonStreams(
                                groups = current.groups.map { group ->
                                    if (group.addonId != completion.addonId) {
                                        group
                                    } else {
                                        val mergedStreams = if (completion.streams.isEmpty()) {
                                            group.streams
                                        } else {
                                            (group.streams + completion.streams).sortedForGroupedDisplay()
                                        }
                                        val stillLoading = remaining > 0
                                        val finalError = if (mergedStreams.isEmpty() && !stillLoading) {
                                            pluginFirstErrorByAddonId[completion.addonId]
                                        } else {
                                            null
                                        }
                                        presentStreamGroup(
                                            group.copy(
                                                streams = mergedStreams,
                                                isLoading = stillLoading,
                                                error = finalError,
                                            ),
                                        )
                                    }
                                },
                                installedOrder = installedAddonOrder,
                            )
                            val anyLoading = updated.any { it.isLoading }
                            if (!anyLoading || updated.any { it.streams.isNotEmpty() }) {
                                StreamSessionCache.put(
                                    type = type,
                                    videoId = videoId,
                                    season = season,
                                    episode = episode,
                                    groups = updated,
                                    isComplete = !anyLoading,
                                )
                            }
                            current.copy(
                                groups = updated,
                                isAnyLoading = anyLoading,
                                emptyStateReason = updated.toEmptyStateReason(anyLoading),
                            )
                        }
                    }
                }
            }

            for (availabilityJob in debridAvailabilityJobs) {
                availabilityJob.join()
            }
            log.d { "complete $panelName request=$requestKey ${stateFlow.value.streamDiagnostics()}" }
            launch {
                DirectDebridStreamPreparer.prepare(
                    streams = stateFlow.value.groups
                        .filter { it.addonId in installedAddonIds }
                        .flatMap { it.streams },
                    season = season,
                    episode = episode,
                    playerSettings = playerSettings,
                    installedAddonNames = installedAddonNames,
                ) { original, prepared ->
                    stateFlow.update { current ->
                        current.copy(
                            groups = DirectDebridStreamPreparer.replacePreparedStream(
                                groups = current.groups,
                                original = original,
                                prepared = prepared,
                                eligibleGroupIds = installedAddonIds,
                            ),
                        )
                    }
                }
            }
            completions.close()
        }
        setJob(job)
    }
}

private fun StreamsUiState.streamDiagnostics(): String {
    val streamCount = groups.sumOf { it.streams.size }
    val loadingCount = groups.count { it.isLoading }
    val errorCount = groups.count { !it.error.isNullOrBlank() }
    val sampleGroups = groups.take(4).joinToString(prefix = "[", postfix = "]") { group ->
        buildString {
            append(group.addonName)
            append(':')
            append(group.streams.size)
            if (group.isLoading) append(":loading")
            if (!group.error.isNullOrBlank()) append(":error")
        }
    }
    val suffix = if (groups.size > 4) "+${groups.size - 4}" else ""
    return "groups=${groups.size} streams=$streamCount isAnyLoading=$isAnyLoading " +
        "loadingGroups=$loadingCount errorGroups=$errorCount empty=${emptyStateReason ?: "none"} " +
        "sample=$sampleGroups$suffix"
}

private fun com.nuvio.app.features.addons.ManagedAddon.streamAddonInstanceId(manifestId: String): String =
    "addon:$manifestId:$manifestUrl"
