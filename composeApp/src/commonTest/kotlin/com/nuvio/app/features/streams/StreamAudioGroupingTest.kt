package com.nuvio.app.features.streams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamAudioGroupingTest {
    private fun stream(
        name: String,
        provider: String = "Miruro",
        description: String? = null,
        url: String? = null,
    ) = StreamItem(name = name, addonName = provider, addonId = provider, description = description, url = url)
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

    @Test fun exactMediaLinksAreMarkedAcrossSitesWithoutMergingProviderSections() {
        val url = "https://cdn.example/episode/master.m3u8?token=one"
        val sections = listOf(
            AddonStreamGroup("AnimeSalt", "salt", listOf(stream("MyStream [SUB]", "AnimeSalt", url = url))),
            AddonStreamGroup("Anidap", "anidap", listOf(stream("Nero [SUB]", "Anidap", url = url))),
        ).providerSections()

        assertEquals(listOf("Anidap", "AnimeSalt"), sections.map { it.addonName })
        assertEquals(listOf("AnimeSalt"), sections.first { it.addonName == "Anidap" }.streams.single().sameLinkAlsoOn)
        assertEquals(listOf("Anidap"), sections.first { it.addonName == "AnimeSalt" }.streams.single().sameLinkAlsoOn)
    }

    @Test fun signedLinksWithRotatingAuthTokensAreMarkedAsTheSameMedia() {
        val sections = listOf(
            AddonStreamGroup("AnimeSalt", "salt", listOf(stream("MyStream", "AnimeSalt", url = "https://cdn.example/ep.m3u8?token=one"))),
            AddonStreamGroup("Anidap", "anidap", listOf(stream("Nero", "Anidap", url = "https://cdn.example/ep.m3u8?token=two"))),
        ).providerSections()

        assertEquals(listOf("Anidap"), sections.first { it.addonName == "AnimeSalt" }.streams.single().sameLinkAlsoOn)
        assertEquals(listOf("AnimeSalt"), sections.first { it.addonName == "Anidap" }.streams.single().sameLinkAlsoOn)
    }

    @Test fun providerFilterStillDetectsSameLinksFromTheUnfilteredResults() {
        val url = "https://cdn.example/episode/master.m3u8?token=rotating"
        val allGroups = listOf(
            AddonStreamGroup("AnimeSalt", "salt", listOf(stream("MyStream [SUB]", "AnimeSalt", url = url))),
            AddonStreamGroup("Anidap", "anidap", listOf(stream("Nero [SUB]", "Anidap", url = url))),
        )

        val filteredSections = listOf(allGroups.last()).providerSections(allGroups)

        assertEquals(1, filteredSections.size)
        assertEquals(listOf("AnimeSalt"), filteredSections.single().streams.single().sameLinkAlsoOn)
    }

    @Test fun meaningfulEpisodeAndQualityQueryValuesRemainDistinct() {
        val sections = listOf(
            AddonStreamGroup("AnimeSalt", "salt", listOf(stream("MyStream", "AnimeSalt", url = "https://cdn.example/ep.m3u8?ep=1&token=one"))),
            AddonStreamGroup("Anidap", "anidap", listOf(stream("Nero", "Anidap", url = "https://cdn.example/ep.m3u8?ep=2&token=two"))),
        ).providerSections()

        assertTrue(sections.all { it.streams.single().sameLinkAlsoOn.isEmpty() })
    }
}
