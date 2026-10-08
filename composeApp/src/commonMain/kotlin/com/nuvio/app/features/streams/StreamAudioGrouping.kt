package com.nuvio.app.features.streams

internal enum class StreamAudioGroup(val label: String) {
    SUB("SUB"), DUB("DUB"), MULTI_AUDIO("Multi-audio"), OTHER("Other streams"),
}

private val multiAudioPattern = Regex("\\b(?:multi[ -]?audio|dual[ -]?audio)\\b", RegexOption.IGNORE_CASE)
private val subPattern = Regex("\\[(?:SUB|HSUB|S-SUB|H-SUB)\\]|\\b(?:Japanese|English|hard|soft)[ -]?sub(?:bed|titles)?\\b", RegexOption.IGNORE_CASE)
private val dubPattern = Regex("\\[DUB\\]|\\b(?:English|Hindi|Tamil|Telugu|Kannada|Malayalam|German|French|Spanish|Portuguese)[ -]?dub(?:bed)?\\b", RegexOption.IGNORE_CASE)

internal fun StreamItem.audioGroup(): StreamAudioGroup {
    val text = listOfNotNull(name, title, description).joinToString(" ")
    val sub = subPattern.containsMatchIn(text)
    val dub = dubPattern.containsMatchIn(text)
    return when {
        multiAudioPattern.containsMatchIn(text) || (sub && dub) -> StreamAudioGroup.MULTI_AUDIO
        sub -> StreamAudioGroup.SUB
        dub -> StreamAudioGroup.DUB
        else -> StreamAudioGroup.OTHER
    }
}

internal data class StreamAudioSection(val audioGroup: StreamAudioGroup, val groups: List<AddonStreamGroup>)

internal fun List<AddonStreamGroup>.audioSections(): List<StreamAudioSection> =
    StreamAudioGroup.entries.mapNotNull { audio ->
        val sections = sortedBy { providerDisplayName(it.addonName).lowercase() }.mapNotNull { group ->
            val streams = group.streams.filter { it.audioGroup() == audio }.sortedForGroupedDisplay()
            when {
                streams.isNotEmpty() -> group.copy(streams = streams, isLoading = false)
                audio == StreamAudioGroup.OTHER && group.isLoading -> group.copy(streams = emptyList())
                else -> null
            }
        }
        sections.takeIf { it.isNotEmpty() }?.let { StreamAudioSection(audio, it) }
    }
