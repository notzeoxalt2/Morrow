package com.nuvio.app.features.search

/** Rank display titles without changing the IDs used to resolve streams. Equal matches stay stable. */
internal fun <T> rankSearchResults(items: List<T>, query: String, title: (T) -> String): List<T> {
    val wanted = normalizedSearchTitle(query)
    if (wanted.isEmpty()) return items
    val words = wanted.split(' ')
    fun score(item: T): Int {
        val candidate = normalizedSearchTitle(title(item))
        val tokens = candidate.split(' ')
        return when {
            candidate == wanted -> 0
            candidate.replace(" ", "") == wanted.replace(" ", "") -> 1
            candidate.startsWith("$wanted ") -> 2
            words.all { it in tokens } -> 3
            candidate.contains(wanted) -> 4
            else -> 5
        }
    }
    return items.sortedBy(::score)
}

private fun normalizedSearchTitle(value: String): String = value.lowercase()
    .replace('&', ' ')
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()
