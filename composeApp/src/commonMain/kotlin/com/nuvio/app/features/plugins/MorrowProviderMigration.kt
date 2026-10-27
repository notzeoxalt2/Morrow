package com.nuvio.app.features.plugins

internal object MorrowProviderMigration {
    val defaults = listOf(
        "https://raw.githubusercontent.com/notzeoxalt2/morrowx1movies/main/manifest.json",
        "https://raw.githubusercontent.com/notzeoxalt2/morrowx1anime/main/manifest.json",
    )
    private fun key(url: String): String = url.trim().substringBefore('?').substringBefore('#')
        .trimEnd('/').lowercase().replace("/refs/heads/", "/")

    private val retiredAnime2 = "https://raw.githubusercontent.com/notzeoxalt2/morrowx2anime/main/manifest.json"

    fun isLegacy(url: String): Boolean = key(url) == key(retiredAnime2)
    fun canonical(url: String): String = defaults.firstOrNull { key(it) == key(url) } ?: url
    fun repositoryUrls(urls: List<String>): List<String> =
        (urls.filterNot(::isLegacy).map(::canonical) + defaults).distinct()

    fun isRetiredScraper(repositoryUrl: String, scraperId: String): Boolean {
        val repo = key(repositoryUrl)
        val id = scraperId.substringAfterLast(':').lowercase()
        val x1Anime = key(defaults[1])
        val x2Anime = key(retiredAnime2)

        // These disabled X1 aliases were previously enabled while pointing to
        // Anidap instead of their named sites. Keep genuine providers available.
        val retiredX1Aliases = setOf(
            "animepahe", "anidb", "anikototv", "aniwaves", "anitaku",
            "kurage", "animedekho", "anime-nexus", "lunarx",
            // These IDs were exposed by the shared Anidap resolver under
            // site names. The independent implementations use distinct IDs.
            "hianime", "enma", "animetvplus", "kaa",
        )
        // X2 is consolidated into X1. Three entries are Anidap copies and its
        // SaltAnime entry duplicates X1's adapter, so old cached copies are dropped.
        val duplicateX2Aliases = setOf("animedekho", "anime-nexus", "lunarx", "saltanime")
        return (repo == x1Anime && id in retiredX1Aliases) ||
            (repo == x2Anime && id in duplicateX2Aliases)
    }
}
