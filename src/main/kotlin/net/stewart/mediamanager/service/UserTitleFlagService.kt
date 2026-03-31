package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.UserFlagType
import net.stewart.mediamanager.entity.UserTitleFlag
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

object UserTitleFlagService {
    private val log = LoggerFactory.getLogger(UserTitleFlagService::class.java)

    fun hasFlagForUser(userId: Long, titleId: Long, flagType: UserFlagType): Boolean {
        return UserTitleFlag.findAll().any {
            it.user_id == userId && it.title_id == titleId && it.flag == flagType.name
        }
    }

    fun setFlagForUser(userId: Long, titleId: Long, flagType: UserFlagType) {
        if (hasFlagForUser(userId, titleId, flagType)) return
        UserTitleFlag(
            user_id = userId,
            title_id = titleId,
            flag = flagType.name,
            created_at = LocalDateTime.now()
        ).save()
        log.info("Flag set: user={} title={} flag={}", userId, titleId, flagType)
    }

    fun clearFlagForUser(userId: Long, titleId: Long, flagType: UserFlagType) {
        UserTitleFlag.findAll()
            .filter { it.user_id == userId && it.title_id == titleId && it.flag == flagType.name }
            .forEach { it.delete() }
        log.info("Flag cleared: user={} title={} flag={}", userId, titleId, flagType)
    }

    fun getStarredTitleIdsForUser(userId: Long): Set<Long> {
        return UserTitleFlag.findAll()
            .filter { it.user_id == userId && it.flag == UserFlagType.STARRED.name }
            .map { it.title_id }
            .toSet()
    }

    fun getHiddenTitleIdsForUser(userId: Long): Set<Long> {
        return UserTitleFlag.findAll()
            .filter { it.user_id == userId && it.flag == UserFlagType.HIDDEN.name }
            .map { it.title_id }
            .toSet()
    }
}
