package com.nuvio.app.features.plugins

/** Only the owned anime repository can be replaced by a locally packaged test snapshot. */
internal fun bundledAnimePreviewPath(url: String): String? {
    val clean = url.substringBefore('?').substringBefore('#').replace("/refs/heads/", "/")
    val base = "https://raw.githubusercontent.com/notzeoxalt2/morrowx1anime/main/"
    if (!clean.startsWith(base)) return null
    val path = clean.removePrefix(base)
    return path.takeIf { it == "manifest.json" || Regex("providers/[a-z0-9-]+\\.js").matches(it) }
}
