package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.UserFlagType
import net.stewart.mediamanager.entity.UserTitleFlag
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

object UserTitleFlagService {
    private val log = LoggerFactory.getLogger(UserTitleFlagService::class.java)

    /** Returns the current user's ID, or null if not authenticated. */
    private fun currentUserId(): Long? = AuthService.getCurrentUser()?.id

    fun hasFlag(titleId: Long, flagType: UserFlagType): Boolean {
        val userId = currentUserId() ?: return false
        return UserTitleFlag.findAll().any {
            it.user_id == userId && it.title_id == titleId && it.flag == flagType.name
        }
    }

    fun setFlag(titleId: Long, flagType: UserFlagType) {
        val userId = currentUserId() ?: return
        // Idempotent
        if (hasFlag(titleId, flagType)) return
        UserTitleFlag(
            user_id = userId,
            title_id = titleId,
            flag = flagType.name,
            created_at = LocalDateTime.now()
        ).save()
        log.info("Flag set: user={} title={} flag={}", userId, titleId, flagType)
    }

    fun clearFlag(titleId: Long, flagType: UserFlagType) {
        val userId = currentUserId() ?: return
        UserTitleFlag.findAll()
            .filter { it.user_id == userId && it.title_id == titleId && it.flag == flagType.name }
            .forEach { it.delete() }
        log.info("Flag cleared: user={} title={} flag={}", userId, titleId, flagType)
    }

    /** Toggles a flag and returns the new state (true = now set). */
    fun toggleFlag(titleId: Long, flagType: UserFlagType): Boolean {
        return if (hasFlag(titleId, flagType)) {
            clearFlag(titleId, flagType)
            false
        } else {
            setFlag(titleId, flagType)
            true
        }
    }

    /** Returns title IDs starred by the current user. */
    fun getStarredTitleIds(): Set<Long> {
        val userId = currentUserId() ?: return emptySet()
        return UserTitleFlag.findAll()
            .filter { it.user_id == userId && it.flag == UserFlagType.STARRED.name }
            .map { it.title_id }
            .toSet()
    }

    /** Returns title IDs personally hidden by the current user. */
    fun getHiddenTitleIds(): Set<Long> {
        val userId = currentUserId() ?: return emptySet()
        return UserTitleFlag.findAll()
            .filter { it.user_id == userId && it.flag == UserFlagType.HIDDEN.name }
            .map { it.title_id }
            .toSet()
    }
}
