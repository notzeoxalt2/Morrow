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


/** One visual provider section, even if profile sync contains duplicate instances. */
internal fun List<AddonStreamGroup>.providerSections(): List<AddonStreamGroup> =
    groupBy { providerDisplayName(it.addonName).lowercase() }.map { (name, groups) ->
        AddonStreamGroup(
            addonName = providerDisplayName(groups.first().addonName),
            addonId = "provider-name:$name",
            streams = groups.flatMap { it.streams }.distinctBy {
                listOf(it.playableDirectUrl, it.infoHash, it.fileIdx, it.streamLabel,
                    it.behaviorHints.proxyHeaders?.request)
            }.sortedForGroupedDisplay(),
            isLoading = groups.any { it.isLoading },
            error = groups.firstNotNullOfOrNull { it.error },
        )
    }.sortedBy { it.addonName.lowercase() }

internal fun StreamItem.serverDisplayKey(): String = streamLabel.lowercase()
    .replace(Regex("""\[(?:sub|dub|hsub|s-sub|h-sub)\]"""), "")
    .replace(Regex("""\b(?:japanese sub|english dub|hindi dub|tamil dub|telugu dub|kannada dub|malayalam dub|\d{3,4}p|4k|8k|auto)\b"""), "")
    .replace(Regex("""[|·:/\-]+\s*$"""), "")
    .replace(Regex("""\s+"""), " ").trim()
