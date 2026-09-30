package com.nuvio.app.features.plugins

import kotlin.test.Test
import kotlin.test.assertEquals

class PluginContentIdsTest {
    @Test
    fun `anime namespaces survive parent and provider lookup`() {
        for ((playback, parent) in listOf(
            "anilist:21:1:3" to "anilist:21",
            "mal:34566:1:3" to "mal:34566",
            "kitsu:12:3" to "kitsu:12",
        )) {
            assertEquals(parent, contentParentId(playback))
            assertEquals(parent, pluginContentId(playback, 1, 3))
        }
    }

    @Test
    fun `foreign numeric ids are never mistaken for anilist ids`() {
        val service = com.nuvio.app.features.anime.AnimeMetadataService
        assertEquals(21, service.extractAniListId("anilist:21:1:3"))
        assertEquals(null, service.extractAniListId("mal:21"))
        assertEquals(null, service.extractAniListId("kitsu:12"))
        assertEquals(null, service.extractAniListId("tt0388629"))
        assertEquals(null, service.extractAniListId("tmdb:37854"))
    }

    @Test
    fun `future episode with null translation does not invalidate anime mappings`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val response = json.decodeFromString<com.nuvio.app.features.anime.AniZipResponse>(
            """{"mappings":{"anilist_id":21,"themoviedb_id":"37854"},"episodes":{"1":{"title":{"en":"I'm Luffy!"}},"1181":{"title":{"en":null,"ja":null}}}}""",
        )
        assertEquals("37854", response.mappings?.themoviedb_id)
        assertEquals("I'm Luffy!", response.episodes?.get("1")?.displayTitle)
        assertEquals(null, response.episodes?.get("1181")?.displayTitle)
    }

    @Test
    fun `series playback id strips season episode suffix`() {
        assertEquals(
            "tt2575988",
            pluginContentId(
                videoId = "tt2575988:5:8",
                season = 5,
                episode = 8,
            ),
        )
    }

    @Test
    fun `tmdb prefixed series playback id strips prefix and suffix`() {
        assertEquals(
            "12345",
            pluginContentId(
                videoId = "tmdb:12345:2:6",
                season = 2,
                episode = 6,
            ),
        )
    }

    @Test
    fun `movie id stays unchanged`() {
        assertEquals(
            "tt0133093",
            pluginContentId(
                videoId = "tt0133093",
                season = null,
                episode = null,
            ),
        )
    }

    @Test
    fun `slash prefixed tmdb id keeps base content id`() {
        assertEquals(
            "999",
            pluginContentId(
                videoId = "tmdb/999/1/2",
                season = 1,
                episode = 2,
            ),
        )
    }
}
