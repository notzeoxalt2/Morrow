package com.nuvio.app.features.details

/** Match only title credits, by person ID or an unambiguous full name. */
internal fun mergeCastPhotos(cast: List<MetaPerson>, photos: List<MetaPerson>): List<MetaPerson> {
    fun nameKey(name: String) = name.trim().lowercase().replace(Regex("\\s+"), " ")
    return cast.map { person ->
        if (!person.photo.isNullOrBlank()) return@map person
        val matches = photos.filter { candidate ->
            if (person.tmdbId != null) candidate.tmdbId == person.tmdbId
            else nameKey(person.name).isNotEmpty() && nameKey(candidate.name) == nameKey(person.name)
        }
        val match = matches.singleOrNull() ?: return@map person
        person.copy(photo = match.photo?.takeIf { it.isNotBlank() }, tmdbId = person.tmdbId ?: match.tmdbId)
    }
}
