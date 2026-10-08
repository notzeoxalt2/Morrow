package com.nuvio.app.features.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MorrowProviderMigrationTest {
    @Test fun cloudSyncCannotRestoreLegacyRepositoriesOrDropCurrentDefaults() {
        val urls = MorrowProviderMigration.repositoryUrls(listOf(
            "https://raw.githubusercontent.com/notzeoxalt2/morrowx2anime/refs/heads/main/manifest.json?cache=1",
            "https://raw.githubusercontent.com/Yoruix/nuvio-providers/main/manifest.json",
        ))
        assertEquals(MorrowProviderMigration.defaults, urls)
    }
    @Test fun currentAliasesDeduplicateAndCustomRepositorySurvives() {
        val custom = "https://example.com/custom/manifest.json?key=abc"
        val urls = MorrowProviderMigration.repositoryUrls(listOf(
            "https://raw.githubusercontent.com/notzeoxalt2/morrowx1anime/refs/heads/main/manifest.json",
            MorrowProviderMigration.defaults[1], custom,
        ))
        assertEquals(3, urls.size)
        assertTrue(custom in urls)
    }
    @Test fun retiredCopiesAreRemovedButGenuineAndCustomProvidersRemain() {
        assertTrue(MorrowProviderMigration.isRetiredScraper(MorrowProviderMigration.defaults[1], "repo:hianime"))
        assertFalse(MorrowProviderMigration.isRetiredScraper(MorrowProviderMigration.defaults[1], "repo:miruro"))
        assertFalse(MorrowProviderMigration.isRetiredScraper("https://example.com/manifest.json", "repo:hianime"))
    }
}

