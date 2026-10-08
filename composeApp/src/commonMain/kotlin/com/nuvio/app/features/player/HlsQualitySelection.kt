package com.nuvio.app.features.player

/** Filter only master-playlist variants; media playlists, audio and subtitles are retained. */
internal fun selectHlsQuality(content: String, quality: VideoQuality): String {
    if (quality == VideoQuality.Auto) return content
    val lines = content.lines()
    data class Variant(val header: Int, val uri: Int, val height: Int, val bitrate: Long)
    val variants = lines.mapIndexedNotNull { index, line ->
        if (!line.trimStart().startsWith("#EXT-X-STREAM-INF:")) return@mapIndexedNotNull null
        val uri = (index + 1 until lines.size).firstOrNull { lines[it].isNotBlank() && !lines[it].trimStart().startsWith('#') }
            ?: return@mapIndexedNotNull null
        val height = Regex("RESOLUTION=\\d+x(\\d+)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val bandwidth = Regex("(?:^|[:,])BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        Variant(index, uri, height, bandwidth)
    }
    if (variants.size <= 1) return content
    val maxHeight = when (quality) { VideoQuality.High -> 720; VideoQuality.Mid -> 480; VideoQuality.Low -> 360; else -> Int.MAX_VALUE }
    val bitrateCap = when (quality) { VideoQuality.High -> 4_000_000L; VideoQuality.Mid -> 1_500_000L; VideoQuality.Low -> 800_000L; else -> Long.MAX_VALUE }
    val knownHeights = variants.filter { it.height > 0 }
    val candidates = if (knownHeights.isNotEmpty()) knownHeights.filter { it.height <= maxHeight }
        else variants.filter { it.bitrate <= bitrateCap }
    val order = compareBy<Variant>({ it.height }, { it.bitrate })
    val selected = candidates.maxWithOrNull(order) ?: variants.minWithOrNull(order) ?: return content
    val removed = variants.filter { it != selected }.flatMap { listOf(it.header, it.uri) }.toSet()
    return lines.filterIndexed { index, _ -> index !in removed }.joinToString("\n")
}
