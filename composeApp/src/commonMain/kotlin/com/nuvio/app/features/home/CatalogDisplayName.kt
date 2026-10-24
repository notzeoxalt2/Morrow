package com.nuvio.app.features.home

/** Presentation only: keep the addon catalog ID, URL and metadata attribution intact. */
internal fun catalogDisplayName(name: String, type: String): String {
    if (!type.equals("anime", true) && !Regex("(?i)\\banime\\b").containsMatchIn(name)) return name
    val cleaned = name.trim().replace(
        Regex("(?i)^(?:myanimelist|mal)(?:\\s*[-:|–—]\\s*|\\s+|$)"), "",
    ).trim()
    if (cleaned == name.trim()) return name
    return when (cleaned.lowercase()) {
        "", "anime", "anime catalog", "catalog" -> "Explore Anime"
        "top", "top anime", "top rated", "top rated anime" -> "Top Rated Anime"
        "popular", "popular anime", "most popular" -> "Popular Anime"
        "airing", "currently airing", "airing anime" -> "Airing Now"
        "upcoming", "upcoming anime" -> "Coming Soon"
        else -> cleaned
    }
}
