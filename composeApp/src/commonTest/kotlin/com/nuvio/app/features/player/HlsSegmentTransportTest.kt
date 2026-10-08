package com.nuvio.app.features.player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HlsSegmentTransportTest {
    private val png = byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a) +
        ByteArray(12) + "IEND".encodeToByteArray() + ByteArray(4)
    private val ts = ByteArray(188 * 4).also { for (i in 0..3) it[i*188] = 0x47 }
    @Test fun stripsOnlyAnImagePrefixBeforeAlignedTransportPackets() {
        assertEquals(png.size, pngWrappedTransportStreamOffset(png + ts))
    }
    @Test fun leavesRealImagesAndOrdinaryTransportStreamsUntouched() {
        assertNull(pngWrappedTransportStreamOffset(png + ByteArray(600)))
        assertNull(pngWrappedTransportStreamOffset(ts))
    }
    @Test fun aSingleAccidentalSyncByteIsNotEnough() {
        assertNull(pngWrappedTransportStreamOffset(png + ByteArray(600).also { it[0] = 0x47 }))
    }
}
