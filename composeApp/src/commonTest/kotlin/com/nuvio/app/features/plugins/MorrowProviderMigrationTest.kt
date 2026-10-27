package com.nuvio.app.features.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MorrowProviderMigrationTest {
    @Test fun cloudSyncKeepsInstalledRepositoriesAndAddsCurrentDefaults() {
        val legacy = "https://raw.githubusercontent.com/Yoruix/nuvio-providers/main/manifest.json"
        val urls = MorrowProviderMigration.repositoryUrls(listOf(
            "https://raw.githubusercontent.com/notzeoxalt2/morrowx2anime/refs/heads/main/manifest.json?cache=1",
            legacy,
        ))
        assertEquals(MorrowProviderMigration.defaults.size + 1, urls.size)
        assertFalse("https://raw.githubusercontent.com/notzeoxalt2/morrowx2anime/main/manifest.json" in urls)
        assertTrue(MorrowProviderMigration.isLegacy("https://raw.githubusercontent.com/notzeoxalt2/morrowx2anime/main/manifest.json"))
        assertTrue(legacy in urls)
        assertTrue(MorrowProviderMigration.defaults.all { it in urls })
    }
    @Test fun currentAliasesDeduplicateAndCustomRepositorySurvives() {
        val custom = "https://example.com/custom/manifest.json?key=abc"
        val urls = MorrowProviderMigration.repositoryUrls(listOf(
            "https://raw.githubusercontent.com/notzeoxalt2/morrowx1anime/refs/heads/main/manifest.json",
            MorrowProviderMigration.defaults[1], custom,
        ))
        assertEquals(MorrowProviderMigration.defaults.size + 1, urls.size)
        assertTrue(custom in urls)
    }
    @Test fun duplicateAndMislabelledAliasesAreRetired() {
        assertTrue(MorrowProviderMigration.isRetiredScraper(MorrowProviderMigration.defaults[1], "repo:hianime"))
        assertFalse(MorrowProviderMigration.isRetiredScraper(MorrowProviderMigration.defaults[1], "repo:hianime-site"))
        assertTrue(MorrowProviderMigration.isRetiredScraper(MorrowProviderMigration.defaults[1], "repo:anime-nexus"))
        assertTrue(MorrowProviderMigration.isRetiredScraper(MorrowProviderMigration.defaults[1], "repo:enma"))
        assertTrue(MorrowProviderMigration.isRetiredScraper(MorrowProviderMigration.defaults[1], "repo:animetvplus"))
        assertTrue(MorrowProviderMigration.isRetiredScraper(MorrowProviderMigration.defaults[1], "repo:kaa"))
        val retiredX2 = "https://raw.githubusercontent.com/notzeoxalt2/morrowx2anime/main/manifest.json"
        assertTrue(MorrowProviderMigration.isRetiredScraper(retiredX2, "repo:animedekho"))
        assertTrue(MorrowProviderMigration.isRetiredScraper(retiredX2, "repo:anime-nexus"))
        assertTrue(MorrowProviderMigration.isRetiredScraper(retiredX2, "repo:lunarx"))
        assertTrue(MorrowProviderMigration.isRetiredScraper(retiredX2, "repo:saltanime"))
        assertFalse(MorrowProviderMigration.isRetiredScraper(MorrowProviderMigration.defaults[1], "repo:saltanime"))
        assertFalse(MorrowProviderMigration.isRetiredScraper("https://example.com/manifest.json", "repo:hianime"))
        assertTrue(MorrowProviderMigration.isLegacy(retiredX2))
        assertFalse(MorrowProviderMigration.isLegacy("https://raw.githubusercontent.com/Yoruix/nuvio-providers/main/manifest.json"))
    }

    @Test fun retiredAnime2RepositoryIsRemovedFromSyncedAndDefaultCatalogs() {
        val retiredX2 = "https://raw.githubusercontent.com/notzeoxalt2/morrowx2anime/refs/heads/main/manifest.json?cache=1"
        val urls = MorrowProviderMigration.repositoryUrls(listOf(retiredX2, "https://example.com/custom/manifest.json"))
        assertEquals(MorrowProviderMigration.defaults.size + 1, urls.size)
        assertFalse(urls.any(MorrowProviderMigration::isLegacy))
        assertTrue("https://example.com/custom/manifest.json" in urls)
    }
}

