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

    @Test
    fun laterSeasonUsesVerifiedAbsoluteAnimeEpisodeForSeriesMalId() {
        assertEquals(
            AnimeMalEpisodeLookup(malId = 1735, episode = 73),
            resolveAnimeMalEpisodeLookup(
                malId = 1735,
                episodeMalId = null,
                season = 4,
                episode = 1,
                absoluteEpisode = 73,
            ),
        )
    }

    @Test
    fun exactSeasonMalMappingKeepsSeasonLocalEpisodeNumber() {
        assertEquals(
            AnimeMalEpisodeLookup(malId = 50803, episode = 1),
            resolveAnimeMalEpisodeLookup(
                malId = 127641,
                episodeMalId = 50803,
                season = 4,
                episode = 1,
                absoluteEpisode = 73,
            ),
        )
    }

    @Test
    fun laterSeasonWithoutVerifiedEpisodeMappingDoesNotGuess() {
        assertNull(
            resolveAnimeMalEpisodeLookup(
                malId = 1735,
                episodeMalId = null,
                season = 4,
                episode = 1,
                absoluteEpisode = null,
            ),
        )
    }
}
