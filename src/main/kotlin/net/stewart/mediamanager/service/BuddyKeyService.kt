package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.BuddyApiKey
import org.mindrot.jbcrypt.BCrypt
import java.time.LocalDateTime
import java.util.UUID

object BuddyKeyService {

    /**
     * Creates a new buddy API key with the given display name.
     * Returns the raw key (plaintext) — this is the only time it's available.
     * Only the bcrypt hash is stored in the database.
     */
    fun createKey(name: String): String {
        val rawKey = UUID.randomUUID().toString()
        val hash = BCrypt.hashpw(rawKey, BCrypt.gensalt(12))
        BuddyApiKey(
            name = name.trim(),
            key_hash = hash,
            created_at = LocalDateTime.now()
        ).save()
        return rawKey
    }

    /**
     * Validates a provided API key against all stored keys.
     * Returns true if any key matches.
     */
    fun validate(providedKey: String): Boolean {
        val keys = BuddyApiKey.findAll()
        return keys.any { BCrypt.checkpw(providedKey, it.key_hash) }
    }

    /** Returns all keys for display in the admin UI. */
    fun getAllKeys(): List<BuddyApiKey> =
        BuddyApiKey.findAll().sortedByDescending { it.created_at }

    /** Deletes a key permanently by ID. */
    fun deleteKey(id: Long) {
        val key = BuddyApiKey.findById(id) ?: return
        key.delete()
    }

    /** Returns count of keys. */
    fun activeKeyCount(): Int =
        BuddyApiKey.findAll().size
}
