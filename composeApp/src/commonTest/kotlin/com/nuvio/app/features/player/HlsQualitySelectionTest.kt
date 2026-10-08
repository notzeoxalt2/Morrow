package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HlsQualitySelectionTest {
    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",URI="audio.m3u8"
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",URI="subs.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720,AUDIO="audio"
        720.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=12000000,RESOLUTION=3840x2160,AUDIO="audio"
        2160.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=700000,RESOLUTION=640x360,AUDIO="audio"
        360.m3u8
    """.trimIndent()

    @Test fun maximumUsesHighestAvailableEvenAbove1080pAndRetainsTracks() {
        val result = selectHlsQuality(master, VideoQuality.Max)
        assertTrue(result.contains("2160.m3u8"))
        assertFalse(result.contains("720.m3u8"))
        assertTrue(result.contains("audio.m3u8"))
        assertTrue(result.contains("subs.m3u8"))
    }

    @Test fun manualCapsSelectExistingRendition() {
        assertTrue(selectHlsQuality(master, VideoQuality.High).contains("720.m3u8"))
        assertFalse(selectHlsQuality(master, VideoQuality.High).contains("2160.m3u8"))
        assertTrue(selectHlsQuality(master, VideoQuality.Mid).contains("360.m3u8"))
    }

    @Test fun autoAndSingleQualityMediaStayIntact() {
        assertEquals(master, selectHlsQuality(master, VideoQuality.Auto))
        val media = "#EXTM3U\n#EXTINF:6,\nsegment.ts\n#EXT-X-ENDLIST"
        assertEquals(media, selectHlsQuality(media, VideoQuality.Max))
    }

    @Test fun missingResolutionUsesBandwidth() {
        val playlist = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=9000000\nhigh.m3u8"
        assertFalse(selectHlsQuality(playlist, VideoQuality.Low).contains("high.m3u8"))
        assertTrue(selectHlsQuality(playlist, VideoQuality.Max).contains("high.m3u8"))
    }
}
