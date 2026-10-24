package com.nuvio.app.features.plugins

internal object MorrowProviderMigration {
    val defaults = listOf(
        "https://raw.githubusercontent.com/notzeoxalt2/morrowx1movies/main/manifest.json",
        "https://raw.githubusercontent.com/notzeoxalt2/morrowx1anime/main/manifest.json",
        "https://raw.githubusercontent.com/notzeoxalt2/morrowx2anime/main/manifest.json",
    )
    private fun key(url: String): String = url.trim().substringBefore('?').substringBefore('#')
        .trimEnd('/').lowercase().replace("/refs/heads/", "/")

    // Keep previously installed user repositories and scrapers. Old aliases may
    // still contain live regional or alternate-server providers.
    fun isLegacy(url: String): Boolean = false
    fun canonical(url: String): String = defaults.firstOrNull { key(it) == key(url) } ?: url
    fun repositoryUrls(urls: List<String>): List<String> =
        (urls.map(::canonical) + defaults).distinct()

    fun isRetiredScraper(repositoryUrl: String, scraperId: String): Boolean = false
}

