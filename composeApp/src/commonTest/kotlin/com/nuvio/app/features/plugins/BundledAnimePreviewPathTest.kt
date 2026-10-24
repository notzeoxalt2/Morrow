package com.nuvio.app.features.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BundledAnimePreviewPathTest {
    private val root = "https://raw.githubusercontent.com/notzeoxalt2/morrowx1anime/main/"

    @Test fun acceptsOnlyOwnedManifestAndProviderScripts() {
        assertEquals("manifest.json", bundledAnimePreviewPath("${root}manifest.json?cache=1"))
        assertEquals("providers/meguanimes.js", bundledAnimePreviewPath("${root}providers/meguanimes.js#v"))
        assertNull(bundledAnimePreviewPath("https://raw.githubusercontent.com/other/repo/main/manifest.json"))
        assertNull(bundledAnimePreviewPath("${root}README.md"))
        assertNull(bundledAnimePreviewPath("${root}providers/../../secrets.js"))
    }
}
