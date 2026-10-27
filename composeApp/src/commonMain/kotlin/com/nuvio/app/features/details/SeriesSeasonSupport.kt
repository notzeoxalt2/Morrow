package com.nuvio.app.features.details

internal const val SPECIALS_SEASON_NUMBER = 0

internal val metaVideoSeasonEpisodeComparator: Comparator<MetaVideo> =
    compareBy<MetaVideo>(
        { seasonSortKey(it.season) },
        { it.episode ?: Int.MAX_VALUE },
        { it.released ?: "" },
        { it.title },
    )

internal fun normalizeSeasonNumber(seasonNumber: Int?): Int =
    if (seasonNumber == null || seasonNumber <= SPECIALS_SEASON_NUMBER) {
        SPECIALS_SEASON_NUMBER
    } else {
        seasonNumber
    }

internal fun seasonSortKey(seasonNumber: Int?): Int =
    if (seasonNumber == null || seasonNumber <= SPECIALS_SEASON_NUMBER) {
        Int.MAX_VALUE
    } else {
        seasonNumber
    }

internal fun preferredEpisodeNumberForSeason(
    displayedSeasonNumber: Int,
    preferredSeasonNumber: Int?,
    preferredEpisodeNumber: Int?,
): Int? = preferredEpisodeNumber.takeIf { displayedSeasonNumber == preferredSeasonNumber }

internal fun MetaDetails.groupedEpisodesForDisplay(): Map<Int, List<MetaVideo>> {
    val numberedEpisodes = videos.filter { it.season != null || it.episode != null }
    return when {
        numberedEpisodes.isNotEmpty() -> numberedEpisodes
            .sortedWith(metaVideoSeasonEpisodeComparator)
            .groupBy { normalizeSeasonNumber(it.season) }
        type != "series" && videos.isNotEmpty() -> mapOf(normalizeSeasonNumber(null) to videos)
        else -> emptyMap()
    }
}

/** Returns a verified AniZip broadcast number; never infer a flat-catalog number from list order. */
internal fun MetaDetails.absoluteEpisodeNumber(season: Int?, episode: Int?): Int? {
    if (season == null || episode == null || season <= SPECIALS_SEASON_NUMBER || episode <= 0) return null
    videos.firstOrNull { it.season == season && it.episode == episode }
        ?.absoluteEpisodeNumber
        ?.takeIf { it > 0 }
        ?.let { return it }
    return null
}

internal fun MetaDetails.isAnimeCatalog(): Boolean =
    id.startsWith("anilist:", ignoreCase = true) ||
        id.startsWith("mal:", ignoreCase = true) ||
        id.startsWith("kitsu:", ignoreCase = true) ||
        genres.any { it.contains("anime", ignoreCase = true) } ||
        (genres.any { it.contains("animation", ignoreCase = true) } &&
            (country?.contains("JP", ignoreCase = true) == true ||
                country?.contains("Japan", ignoreCase = true) == true ||
                language?.equals("ja", ignoreCase = true) == true ||
                language?.contains("Japanese", ignoreCase = true) == true))

/**
 * Keep short, genuinely split anime in season view by default. Very long catalogs
 * start flat so users can page through large episode runs without crossing season tabs.
 */
internal fun MetaDetails.prefersFlatEpisodeCatalog(): Boolean {
    if (!isAnimeCatalog()) return false
    val numberedEpisodes = videos.filter { (it.season ?: 0) > 0 && (it.episode ?: 0) > 0 }
    if (numberedEpisodes.isEmpty()) return true
    val seasonCount = numberedEpisodes.mapNotNull { it.season }.distinct().size
    return seasonCount <= 1 || numberedEpisodes.size >= 200
}

internal fun episodeWindowStart(
    requestedEpisodeNumber: Int?,
    episodeCount: Int,
    pageSize: Int,
): Int {
    if (episodeCount <= 0 || pageSize <= 0) return 0
    return ((requestedEpisodeNumber ?: 1) - 1).coerceIn(0, episodeCount - 1)
}
