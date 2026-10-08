package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamItem

/** Keeps server/audio labels stable while allowing the next episode's quality to differ. */
internal data class NextEpisodeSourcePreference(
    val providerId: String?, val providerName: String, val streamLabel: String, val bingeGroup: String?,
) {
    val hasIdentity: Boolean get() = (!providerId.isNullOrBlank() || providerName.isNotBlank()) &&
        (stableLabel(streamLabel).isNotBlank() || !bingeGroup.isNullOrBlank())

    fun matches(streams: List<StreamItem>): List<StreamItem> = streams.filter { stream ->
        val sameProvider = if (!providerId.isNullOrBlank()) stream.addonId == providerId
            else stream.addonName.equals(providerName, ignoreCase = true)
        // A shared binge group alone may span several servers or SUB/DUB choices.
        val label = stableLabel(streamLabel)
        val sameSource = if (label.isNotBlank()) stableLabel(stream.streamLabel) == label
            else !bingeGroup.isNullOrBlank() && stream.behaviorHints.bingeGroup == bingeGroup
        sameProvider && sameSource
    }.sortedByDescending { stream ->
        Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).find(stream.streamLabel)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}

private fun stableLabel(value: String): String = value.lowercase()
    .replace(Regex("""\b(?:\d{3,4}p|4k|8k|auto)\b"""), "")
    .trim()
    .replace(Regex("""[|·:/\-]+\s*$"""), "")
    .replace(Regex("""\s+"""), " ").trim()
