package com.nuvio.app.features.plugins

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginScraperTypeSupportTest {
    private fun scraper(
        name: String,
        id: String,
        repositoryUrl: String,
        supportedTypes: List<String>,
    ) = PluginScraper(
        id = id,
        repositoryUrl = repositoryUrl,
        name = name,
        description = "test",
        version = "1",
        filename = "$id.js",
        supportedTypes = supportedTypes,
        enabled = true,
        manifestEnabled = true,
        code = "",
    )

    @Test
    fun movieAndTvScrapersDoNotAutomaticallyRunForAnime() {
        val movieProvider = scraper(
            name = "DahmerMovies",
            id = "dahmermovies",
            repositoryUrl = "https://example.com/movies/manifest.json",
            supportedTypes = listOf("movie", "tv"),
        )

        assertFalse(movieProvider.supportsType("anime"))
        assertTrue(movieProvider.supportsType("tv"))
        assertTrue(movieProvider.supportsType("movie"))
    }

    @Test
    fun explicitlyDeclaredAnimeCrossoverProviderRunsForAnime() {
        val crossoverProvider = scraper(
            name = "Cinema Anime",
            id = "cinema-anime",
            repositoryUrl = "https://example.com/movies/manifest.json",
            supportedTypes = listOf("movie", "tv", "anime"),
        )

        assertTrue(crossoverProvider.supportsType("anime"))
    }

    @Test
    fun animeSpecificProviderIdentityIsRecognizedEvenWithLegacyTvRoute() {
        val legacyAnimeProvider = scraper(
            name = "Miruro",
            id = "miruro",
            repositoryUrl = "https://example.com/manifest.json",
            supportedTypes = listOf("tv"),
        )

        assertTrue(legacyAnimeProvider.supportsType("anime"))
    }
}
