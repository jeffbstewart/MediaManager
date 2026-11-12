package net.stewart.mediamanager.entity

/**
 * Type-safe composite key for TMDB identifiers.
 *
 * TMDB uses separate ID namespaces for movies and TV shows — the same integer can
 * refer to different titles (e.g., movie 253 = "Live and Let Die", TV 253 = "Star Trek").
 * Encoding both the numeric ID and the media type in a single value type makes it
 * impossible to accidentally compare or store a bare integer without its namespace.
 *
 * Person IDs are a separate TMDB namespace with no movie/TV ambiguity — left as bare Int.
 */
data class TmdbId(val id: Int, val type: MediaType) {
    val typeString: String get() = type.name
    override fun toString(): String = "$type:$id"

    companion object {
        fun of(id: Int?, mediaType: String?): TmdbId? {
            if (id == null || mediaType == null) return null
            val type = try { MediaType.valueOf(mediaType) }
                       catch (_: IllegalArgumentException) { return null }
            return TmdbId(id, type)
        }
    }
}
