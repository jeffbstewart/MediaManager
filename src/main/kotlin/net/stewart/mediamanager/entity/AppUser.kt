package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("app_user")
data class AppUser(
    override var id: Long? = null,
    var username: String = "",
    var display_name: String = "",
    var password_hash: String = "",
    var access_level: Int = 1,
    var rating_ceiling: Int? = null,
    var must_change_password: Boolean = false,
    var locked: Boolean = false,
    var subtitles_enabled: Boolean = true,
    var live_tv_min_quality: Int = 4,
    var created_at: LocalDateTime? = null,
    var updated_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<AppUser, Long>(AppUser::class.java)

    fun isAdmin(): Boolean = access_level >= 2

    /**
     * Checks whether this user is allowed to see a title with the given raw content_rating string.
     * - Admins always see everything
     * - null ceiling = unrestricted (sees everything)
     * - Unrated titles (rawRating is null) are hidden from ceiling-limited accounts
     * - Rated titles: compare ordinal levels
     */
    fun canSeeRating(rawRating: String?): Boolean {
        if (isAdmin()) return true
        val ceiling = rating_ceiling ?: return true
        val rating = ContentRating.fromTmdbCertification(rawRating) ?: return false // unrated → hidden
        return rating.ordinalLevel <= ceiling
    }
}
