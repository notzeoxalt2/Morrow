package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.debrid.DirectDebridStreamPreparer
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DebridStreamPresentation
import com.nuvio.app.features.debrid.LocalDebridAvailabilityService
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.pluginContentId
import com.nuvio.app.features.plugins.PluginsUiState
import com.nuvio.app.features.providers.offline.OfflineAnimeProviders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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

object StreamsRepository {
    private val log = Logger.withTag("StreamsRepo")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(StreamsUiState())
    val uiState: StateFlow<StreamsUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null
    private var activeRequestKey: String? = null

    fun requestToken(
        type: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
        manualSelection: Boolean = false,
    ): String =
        "$type::$videoId::$season::$episode::$manualSelection"

    fun load(
        type: String,
        videoId: String,
        parentMetaId: String? = null,
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        manualSelection: Boolean = false,
    ) {
        PluginRepository.setLocalPluginSearchPaused(false)
        load(
            type = type,
            videoId = videoId,
            parentMetaId = parentMetaId,
            title = title,
            season = season,
            episode = episode,
            manualSelection = manualSelection,
            forceRefresh = false,
        )
    }

    fun reload(
        type: String,
        videoId: String,
        parentMetaId: String? = null,
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        manualSelection: Boolean = false,
    ) {
        PluginRepository.setLocalPluginSearchPaused(false)
        load(
            type = type,
            videoId = videoId,
            parentMetaId = parentMetaId,
            title = title,
            season = season,
            episode = episode,
            manualSelection = manualSelection,
            forceRefresh = true,
        )
    }

