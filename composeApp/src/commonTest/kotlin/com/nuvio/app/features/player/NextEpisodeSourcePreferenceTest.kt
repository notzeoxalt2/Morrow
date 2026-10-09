package com.nuvio.app.features.player
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamBehaviorHints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NextEpisodeSourcePreferenceTest {
    private fun stream(label: String, provider: String = "miruro", group: String = "shared") = StreamItem(
        name = label, addonName = provider, addonId = provider, url = "https://example.com/next.m3u8",
        behaviorHints = StreamBehaviorHints(bingeGroup = group),
    )
    @Test fun keepsProviderServerAndAudioEvenWhenBingeGroupIsShared() {
        val preference = NextEpisodeSourcePreference("miruro", "Miruro", "Miruro | anikoto / HD-1 [SUB] · 1080p", "shared")
        val correct = stream("Miruro | anikoto / HD-1 [SUB] · 720p")
        assertEquals(listOf(correct), preference.matches(listOf(
            stream("Miruro | anikoto / HD-1 [DUB] · 1080p"),
            stream("Miruro | anikoto / HD-2 [SUB] · 1080p"),
            stream("Miruro | anikoto / HD-1 [SUB] · 1080p", "another"), correct,
        )))
    }
    @Test fun prefersHighestQualityWithinSameServer() {
        val preference = NextEpisodeSourcePreference("miruro", "Miruro", "Gigi [SUB] · Auto", null)
        val high = stream("Gigi [SUB] · 1080p")
        assertEquals(high, preference.matches(listOf(stream("Gigi [SUB] · 360p"), high)).first())
    }
    @Test fun missingServerDoesNotSelectAnotherServerOrDub() {
        val preference = NextEpisodeSourcePreference("miruro", "Miruro", "HD-1 [SUB]", "shared")
        assertTrue(preference.matches(listOf(stream("HD-2 [SUB]"), stream("HD-1 [DUB]"))).isEmpty())
    }
    @Test fun bingeFallbackIsStillScopedToProvider() {
        val preference = NextEpisodeSourcePreference("miruro", "Miruro", "", "shared")
        val correct = stream("Next")
        assertEquals(listOf(correct), preference.matches(listOf(stream("Next", "another"), correct)))
    }
    @Test fun multiAudioTrackCountDoesNotChangeServerIdentity() {
        val preference = NextEpisodeSourcePreference("salt", "AnimeSalt", "AnimeSalt | MyStream · Multi-Audio (7 tracks) [Multi-Audio]", null)
        val next = stream("AnimeSalt | MyStream · Multi-Audio (5 tracks) [Multi-Audio]", "salt")
        assertEquals(listOf(next), preference.matches(listOf(next)))
    }
}
