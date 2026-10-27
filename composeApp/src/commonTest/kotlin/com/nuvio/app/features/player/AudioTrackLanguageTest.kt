package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioTrackLanguageTest {
    @Test
    fun `track language metadata takes precedence over codec label`() {
        assertEquals("en", audioTrackLanguageCode("en", "Dolby Digital Plus / 5.1"))
    }

    @Test
    fun `language embedded in a track label is recognized`() {
        assertEquals("hi", audioTrackLanguageCode("und", "Dolby Digital Plus / 5.1 / Hindi"))
    }

    @Test
    fun `unknown language and codec-only labels do not claim a language`() {
        assertNull(audioTrackLanguageCode("und", "Dolby Digital Plus / 7.1"))
    }
}
