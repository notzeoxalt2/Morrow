package com.nuvio.app.features.anime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnimeEpisodeClassificationTest {
    @Test
    fun identifiesFillerAndRecapFromExplicitEpisodeFlags() {
        assertEquals(
            AnimeEpisodeClassification.Filler,
            AnimeMetadataService.classifyAnimeEpisode(filler = true, recap = false),
        )
        assertEquals(
            AnimeEpisodeClassification.Recap,
            AnimeMetadataService.classifyAnimeEpisode(filler = false, recap = true),
        )
    }

    @Test
    fun leavesUnflaggedOrUnknownEpisodesUnlabelled() {
        assertNull(AnimeMetadataService.classifyAnimeEpisode(filler = false, recap = false))
        assertNull(AnimeMetadataService.classifyAnimeEpisode(filler = null, recap = null))
    }
}
