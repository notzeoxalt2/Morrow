package com.nuvio.app.features.player

/** Public HLS hosts can prefix MPEG-TS with an image wrapper. Ordinary images stay intact. */
internal fun pngWrappedTransportStreamOffset(prefix: ByteArray): Int? {
    val signature = intArrayOf(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    if (prefix.size < 8 || signature.indices.any { (prefix[it].toInt() and 255) != signature[it] }) return null
    // Require the PNG end marker before looking for multiple aligned TS packets.
    val end = (8 until (prefix.size - 8)).firstOrNull { index ->
        prefix[index] == 'I'.code.toByte() && prefix[index+1] == 'E'.code.toByte() &&
            prefix[index+2] == 'N'.code.toByte() && prefix[index+3] == 'D'.code.toByte()
    }?.plus(8) ?: return null
    return (end until (prefix.size - 376)).firstOrNull { index ->
        prefix[index] == 0x47.toByte() && prefix[index+188] == 0x47.toByte() && prefix[index+376] == 0x47.toByte()
    }
}
