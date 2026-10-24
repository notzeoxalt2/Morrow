package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogDisplayNameTest {
    @Test fun removesTechnicalAnimePrefix() {
        assertEquals("Explore Anime", catalogDisplayName("MAL Anime", "anime"))
        assertEquals("Top Rated Anime", catalogDisplayName("MyAnimeList - Top Rated", "anime"))
        assertEquals("Popular Anime", catalogDisplayName("MAL: Popular", "anime"))
    }
    @Test fun leavesOtherCatalogsAndGenresAlone() {
        assertEquals("Romance", catalogDisplayName("Romance", "anime"))
        assertEquals("Malcolm Movies", catalogDisplayName("Malcolm Movies", "movie"))
        assertEquals("Coming Soon", catalogDisplayName("MAL Upcoming", "anime"))
    }
}
