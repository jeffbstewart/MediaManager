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
    var access_level: Int = ACCESS_LEVEL_VIEWER,
    var rating_ceiling: Int? = null,
    var must_change_password: Boolean = false,
    var locked: Boolean = false,
    var subtitles_enabled: Boolean = true,
    var live_tv_min_quality: Int = 4,
    /** Pinned [WebTheme] id, or null to follow [WebTheme.DEFAULT]. */
    var web_theme: String? = null,
    var privacy_policy_version: Int? = null,
    var privacy_policy_accepted_at: LocalDateTime? = null,
    var terms_of_use_version: Int? = null,
    var terms_of_use_accepted_at: LocalDateTime? = null,
    var created_at: LocalDateTime? = null,
    var updated_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<AppUser, Long>(AppUser::class.java) {
        // Persisted in app_user.access_level and mapped from the AccessLevel
        // proto enum in ProtoMappers; isAdmin() is a >= test, so any higher
        // value is an admin too. Changing a value needs a migration of the
        // existing rows.
        /** `access_level` of a viewer: browse, search, and play. */
        const val ACCESS_LEVEL_VIEWER = 1
        /** `access_level` of an admin: a viewer who also manages the catalog, users, and settings. */
        const val ACCESS_LEVEL_ADMIN = 2

        /** Returns the username for [id], or `"id=<n>"` if no such user exists. For audit logs. */
        fun usernameFor(id: Long): String = findById(id)?.username ?: "id=$id"
    }

    fun isAdmin(): Boolean = access_level >= ACCESS_LEVEL_ADMIN

    /** Returns the typed rating ceiling, or null if this user is unrestricted. */
    val ratingCeilingValue: RatingCeiling? get() = RatingCeiling.fromDb(rating_ceiling)

    /**
     * Checks whether this user is allowed to see a title with the given raw content_rating string.
     * - Admins always see everything
     * - null ceiling = unrestricted (sees everything)
     * - Unrated titles (rawRating is null) are hidden from ceiling-limited accounts
     * - Rated titles: checked against the [RatingCeiling]
     */
    fun canSeeRating(rawRating: String?): Boolean {
        if (isAdmin()) return true
        val ceiling = ratingCeilingValue ?: return true
        val rating = ContentRating.fromTmdbCertification(rawRating) ?: return false // unrated → hidden
        return ceiling.allows(rating)
    }
}
