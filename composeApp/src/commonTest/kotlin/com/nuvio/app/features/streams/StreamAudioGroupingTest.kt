package com.nuvio.app.features.streams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamAudioGroupingTest {
    private fun stream(name: String, provider: String = "Miruro", description: String? = null) =
        StreamItem(name = name, addonName = provider, addonId = provider, description = description)
    @Test fun eachProviderAppearsOnceWithServerSubDubPairs() {
        val sections = listOf(
            AddonStreamGroup("Miruro", "old", listOf(stream("HD-2 [DUB]"), stream("HD-1 [SUB]"))),
            AddonStreamGroup("Miruro", "new", listOf(stream("HD-1 [DUB]"), stream("HD-2 [SUB]"))),
        ).providerSections()
        assertEquals(1, sections.size)
        assertEquals(listOf("HD-1 [SUB]", "HD-1 [DUB]", "HD-2 [SUB]", "HD-2 [DUB]"), sections.single().streams.map { it.name })
    }
    @Test fun duplicateProviderFilterIncludesBothInstances() {
        val groups = listOf(AddonStreamGroup("Miruro", "a", emptyList()), AddonStreamGroup("Miruro", "b", emptyList()))
        assertEquals(groups, StreamsUiState(groups = groups, selectedFilter = "provider-name:miruro").filteredGroups)
    }
    @Test fun multiAudioIsSeparateFromDubAndSub() {
        assertEquals(StreamAudioGroup.MULTI_AUDIO, stream("Dual-Audio [SUB] [DUB]").audioGroup())
        assertEquals(StreamAudioGroup.MULTI_AUDIO, stream("Multi-Audio").audioGroup())
    }
    @Test fun languageCodeAndSubtitleAvailabilityDoNotImplyDubOrSub() {
        assertEquals(StreamAudioGroup.OTHER, stream("Reacher", description = "1080p • en").audioGroup())
        assertEquals(StreamAudioGroup.OTHER, stream("Subtitle Collection").audioGroup())
    }
    @Test fun qualityIsDescendingWithinSameServerAndAudio() {
        val ordered = listOf(stream("[DUB] 2160p"), stream("[SUB] 720p"), stream("[SUB] 1440p"), stream("[SUB] 1080p")).sortedForGroupedDisplay()
        assertEquals(listOf("[SUB] 1440p", "[SUB] 1080p", "[SUB] 720p", "[DUB] 2160p"), ordered.map { it.name })
    }
    @Test fun loadingProviderRemainsOneSectionAndIdenticalRowsDeduplicate() {
        val sub = stream("HD-1 [SUB]")
        val sections = listOf(AddonStreamGroup("Miruro", "a", listOf(sub)), AddonStreamGroup("Miruro", "b", listOf(sub), isLoading = true)).providerSections()
        assertEquals(1, sections.size)
        assertTrue(sections.single().isLoading)
        assertEquals(1, sections.single().streams.size)
    }
}
