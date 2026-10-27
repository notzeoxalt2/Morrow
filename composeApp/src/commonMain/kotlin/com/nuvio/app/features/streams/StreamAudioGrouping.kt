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
internal fun List<AddonStreamGroup>.providerSections(
    allGroupsForLinkDetection: List<AddonStreamGroup> = this,
): List<AddonStreamGroup> =
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
            sourceCategories = groups.flatMap { it.sourceCategories }.toSet(),
        )
    }.let { sections -> markExactCrossProviderLinks(sections, allGroupsForLinkDetection) }
        .sortedBy { it.addonName.lowercase() }

/** Keep provider attribution intact while identifying the same media URL with rotated auth tokens. */
private fun markExactCrossProviderLinks(
    groups: List<AddonStreamGroup>,
    allGroupsForLinkDetection: List<AddonStreamGroup>,
): List<AddonStreamGroup> {
    val providersByUrl = allGroupsForLinkDetection.flatMap { group ->
        group.streams.mapNotNull { stream ->
            stream.duplicateMediaUrlKey()?.let { it to providerDisplayName(group.addonName) }
        }
    }.groupBy({ it.first }, { it.second })
        .mapValues { (_, names) -> names.distinctBy(String::lowercase) }

    return groups.map { group ->
        group.copy(streams = group.streams.map { stream ->
            val otherProviders = stream.duplicateMediaUrlKey()
                ?.let(providersByUrl::get)
                .orEmpty()
                .filterNot { it.equals(group.addonName, ignoreCase = true) }
                .distinctBy(String::lowercase)
            stream.copy(sameLinkAlsoOn = otherProviders)
        })
    }
}

private val signedUrlAuthQueryKeys = setOf(
    "token", "access_token", "auth", "auth_key", "authorization", "signature", "sig",
    "expires", "expire", "exp", "policy", "key-pair-id", "hdnts", "hdnea",
    "x-amz-algorithm", "x-amz-credential", "x-amz-date", "x-amz-expires",
    "x-amz-security-token", "x-amz-signature", "x-amz-signedheaders",
    "se", "sp", "spr", "sv", "sr", "st", "si", "sip", "skoid", "sktid",
    "skt", "ske", "sks", "skv", "rscc", "rscd", "rsce", "rscl", "rsct",
)

/**
 * CDN auth query values rotate per request. Ignore only known signing fields so the same
 * episode URL still matches, while meaningful query values such as episode/quality remain.
 */
private fun StreamItem.duplicateMediaUrlKey(): String? {
    val rawUrl = playableDirectUrl?.substringBefore('#')?.takeIf(String::isNotBlank) ?: return null
    val match = Regex("^([a-z][a-z0-9+.-]*://)([^/?#]+)([^?#]*)(?:\\?([^#]*))?$", RegexOption.IGNORE_CASE)
        .matchEntire(rawUrl)
        ?: return rawUrl
    val meaningfulQuery = match.groupValues[4]
        .split('&')
        .filter(String::isNotBlank)
        .filterNot { part -> part.substringBefore('=').lowercase() in signedUrlAuthQueryKeys }
        .sorted()
        .joinToString("&")
    return buildString {
        append(match.groupValues[1].lowercase())
        append(match.groupValues[2].lowercase())
        append(match.groupValues[3])
        if (meaningfulQuery.isNotEmpty()) append('?').append(meaningfulQuery)
    }
}

internal fun StreamItem.serverDisplayKey(): String = streamLabel.lowercase()
    .replace(Regex("""\[(?:sub|dub|hsub|s-sub|h-sub)\]"""), "")
    .replace(Regex("""\b(?:japanese sub|english dub|hindi dub|tamil dub|telugu dub|kannada dub|malayalam dub|\d{3,4}p|4k|8k|auto)\b"""), "")
    .replace(Regex("""[|·:/\-]+\s*$"""), "")
    .replace(Regex("""\s+"""), " ").trim()
