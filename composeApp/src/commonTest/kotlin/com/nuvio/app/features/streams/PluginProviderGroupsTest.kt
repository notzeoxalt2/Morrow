package com.nuvio.app.features.streams

import com.nuvio.app.features.plugins.PluginRepositoryItem
import com.nuvio.app.features.plugins.PluginScraper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PluginProviderGroupsTest {
    private fun scraper(repository: String) = PluginScraper(
        id = "shared-provider-id",
        repositoryUrl = repository,
        name = "AnimeSalt",
        description = "",
        version = "1",
        filename = "animesalt.js",
        supportedTypes = listOf("anime"),
        enabled = true,
        manifestEnabled = true,
        code = "",
    )

    @Test fun sameProviderIdFromDifferentRepositoriesRemainsSeparate() {
        val first = "https://one.example/manifest.json"
        val second = "https://two.example/manifest.json"
        val groups = listOf(scraper(first), scraper(second)).toPluginProviderGroups(
            repositories = listOf(
                PluginRepositoryItem(manifestUrl = first, name = "Anime Pack One"),
                PluginRepositoryItem(manifestUrl = second, name = "Anime Pack Two"),
            ),
            groupByRepository = false,
        )

        assertEquals(2, groups.size)
        assertNotEquals(groups[0].addonId, groups[1].addonId)
        assertNotEquals(groups[0].addonName, groups[1].addonName)
        assertEquals(listOf(1, 1), groups.map { it.scrapers.size })
    }

    @Test fun differentProvidersWithSameDisplayNameKeepSeparateResults() {
        val first = AddonStreamGroup("AnimeSalt", "plugin:one:animesalt", emptyList(), isLoading = true)
        val second = AddonStreamGroup("AnimeSalt", "plugin:two:animesalt", emptyList(), isLoading = true)

        val groups = listOf(first).upsertStreamGroupByProviderId(second)

        assertEquals(2, groups.size)
        assertEquals(setOf(first.addonId, second.addonId), groups.map { it.addonId }.toSet())
    }

    @Test fun animeCategoryUsesAdvertisedCapabilitiesInsteadOfCurrentTitleType() {
        val movieAndTv = AddonStreamGroup(
            "Movie scraper",
            "movie-scraper",
            emptyList(),
            sourceCategories = sourceCategories(listOf("movie", "tv")),
        )
        val animeAndTv = AddonStreamGroup(
            "Anime scraper",
            "anime-scraper",
            emptyList(),
            sourceCategories = sourceCategories(listOf("anime", "tv")),
        )

        assertFalse(movieAndTv.matchesSourceCategory("anime"))
        assertTrue(movieAndTv.matchesSourceCategory("movies-series"))
        assertTrue(animeAndTv.matchesSourceCategory("anime"))
    }

    @Test fun knownAnimeAdaptersRemainInAnimeCategoryWhenLegacyManifestOmitsAnimeType() {
        val legacyAnimeAdapter = scraper("https://anime-pack.example/manifest.json").copy(
            supportedTypes = listOf("movie", "tv"),
        )
        val movieAdapter = legacyAnimeAdapter.copy(
            repositoryUrl = "https://movies.example/manifest.json",
            name = "4KHDHub",
            id = "4khdhub",
        )

        assertTrue(legacyAnimeAdapter.sourceCategories().contains("anime"))
        assertFalse(movieAdapter.sourceCategories().contains("anime"))
    }
}
