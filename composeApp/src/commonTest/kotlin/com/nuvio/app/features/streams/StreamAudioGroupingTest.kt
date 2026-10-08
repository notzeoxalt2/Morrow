package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamAudioGroupingTest {
    private fun stream(name: String, provider: String = "Miruro", description: String? = null) =
        StreamItem(name = name, addonName = provider, addonId = provider, description = description)

    @Test fun allProvidersSubStreamsPrecedeEveryDubStream() {
        val groups = listOf(
            AddonStreamGroup("Anikage", "a", listOf(stream("HD-1 [DUB]", "Anikage"), stream("HD-2 [SUB]", "Anikage"))),
            AddonStreamGroup("Miruro", "m", listOf(stream("Vault [DUB]"), stream("Vault [SUB]"))),
        )
        val sections = groups.audioSections()
        assertEquals(listOf(StreamAudioGroup.SUB, StreamAudioGroup.DUB), sections.map { it.audioGroup })
        assertEquals(listOf("Anikage", "Miruro"), sections.first().groups.map { it.addonName })
        assertTrue(sections.first().groups.flatMap { it.streams }.all { it.audioGroup() == StreamAudioGroup.SUB })
    }

    @Test fun multiAudioIsSeparateFromDubAndSub() {
        assertEquals(StreamAudioGroup.MULTI_AUDIO, stream("Dual-Audio [SUB] [DUB]").audioGroup())
        assertEquals(StreamAudioGroup.MULTI_AUDIO, stream("Multi audio").audioGroup())
        assertEquals(StreamAudioGroup.MULTI_AUDIO, stream("Multi-Audio").audioGroup())
    }

    @Test fun languageCodeAndSubtitleAvailabilityDoNotImplyDubOrSub() {
        assertEquals(StreamAudioGroup.OTHER, stream("Reacher", description = "1080p • en").audioGroup())
        assertEquals(StreamAudioGroup.OTHER, stream("Subtitle Collection").audioGroup())
    }

    @Test fun localizedAudioLabelsAreRecognized() {
        assertEquals(StreamAudioGroup.SUB, stream("Japanese Sub").audioGroup())
        assertEquals(StreamAudioGroup.DUB, stream("Hindi Dub").audioGroup())
        assertEquals(StreamAudioGroup.SUB, stream("[S-SUB]").audioGroup())
        assertEquals(StreamAudioGroup.SUB, stream("[HSUB]").audioGroup())
    }

    @Test fun qualityIsDescendingWithinEachProviderAndAudioGroup() {
        val ordered = listOf(stream("[DUB] 2160p"), stream("[SUB] 720p"), stream("[SUB] 1440p"), stream("[SUB] 1080p"))
            .sortedForGroupedDisplay()
        assertEquals(listOf("[SUB] 1440p", "[SUB] 1080p", "[SUB] 720p", "[DUB] 2160p"), ordered.map { it.name })
    }

    @Test fun loadingProviderAppearsOnceWhileStreamsRemainInCorrectSections() {
        val sections = listOf(AddonStreamGroup("Miruro", "m", listOf(stream("[SUB]"), stream("[DUB]")), isLoading = true)).audioSections()
        assertEquals(3, sections.size)
        assertFalse(sections[0].groups.single().isLoading)
        assertTrue(sections[2].groups.single().isLoading)
        assertTrue(sections[2].groups.single().streams.isEmpty())
    }
}
