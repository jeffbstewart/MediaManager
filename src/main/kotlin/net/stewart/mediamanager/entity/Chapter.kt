package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao

data class Chapter(
    override var id: Long? = null,
    var transcode_id: Long = 0,
    var chapter_number: Int = 0,
    var start_seconds: Double = 0.0,
    var end_seconds: Double = 0.0,
    var title: String? = null
) : KEntity<Long> {
    companion object : Dao<Chapter, Long>(Chapter::class.java)
}
