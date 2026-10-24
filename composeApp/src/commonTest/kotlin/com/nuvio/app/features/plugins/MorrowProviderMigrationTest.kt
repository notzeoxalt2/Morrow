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
    @Test fun animeScrapersAndOldRepositoriesAreNeverSilentlyRetired() {
        assertFalse(MorrowProviderMigration.isRetiredScraper(MorrowProviderMigration.defaults[1], "repo:hianime"))
        assertFalse(MorrowProviderMigration.isRetiredScraper("https://example.com/manifest.json", "repo:hianime"))
        assertFalse(MorrowProviderMigration.isLegacy("https://raw.githubusercontent.com/Yoruix/nuvio-providers/main/manifest.json"))
    }
}

