package com.nuvio.app.features.plugins

import kotlin.test.Test
import kotlin.test.assertEquals

class PluginEpisodeArgumentTest {
    private fun scraper(
        code: String,
        type: String = "anime",
        numbering: String? = null,
        id: String = "provider",
    ) = PluginScraper(
        id = id,
        repositoryUrl = "https://example.com/manifest.json",
        name = "Provider",
        description = "",
        version = "1",
        filename = "provider.js",
        supportedTypes = listOf(type),
        enabled = true,
        manifestEnabled = true,
        animeEpisodeNumbering = numbering,
        code = code,
    )

    @Test fun legacyAnimeProviderDefaultsToSeasonLocalEpisode() {
        assertEquals(18, scraper("function getStreams(id, type, season, episode) {}")
            .episodeArgument(18, 117, isAnime = true, season = 2))
    }

    @Test fun flatCatalogReceivesAbsoluteEpisodeOnlyWhenDeclared() {
        assertEquals(117, scraper("function getStreams(id, type, season, episode) {}", numbering = "absolute")
            .episodeArgument(18, 117, isAnime = true, season = 2))
    }

    @Test fun knownFlatProviderUsesAbsoluteEpisodeWhenCachedManifestIsOld() {
        val knownFlatProvider = scraper("function getStreams(id, type, season, episode) {}", id = "cached-repo:miruro")
        assertEquals("absolute", knownFlatProvider.animeEpisodeNumberingMode())
        assertEquals(117, knownFlatProvider.episodeArgument(1, 117, isAnime = true, season = 2))
    }

    @Test fun explicitSeasonLocalManifestOverridesKnownProviderFallback() {
        assertEquals(1, scraper("function getStreams(id, type, season, episode) {}", numbering = "season-local", id = "miruro")
            .episodeArgument(1, 117, isAnime = true, season = 2))
    }

    @Test fun flatCatalogDoesNotGuessAbsoluteNumberForLaterSeason() {
        val flatProvider = scraper("function getStreams(id, type, season, episode) {}", numbering = "absolute")
        assertEquals(null, flatProvider.episodeArgument(12, null, isAnime = true, season = 2))
        assertEquals(true, flatProvider.requiresVerifiedAbsoluteAnimeEpisode(season = 2, absoluteAnimeEpisode = null, isAnime = true))
        assertEquals(false, flatProvider.requiresVerifiedAbsoluteAnimeEpisode(season = 2, absoluteAnimeEpisode = 117, isAnime = true))
    }

    @Test fun flatCatalogCanUseSeasonOneEpisodeWhenAbsoluteMappingIsMissing() {
        val flatProvider = scraper("function getStreams(id, type, season, episode) {}", numbering = "absolute")
        assertEquals(12, flatProvider.episodeArgument(12, null, isAnime = true, season = 1))
        assertEquals(false, flatProvider.requiresVerifiedAbsoluteAnimeEpisode(season = 1, absoluteAnimeEpisode = null, isAnime = true))
    }

    @Test fun seasonAwareAnimeProviderKeepsSeasonLocalEpisode() {
        assertEquals(18, scraper("const context = MORROW_MEDIA_CONTEXT.animeEpisode;")
            .episodeArgument(18, 117, isAnime = true, season = 2))
    }

    @Test fun nonAnimeProviderKeepsRequestedEpisode() {
        assertEquals(18, scraper("function getStreams(id, type, season, episode) {}", "tv")
            .episodeArgument(18, 117, isAnime = true))
    }

    @Test fun multiServerProvidersKeepTheirExtendedLookupWindow() {
        assertEquals(PLUGIN_EXTENDED_EXECUTION_TIMEOUT_MS, pluginExecutionTimeoutMs("https://repo.example/manifest.json:animesalt"))
        assertEquals(PLUGIN_EXTENDED_EXECUTION_TIMEOUT_MS, pluginExecutionTimeoutMs("https://repo.example/manifest.json:aniflix-server-2"))
        assertEquals(PLUGIN_DEFAULT_EXECUTION_TIMEOUT_MS, pluginExecutionTimeoutMs("https://repo.example/manifest.json:hianime-site"))
    }
}
