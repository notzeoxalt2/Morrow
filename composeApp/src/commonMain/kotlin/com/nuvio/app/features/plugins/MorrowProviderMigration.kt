package com.nuvio.app.features.plugins

internal object MorrowProviderMigration {
    val defaults = listOf(
        "https://raw.githubusercontent.com/notzeoxalt2/morrowx1movies/main/manifest.json",
        "https://raw.githubusercontent.com/notzeoxalt2/morrowx1anime/main/manifest.json",
    )
    private val legacy = setOf(
        "https://raw.githubusercontent.com/notzeoxalt2/morrowx2anime/main/manifest.json",
        "https://raw.githubusercontent.com/notzeoxalt2/morrow/main/providers/manifest.json",
        "https://raw.githubusercontent.com/d3adlyrocket/all-in-one-nuvio/main/manifest.json",
        "https://raw.githubusercontent.com/yoruix/nuvio-providers/main/manifest.json",
        "https://raw.githubusercontent.com/abinanthankv/nuviorepo/master/manifest.json",
        "https://raw.githubusercontent.com/michat88/nuvio-providers/main/manifest.json",
    )
    private val retiredAnimeIds = setOf(
        "hianime", "animepahe", "anidb", "anikototv", "aniwaves", "anitaku",
        "kurage", "animedekho", "anime-nexus", "lunarx",
    )
    private fun key(url: String): String = url.trim().substringBefore('?').substringBefore('#')
        .trimEnd('/').lowercase().replace("/refs/heads/", "/")

    fun isLegacy(url: String): Boolean = key(url) in legacy
    fun canonical(url: String): String = defaults.firstOrNull { key(it) == key(url) } ?: url
    fun repositoryUrls(urls: List<String>): List<String> =
        (urls.filterNot(::isLegacy).map(::canonical) + defaults).distinct()

    fun isRetiredScraper(repositoryUrl: String, scraperId: String): Boolean =
        isLegacy(repositoryUrl) || (canonical(repositoryUrl) == defaults[1] &&
            scraperId.substringAfterLast(':') in retiredAnimeIds)
}

