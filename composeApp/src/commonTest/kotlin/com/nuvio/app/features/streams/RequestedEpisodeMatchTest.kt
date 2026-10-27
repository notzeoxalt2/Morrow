package com.nuvio.app.features.streams

import com.nuvio.app.features.plugins.PluginRuntimeResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestedEpisodeMatchTest {
    @Test fun rejectsExplicitlyDifferentSeasonOrEpisode() {
        val result = PluginRuntimeResult(
            title = "Attack on Titan (2013) S2E12",
            url = "https://example.invalid/video.m3u8",
        )

        assertFalse(result.matchesRequestedEpisode(season = 2, episode = 1))
        assertTrue(result.matchesRequestedEpisode(season = 2, episode = 12))
    }

    @Test fun acceptsMatchingAndUnnumberedResults() {
        val matching = PluginRuntimeResult(title = "Attack on Titan S02.E01", url = "https://example.invalid/matching.m3u8")
        val unnumbered = PluginRuntimeResult(title = "Attack on Titan - 1080p", url = "https://example.invalid/unnumbered.m3u8")

        assertTrue(matching.matchesRequestedEpisode(season = 2, episode = 1))
        assertTrue(unnumbered.matchesRequestedEpisode(season = 2, episode = 1))
    }
}
