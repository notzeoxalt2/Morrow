package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals

class SeriesSeasonSupportTest {

    @Test
    fun `normalize season number maps zero and null to specials`() {
        assertEquals(SPECIALS_SEASON_NUMBER, normalizeSeasonNumber(0))
        assertEquals(SPECIALS_SEASON_NUMBER, normalizeSeasonNumber(null))
        assertEquals(2, normalizeSeasonNumber(2))
    }

    @Test
    fun `sorted playable episodes place specials after numbered seasons`() {
        val meta = MetaDetails(
            id = "show",
            type = "series",
            name = "Show",
            videos = listOf(
                MetaVideo(id = "special-1", title = "Special 1", season = 0, episode = 1),
                MetaVideo(id = "season-2", title = "Episode 1", season = 2, episode = 1),
                MetaVideo(id = "season-1", title = "Episode 1", season = 1, episode = 1),
                MetaVideo(id = "special-2", title = "Special 2", season = 0, episode = 2),
            ),
        )

        assertEquals(
            listOf("season-1", "season-2", "special-1", "special-2"),
            meta.sortedPlayableEpisodes().map(MetaVideo::id),
        )
    }

    @Test
    fun `preferred episode only applies to its season`() {
        assertEquals(
            5,
            preferredEpisodeNumberForSeason(
                displayedSeasonNumber = 1,
                preferredSeasonNumber = 1,
                preferredEpisodeNumber = 5,
            ),
        )
        assertEquals(
            null,
            preferredEpisodeNumberForSeason(
                displayedSeasonNumber = 2,
                preferredSeasonNumber = 1,
                preferredEpisodeNumber = 5,
            ),
        )
    }

    @Test
    fun `grouped episodes preserve a large absolute-numbered catalog`() {
        val episodeCount = 1_500
        val meta = MetaDetails(
            id = "long-running-show",
            type = "series",
            name = "Long Running Show",
            videos = (episodeCount downTo 1).map { episodeNumber ->
                MetaVideo(
                    id = "episode-$episodeNumber",
                    title = "Episode $episodeNumber",
                    season = 1,
                    episode = episodeNumber,
                )
            },
        )

        val groupedEpisodes = meta.groupedEpisodesForDisplay()

        assertEquals(listOf(1), groupedEpisodes.keys.toList())
        assertEquals((1..episodeCount).toList(), groupedEpisodes.getValue(1).map(MetaVideo::episode))
    }

    @Test
    fun `absolute episode number follows broadcast order across seasons`() {
        val meta = MetaDetails(
            id = "anime",
            type = "series",
            name = "Anime",
            videos = listOf(
                MetaVideo(id = "s2e2", title = "S2E2", season = 2, episode = 2),
                MetaVideo(id = "special", title = "Special", season = 0, episode = 1),
                MetaVideo(id = "s1e2", title = "S1E2", season = 1, episode = 2),
                MetaVideo(id = "s2e1", title = "S2E1", season = 2, episode = 1),
                MetaVideo(id = "s1e1", title = "S1E1", season = 1, episode = 1),
            ),
        )

        assertEquals(3, meta.absoluteEpisodeNumber(season = 2, episode = 1))
        assertEquals(null, meta.absoluteEpisodeNumber(season = 0, episode = 1))
        assertEquals(null, meta.absoluteEpisodeNumber(season = 9, episode = 1))
    }

    @Test
    fun `anime defaults to flat episodes and quick jump opens at the selected episode`() {
        val anime = MetaDetails(
            id = "tmdb:one-piece",
            type = "series",
            name = "One Piece",
            genres = listOf("Animation", "Adventure"),
            country = "Japan",
        )
        assertEquals(true, anime.prefersFlatEpisodeCatalog())
        assertEquals(89, episodeWindowStart(requestedEpisodeNumber = 90, episodeCount = 1_100, pageSize = 100))
        assertEquals(1_099, episodeWindowStart(requestedEpisodeNumber = 1_500, episodeCount = 1_100, pageSize = 100))
        assertEquals(0, episodeWindowStart(requestedEpisodeNumber = 0, episodeCount = 90, pageSize = 100))
    }
}
