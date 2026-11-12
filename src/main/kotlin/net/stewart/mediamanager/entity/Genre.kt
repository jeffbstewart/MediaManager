package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao

data class Genre(
    override var id: Long? = null,
    var name: String = ""
) : KEntity<Long> {
    companion object : Dao<Genre, Long>(Genre::class.java)
}
