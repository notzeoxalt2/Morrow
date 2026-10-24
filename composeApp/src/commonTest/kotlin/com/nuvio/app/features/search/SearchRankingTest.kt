package com.nuvio.app.features.search

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchRankingTest {
    @Test fun exactTitlePrecedesSequelsAndPartialMatches() {
        assertEquals(listOf("Naruto", "Naruto Shippuden", "Boruto: Naruto Next Generations"),
            rankSearchResults(listOf("Boruto: Naruto Next Generations", "Naruto Shippuden", "Naruto"), "naruto") { it })
    }
    @Test fun punctuationDoesNotHideExactMatch() {
        assertEquals("One-Piece", rankSearchResults(listOf("One Piece Film: Red", "One-Piece"), "one piece") { it }.first())
    }
    @Test fun preservesOrderForEqualAndUnrelatedResults() {
        val items = listOf("Bleach", "Death Note", "進撃の巨人")
        assertEquals(items, rankSearchResults(items, "Naruto") { it })
        assertEquals("進撃の巨人", rankSearchResults(items, "進撃の巨人") { it }.first())
    }
}
