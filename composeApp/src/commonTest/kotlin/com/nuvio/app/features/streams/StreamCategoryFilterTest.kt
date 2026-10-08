package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals

class StreamCategoryFilterTest {
    @Test
    fun categoriesUseCapabilitiesAndPreserveMergedProviderMembership() {
        val groups = listOf(
            AddonStreamGroup("HiAnime", "plugin:hi-sub", emptyList(), sourceCategories = setOf("anime")),
            AddonStreamGroup("HiAnime", "plugin:hi-dub", emptyList(), sourceCategories = setOf("anime")),
            AddonStreamGroup("Cinema", "plugin:cinema", emptyList(), sourceCategories = sourceCategories(listOf("movie", "tv"))),
        )
        assertEquals(groups, StreamsUiState(groups = groups).filteredGroups)
        assertEquals(listOf("plugin:hi-sub", "plugin:hi-dub"),
            StreamsUiState(groups = groups, selectedFilter = "category:anime").filteredGroups.map { it.addonId })
        for (category in listOf("movies", "series")) {
            assertEquals(listOf("plugin:cinema"),
                StreamsUiState(groups = groups, selectedFilter = "category:$category").filteredGroups.map { it.addonId })
        }
        assertEquals(setOf("anime"), groups.providerSections().first { it.addonName == "HiAnime" }.sourceCategories)
    }
}