    private fun load(
        type: String,
        videoId: String,
        parentMetaId: String?,
        title: String?,
        season: Int?,
        episode: Int?,
        manualSelection: Boolean,
        forceRefresh: Boolean,
    ) {
        val pluginUiState = if (AppFeaturePolicy.pluginsEnabled) {
            PluginRepository.initialize()
            PluginRepository.uiState.value
        } else {
            PluginsUiState(pluginsEnabled = false)
        }
        val requestToken = requestToken(
            type = type,
            videoId = videoId,
            season = season,
            episode = episode,
            manualSelection = manualSelection,
        )
        val requestKey = "$requestToken::pluginsGrouped=${pluginUiState.groupStreamsByRepository}"
        val currentState = _uiState.value
        if (
            !forceRefresh &&
            activeRequestKey == requestKey &&
            (currentState.groups.isNotEmpty() || currentState.emptyStateReason != null || currentState.isAnyLoading)
        ) {
            log.d { "Skipping stream reload for unchanged request type=$type id=$videoId" }
            return
        }

        activeRequestKey = requestKey
        activeJob?.cancel()
        _uiState.value = StreamsUiState(requestToken = requestToken)

        PlayerSettingsRepository.ensureLoaded()
        val playerSettings = PlayerSettingsRepository.uiState.value
        val debridSettings = DebridSettingsRepository.snapshot()
        val streamBadgeRules = StreamBadgeSettingsRepository.snapshot()
        val autoPlayMode = playerSettings.streamAutoPlayMode
        val isAutoPlayEnabled = !manualSelection && autoPlayMode != StreamAutoPlayMode.MANUAL &&
            !(autoPlayMode == StreamAutoPlayMode.REGEX_MATCH &&
                !StreamAutoPlayPolicy.isRegexSelectionConfigured(playerSettings.streamAutoPlayRegex))

        // Look up persisted binge group when both settings are enabled
        val persistedBingeGroup = if (
            playerSettings.streamAutoPlayPreferBingeGroup &&
            playerSettings.streamAutoPlayReuseBingeGroup
        ) {
            parentMetaId?.let { BingeGroupCacheRepository.get(it) }
        } else null

        // Enable direct auto-play flow if normal auto-play is enabled,
        // OR if we have a persisted binge group in MANUAL mode
        val bingeGroupDirectFlow = !manualSelection &&
            persistedBingeGroup != null &&
            autoPlayMode == StreamAutoPlayMode.MANUAL
        val isDirectAutoPlayFlow = isAutoPlayEnabled || bingeGroupDirectFlow

        if (isDirectAutoPlayFlow) {
            _uiState.value = StreamsUiState(
                requestToken = requestToken,
                isDirectAutoPlayFlow = true,
                autoPlayDecided = true,
                showDirectAutoPlayOverlay = true,
            )
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

        val embeddedStreams = MetaDetailsRepository.findEmbeddedStreams(videoId)
        if (embeddedStreams.isNotEmpty()) {
            log.d { "Using ${embeddedStreams.size} embedded streams for type=$type id=$videoId" }
            val group = AddonStreamGroup(
                addonName = embeddedStreams.first().addonName,
                addonId = "embedded",
                streams = embeddedStreams,
                isLoading = false,
            )
            val presentedGroup = presentStreamGroup(group)
            _uiState.value = StreamsUiState(
                requestToken = requestToken,
                groups = listOf(presentedGroup),
                autoPlayDecided = true,
                activeAddonIds = setOf("embedded"),
                isAnyLoading = false,
            )
            return
        }

        val isCacheComplete = if (!forceRefresh) {
            StreamSessionCache.isComplete(
                type = type,
                videoId = videoId,
                season = season,
                episode = episode,
                parentMetaId = parentMetaId,
            )
        } else false

        val cachedSessionStreams = if (!forceRefresh) {
            StreamSessionCache.get(
                type = type,
                videoId = videoId,
                season = season,
                episode = episode,
                parentMetaId = parentMetaId,
            )
        } else null

        if (isCacheComplete && cachedSessionStreams != null && cachedSessionStreams.isNotEmpty()) {
            log.i { "Instant complete cache hit: ${cachedSessionStreams.size} stream groups for $type $videoId (S$season:E$episode)" }
            val presentedGroups = cachedSessionStreams.map(::presentStreamGroup)
            _uiState.value = StreamsUiState(
                requestToken = requestToken,
                groups = presentedGroups,
                autoPlayDecided = true,
                activeAddonIds = presentedGroups.map { it.addonId }.toSet(),
                isAnyLoading = false,
                isDirectAutoPlayFlow = isDirectAutoPlayFlow,
                showDirectAutoPlayOverlay = isDirectAutoPlayFlow,
            )
            val meta = MetaDetailsRepository.getActiveMeta(parentMetaId ?: videoId)
            EpisodeStreamPrefetcher.prefetchNextEpisodes(
                currentType = type,
                currentVideoId = videoId,
                parentMetaId = parentMetaId,
                currentSeason = season,
                currentEpisode = episode,
                metaVideos = meta?.videos.orEmpty(),
            )
            return
        }

        val installedAddons = AddonRepository.uiState.value.addons.enabledAddons()
        val pluginScrapers = if (AppFeaturePolicy.pluginsEnabled) {
            PluginRepository.getEnabledScrapersForType(type)
        } else {
            emptyList()
        }
        val pluginProviderGroups = pluginScrapers.toPluginProviderGroups(
            repositories = pluginUiState.repositories,
            groupByRepository = pluginUiState.groupStreamsByRepository,
        )

        val meta = MetaDetailsRepository.getActiveMeta(parentMetaId ?: videoId)
        val cleanTitle = title?.takeIf { it.isNotBlank() } ?: meta?.name ?: videoId
        val metaTitle = cleanTitle
        val mediaLookupId = meta?.imdbId ?: when {
            videoId.startsWith("tt") -> videoId.substringBefore(":")
            videoId.startsWith("kitsu:") || videoId.startsWith("mal:") -> videoId
            parentMetaId?.startsWith("tt") == true -> parentMetaId.substringBefore(":")
            parentMetaId?.startsWith("kitsu:") == true || parentMetaId?.startsWith("mal:") == true -> parentMetaId
            else -> null
        }
        val metaYear = meta?.releaseInfo?.take(4)

        val offlineAnimeGroups = listOf(
            AddonStreamGroup(
                addonName = "HiAnime",
                addonId = "offline:hianime",
                streams = emptyList(),
                isLoading = true,
            ),
            AddonStreamGroup(
                addonName = "AnimeLok",
                addonId = "offline:animelok",
                streams = emptyList(),
                isLoading = true,
            ),
            AddonStreamGroup(
                addonName = "Senshi",
                addonId = "offline:senshi",
                streams = emptyList(),
                isLoading = true,
            ),
            AddonStreamGroup(
                addonName = "AniDB",
                addonId = "offline:anidb",
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
                addonName = "AnimeSalt",
                addonId = "offline:animesalt",
                streams = emptyList(),
                isLoading = true,
            ),
        )

        val streamAddons = installedAddons
            .mapNotNull { addon ->
                val manifest = addon.manifest ?: return@mapNotNull null
                if (!manifest.supportsStream(type, videoId)) return@mapNotNull null

                InstalledStreamAddonTarget(
                    addonName = addon.displayTitle.ifBlank { manifest.name },
                    addonId = addon.streamAddonInstanceId(manifest.id),
                    manifest = manifest,
                )
            }

        log.d { "Found ${streamAddons.size} addons for stream type=$type id=$videoId (Offline anime active)" }

        // Initialise loading placeholders
        val installedAddonOrder = streamAddons.map { it.addonName } + offlineAnimeGroups.map { it.addonName }
        val initialGroups = StreamAutoPlaySelector.orderAddonStreams(streamAddons.map { addon ->
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
        } + offlineAnimeGroups, installedAddonOrder)
        val cachedById = cachedSessionStreams?.associateBy { it.addonId }.orEmpty()
        val populatedInitialGroups = initialGroups.map { group ->
            val cached = cachedById[group.addonId]
            if (cached != null && cached.streams.isNotEmpty()) {
                group.copy(streams = cached.streams)
            } else group
        }
        val isInitiallyLoading = populatedInitialGroups.any { it.isLoading }
        _uiState.value = StreamsUiState(
            requestToken = requestToken,
            groups = populatedInitialGroups,
            activeAddonIds = populatedInitialGroups.map { it.addonId }.toSet(),
            isAnyLoading = isInitiallyLoading,
            emptyStateReason = null,
            isDirectAutoPlayFlow = isDirectAutoPlayFlow,
            autoPlayDecided = true,
            showDirectAutoPlayOverlay = isDirectAutoPlayFlow,
        )

        activeJob = scope.launch {
            val completions = Channel<StreamLoadCompletion>(capacity = Channel.BUFFERED)
            val pluginRemainingByAddonId = pluginProviderGroups
                .associate { it.addonId to it.scrapers.size }
                .toMutableMap()
            val pluginFirstErrorByAddonId = mutableMapOf<String, String>()
            val totalTasks = streamAddons.size +
                pluginProviderGroups.sumOf { it.scrapers.size }

            val installedAddonNames = installedAddonOrder.toSet()
            val installedAddonIds = streamAddons.map { it.addonId }.toSet()
            val debridAvailabilityJobs = mutableListOf<Job>()
            var autoSelectTriggered = false
            var timeoutElapsed = false
            fun evaluateAutoPlay(bingeGroupOnly: Boolean = false): StreamAutoPlayEvaluation =
                StreamAutoPlaySelector.evaluateAutoPlayStream(
                    streams = _uiState.value.groups.flatMap { it.streams },
                    mode = autoPlayMode,
                    regexPattern = playerSettings.streamAutoPlayRegex,
                    source = playerSettings.streamAutoPlaySource,
                    installedAddonNames = installedAddonNames,
                    selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
                    selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins,
                    preferredBingeGroup = persistedBingeGroup,
                    preferBingeGroupInSelection = persistedBingeGroup != null,
                    bingeGroupOnly = bingeGroupOnly,
                    debridEnabled = debridSettings.canResolvePlayableLinks,
                    activeResolverProviderId = debridSettings.activeResolverProviderId,
                )

            fun settleAutoPlay(evaluation: StreamAutoPlayEvaluation) {
                if (autoSelectTriggered) return
                autoSelectTriggered = true
                _uiState.update { current ->
                    if (evaluation.stream == null) {
                        current.copy(
                            autoPlayStream = null,
                            autoPlayCandidates = emptyList(),
                            isDirectAutoPlayFlow = false,
                            showDirectAutoPlayOverlay = false,
                        )
                    } else {
                        current.copy(
                            autoPlayStream = evaluation.stream,
                            autoPlayCandidates = evaluation.readyStreams,
                        )
                    }
                }
            }

            fun updateAutoPlayAfterStreamsChanged() {
                if (!isDirectAutoPlayFlow || autoSelectTriggered) return

                val earlyEvaluation = when {
                    timeoutElapsed -> evaluateAutoPlay()
                    persistedBingeGroup != null -> evaluateAutoPlay(bingeGroupOnly = true)
                    else -> null
                }
                if (earlyEvaluation?.stream != null) {
                    settleAutoPlay(earlyEvaluation)
                    return
                }

                if (
                    _uiState.value.groups.areAutoPlaySourcesLoaded(
                        source = playerSettings.streamAutoPlaySource,
                        installedAddonIds = installedAddonIds,
                    )
                ) {
                    settleAutoPlay(evaluateAutoPlay())
                }
            }

            fun publishCompletion(completion: StreamLoadCompletion) {
                if (completions.trySend(completion).isFailure) {
                    log.d { "Ignoring late stream load completion after channel close" }
                }
            }

            fun publishAddonGroup(group: AddonStreamGroup) {
                _uiState.update { current ->
                    val updated = StreamAutoPlaySelector.orderAddonStreams(
                        groups = current.groups.map { currentGroup ->
                            if (currentGroup.addonId == group.addonId) group else currentGroup
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
                            parentMetaId = parentMetaId,
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
                updateAutoPlayAfterStreamsChanged()
            }

            fun publishAddonGroupAfterCacheCheck(group: AddonStreamGroup) {
                if (group.addonId !in installedAddonIds || group.streams.isEmpty()) {
                    publishAddonGroup(presentStreamGroup(group))
                    return
                }

                val eligibleGroupIds = setOf(group.addonId)
                val shouldWaitForCacheCheck = LocalDebridAvailabilityService.hasPendingCacheCheck(
                    groups = listOf(group),
                    eligibleGroupIds = eligibleGroupIds,
                )
                if (!shouldWaitForCacheCheck) {
                    publishAddonGroup(presentStreamGroup(group))
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
                    publishAddonGroup(presentStreamGroup(availabilityGroup))
                }
                debridAvailabilityJobs += availabilityJob
            }

            updateAutoPlayAfterStreamsChanged()

            val timeoutJob = if (isDirectAutoPlayFlow) {
                val timeoutSeconds = playerSettings.streamAutoPlayTimeoutSeconds
                val isUnlimitedTimeout = timeoutSeconds == Int.MAX_VALUE
                if (timeoutSeconds <= 0 || isUnlimitedTimeout) {
                    timeoutElapsed = true
                    if (isUnlimitedTimeout) {
                        launch {
                            delay(60_000L)
                            if (!autoSelectTriggered) {
                                settleAutoPlay(evaluateAutoPlay())
                            }
                        }
                    } else {
                        null
                    }
                } else {
                    launch {
                        delay(timeoutSeconds * 1_000L)
                        timeoutElapsed = true
                        if (!autoSelectTriggered) {
                            val allStreams = _uiState.value.groups.flatMap { it.streams }
                            if (allStreams.isNotEmpty()) {
                                val evaluation = evaluateAutoPlay()
                                if (evaluation.stream != null || !evaluation.hasPendingDebridCandidate) {
                                    settleAutoPlay(evaluation)
                                }
                            }
                        }
                    }
                }
            } else {
                null
            }

            launch {
                OfflineAnimeProviders.fetchAllStreams(
                    title = metaTitle,
                    mediaLookupId = mediaLookupId,
                    type = type,
                    year = metaYear,
                    season = season,
                    episode = episode,
                    onGroupLoaded = { group ->
                        val nonTorrentStreams = group.streams.filterNot { it.isTorrentStream || !it.infoHash.isNullOrBlank() }
                        publishAddonGroup(presentStreamGroup(group.copy(streams = nonTorrentStreams)))
                    }
                )
                _uiState.update { current ->
                    current.copy(
                        groups = current.groups.map { g ->
                            if (g.addonId.startsWith("offline:") && g.isLoading) {
                                g.copy(isLoading = false)
                            } else g
                        }
                    )
                }
                updateAutoPlayAfterStreamsChanged()
            }

            streamAddons.forEach { addon ->
                launch {
                    val displayName = addon.addonName
                    val group = try {
                        val url = buildAddonResourceUrl(
                            manifestUrl = addon.manifest.transportUrl,
                            resource = "stream",
                            type = type,
                            id = videoId,
                        )
                        log.d { "Fetching streams from: $url" }
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
                        val nonTorrentStreams = parsed.filterNot { it.isTorrentStream || !it.infoHash.isNullOrBlank() }
                        AddonStreamGroup(
                            addonName = displayName,
                            addonId = addon.addonId,
                            streams = nonTorrentStreams,
                            isLoading = false,
                        )
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        log.w(t) { "Failed to fetch streams from $displayName" }
                        AddonStreamGroup(
                            addonName = displayName,
                            addonId = addon.addonId,
                            streams = emptyList(),
                            isLoading = false,
                            error = t.message,
                        )
                    }
                    publishCompletion(StreamLoadCompletion.Addon(group))
                }
            }

            val pluginSemaphore = Semaphore(permits = 20)
            pluginProviderGroups.forEach { providerGroup ->
                val includeScraperNameInSubtitle = false
                providerGroup.scrapers.forEach { scraper ->
                    launch {
                        val completion = try {
                            pluginSemaphore.withPermit {
                                withTimeoutOrNull(25_000L) {
                                    PluginRepository.executeScraper(
                                        scraper = scraper,
                                        tmdbId = pluginContentId(
                                            videoId = videoId,
                                            season = season,
                                            episode = episode,
                                        ),
                                        mediaType = type,
                                        season = season,
                                        episode = episode,
                                    ).fold(
                                        onSuccess = { results ->
                                            val nonTorrentResults = results.filterNot { it.infoHash != null }
                                            StreamLoadCompletion.PluginScraper(
                                                addonId = providerGroup.addonId,
                                                streams = nonTorrentResults.map { result ->
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
                        val result = completion.group
                        publishAddonGroupAfterCacheCheck(result)
                    }

                    is StreamLoadCompletion.PluginScraper -> {
                        val remaining = (pluginRemainingByAddonId[completion.addonId] ?: 1) - 1
                        pluginRemainingByAddonId[completion.addonId] = remaining.coerceAtLeast(0)
                        if (!completion.error.isNullOrBlank() && pluginFirstErrorByAddonId[completion.addonId].isNullOrBlank()) {
                            pluginFirstErrorByAddonId[completion.addonId] = completion.error
                        }

                        _uiState.update { current ->
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
                                        group.copy(
                                            streams = mergedStreams,
                                            isLoading = stillLoading,
                                            error = finalError,
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
                                    parentMetaId = parentMetaId,
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
                        updateAutoPlayAfterStreamsChanged()
                    }

                }
            }

            for (availabilityJob in debridAvailabilityJobs) {
                availabilityJob.join()
            }

            launch {
                DirectDebridStreamPreparer.prepare(
                    streams = _uiState.value.groups
                        .filter { it.addonId in installedAddonIds }
                        .flatMap { it.streams },
                    season = season,
                    episode = episode,
                    playerSettings = playerSettings,
                    installedAddonNames = installedAddonNames,
                ) { original, prepared ->
                    _uiState.update { current ->
                        current.copy(
                            groups = DirectDebridStreamPreparer.replacePreparedStream(
                                groups = current.groups,
                                original = original,
                                prepared = prepared,
                                eligibleGroupIds = installedAddonIds,
                            ),
                        )
                    }
                    updateAutoPlayAfterStreamsChanged()
                }
            }

            if (isDirectAutoPlayFlow && !autoSelectTriggered) {
                settleAutoPlay(evaluateAutoPlay())
            }
            timeoutJob?.cancel()

            val meta = MetaDetailsRepository.getActiveMeta(parentMetaId ?: videoId)
            EpisodeStreamPrefetcher.prefetchNextEpisodes(
                currentType = type,
                currentVideoId = videoId,
                parentMetaId = parentMetaId,
                currentSeason = season,
                currentEpisode = episode,
                metaVideos = meta?.videos.orEmpty(),
            )
        }
    }

    fun selectFilter(addonId: String?) {
        _uiState.update { it.copy(selectedFilter = addonId) }
    }

    fun consumeAutoPlay() {
        activeRequestKey = null
        _uiState.update {
            it.copy(
                autoPlayStream = null,
                autoPlayCandidates = emptyList(),
                isDirectAutoPlayFlow = false,
                showDirectAutoPlayOverlay = false,
            )
        }
    }

    fun skipAutoPlayStream(stream: StreamItem): Boolean {
        var hasNext = false
        _uiState.update { current ->
            val failedIndex = current.autoPlayCandidates.indexOf(stream)
            val remaining = if (failedIndex >= 0) {
                current.autoPlayCandidates.drop(failedIndex + 1)
            } else {
                current.autoPlayCandidates.drop(1)
            }
            hasNext = remaining.isNotEmpty()
            current.copy(
                autoPlayStream = remaining.firstOrNull(),
                autoPlayCandidates = remaining,
                isDirectAutoPlayFlow = remaining.isNotEmpty(),
                showDirectAutoPlayOverlay = remaining.isNotEmpty(),
                overlayMessage = null,
            )
        }
        return hasNext
    }

    fun cancelLoading() {
        PluginRepository.setLocalPluginSearchPaused(true)
        activeJob?.cancel()
        activeJob = null
        _uiState.update { current ->
            if (!current.isAnyLoading && current.groups.none { it.isLoading }) {
                current
            } else {
                val updatedGroups = current.groups.map { group ->
                    if (group.isLoading) group.copy(isLoading = false) else group
                }
                current.copy(
                    groups = updatedGroups,
                    isAnyLoading = false,
                    emptyStateReason = if (updatedGroups.isEmpty()) {
                        current.emptyStateReason
                    } else {
                        updatedGroups.toEmptyStateReason(anyLoading = false)
                    },
                )
            }
        }
    }

    fun clear() {
        PluginRepository.setLocalPluginSearchPaused(true)
        activeJob?.cancel()
        activeJob = null
        activeRequestKey = null
        _uiState.value = StreamsUiState()
    }

    fun setOverlayVisible(visible: Boolean, message: String? = null) {
        _uiState.update { it.copy(showDirectAutoPlayOverlay = visible, overlayMessage = message) }
    }
}
