package com.nuvio.app.features.plugins

/** Preserve ID namespaces when removing playback episode suffixes. */
internal fun contentParentId(videoId: String): String {
    val value = videoId.trim().replace("tmdb/", "tmdb:")
    val parts = value.split(':')
    return if (parts.size >= 2 && parts[0].lowercase() in setOf("anilist", "mal", "kitsu", "tmdb", "movie", "series")) {
        parts.take(2).joinToString(":").substringBefore('/')
    } else value.substringBefore(':').substringBefore('/')
}

internal fun pluginContentId(videoId: String, season: Int?, episode: Int?): String {
    if (videoId.isBlank()) return videoId
    return contentParentId(videoId).removePrefix("tmdb:")
}
