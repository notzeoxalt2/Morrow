package com.nuvio.app.features.player.skip

internal sealed interface SkipIntervalLookup {
    data class Imdb(val imdbId: String, val season: Int, val episode: Int) : SkipIntervalLookup
    data class Mal(val malId: String, val episode: Int) : SkipIntervalLookup
    data class Kitsu(val kitsuId: String, val episode: Int) : SkipIntervalLookup
}

internal fun resolveSkipIntervalLookup(
    videoId: String?,
    season: Int?,
    episode: Int?,
    mappedMalId: Int? = null,
    mappedMalEpisode: Int? = null,
    absoluteEpisode: Int? = null,
): SkipIntervalLookup? {
    val normalizedId = videoId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val parts = normalizedId.split(':')

    return when {
        normalizedId.startsWith("mal:", ignoreCase = true) -> {
            val embeddedMalId = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
            val embeddedEpisode = parts.takeIf { it.size >= 3 }?.lastOrNull()?.toIntOrNull()
            val resolvedEpisode = mappedMalEpisode
                ?: absoluteEpisode
                ?: episode?.takeIf { season == null || season <= 1 }
                ?: embeddedEpisode?.takeIf { season == null || season <= 1 }
                ?: return null
            SkipIntervalLookup.Mal(malId = mappedMalId?.toString() ?: embeddedMalId, episode = resolvedEpisode)
        }

        normalizedId.startsWith("kitsu:", ignoreCase = true) -> {
            val kitsuId = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
            val embeddedEpisode = parts.takeIf { it.size >= 3 }?.lastOrNull()?.toIntOrNull()
            val resolvedEpisode = episode ?: embeddedEpisode ?: return null
            SkipIntervalLookup.Kitsu(kitsuId = kitsuId, episode = resolvedEpisode)
        }

        parts.firstOrNull()?.startsWith("tt", ignoreCase = true) == true -> {
            val resolvedSeason = season ?: parts.getOrNull(1)?.toIntOrNull() ?: return null
            val resolvedEpisode = episode ?: parts.getOrNull(2)?.toIntOrNull() ?: return null
            SkipIntervalLookup.Imdb(
                imdbId = parts.first(),
                season = resolvedSeason,
                episode = resolvedEpisode,
            )
        }

        mappedMalId != null && mappedMalId > 0 -> {
            val resolvedEpisode = mappedMalEpisode
                ?: absoluteEpisode
                ?: episode?.takeIf { season == null || season <= 1 }
                ?: return null
            SkipIntervalLookup.Mal(malId = mappedMalId.toString(), episode = resolvedEpisode)
        }

        else -> null
    }
}
