package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("tmdb_collection")
data class TmdbCollection(
    override var id: Long? = null,
    var tmdb_collection_id: Int = 0,
    var name: String = "",
    var poster_path: String? = null,
    var backdrop_path: String? = null,
    var fetched_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<TmdbCollection, Long>(TmdbCollection::class.java)
}

@Table("tmdb_collection_part")
data class TmdbCollectionPart(
    override var id: Long? = null,
    var collection_id: Long = 0,
    var tmdb_movie_id: Int = 0,
    var title: String = "",
    var position: Int = 0,
    var release_date: String? = null,
    var poster_path: String? = null
) : KEntity<Long> {
    companion object : Dao<TmdbCollectionPart, Long>(TmdbCollectionPart::class.java)
}
