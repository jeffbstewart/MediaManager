package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.FamilyMember
import net.stewart.mediamanager.entity.TitleFamilyMember
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalDateTime

object FamilyMemberService {
    private val log = LoggerFactory.getLogger(FamilyMemberService::class.java)

    fun createMember(name: String, birthDate: LocalDate? = null, notes: String? = null): FamilyMember {
        val member = FamilyMember(
            name = name.trim(),
            birth_date = birthDate,
            notes = notes?.trim()?.ifBlank { null },
            created_at = LocalDateTime.now()
        )
        member.save()
        log.info("Family member created: id={} name=\"{}\"", member.id, member.name)
        return member
    }

    fun updateMember(id: Long, name: String, birthDate: LocalDate? = null, notes: String? = null): FamilyMember? {
        val member = FamilyMember.findById(id) ?: return null
        member.name = name.trim()
        member.birth_date = birthDate
        member.notes = notes?.trim()?.ifBlank { null }
        member.save()
        log.info("Family member updated: id={} name=\"{}\"", member.id, member.name)
        return member
    }

    fun deleteMember(id: Long) {
        TitleFamilyMember.findAll().filter { it.family_member_id == id }.forEach { it.delete() }
        FamilyMember.deleteById(id)
        log.info("Family member deleted: id={}", id)
    }

    fun getAllMembers(): List<FamilyMember> = FamilyMember.findAll().sortedBy { it.name.lowercase() }

    fun getMembersForTitle(titleId: Long): List<FamilyMember> {
        val memberIds = TitleFamilyMember.findAll()
            .filter { it.title_id == titleId }
            .map { it.family_member_id }
            .toSet()
        if (memberIds.isEmpty()) return emptyList()
        return FamilyMember.findAll().filter { it.id in memberIds }.sortedBy { it.name.lowercase() }
    }

    fun addMemberToTitle(titleId: Long, memberId: Long) {
        val exists = TitleFamilyMember.findAll()
            .any { it.title_id == titleId && it.family_member_id == memberId }
        if (exists) return
        TitleFamilyMember(
            title_id = titleId,
            family_member_id = memberId,
            created_at = LocalDateTime.now()
        ).save()
    }

    fun removeMemberFromTitle(titleId: Long, memberId: Long) {
        TitleFamilyMember.findAll()
            .filter { it.title_id == titleId && it.family_member_id == memberId }
            .forEach { it.delete() }
    }

    fun getTitleIdsForMembers(memberIds: Set<Long>): Set<Long> {
        if (memberIds.isEmpty()) return emptySet()
        return TitleFamilyMember.findAll()
            .filter { it.family_member_id in memberIds }
            .map { it.title_id }
            .toSet()
    }

    /** Returns family_member_id -> count of associated titles. */
    fun getMemberTitleCounts(): Map<Long, Int> {
        return TitleFamilyMember.findAll()
            .groupBy { it.family_member_id }
            .mapValues { it.value.size }
    }

    fun isNameUnique(name: String, excludeId: Long? = null): Boolean {
        val trimmed = name.trim().lowercase()
        return FamilyMember.findAll().none {
            it.name.lowercase() == trimmed && it.id != excludeId
        }
    }
}
