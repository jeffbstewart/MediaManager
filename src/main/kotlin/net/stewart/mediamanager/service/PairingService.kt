package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import net.stewart.mediamanager.entity.*
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.UUID
import javax.imageio.ImageIO

object PairingService {

    private val log = LoggerFactory.getLogger(PairingService::class.java)
    private const val CODE_LENGTH = 6
    private const val CODE_TTL_MINUTES = 5L
    private val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no I/O/0/1 for readability

    /**
     * Creates a new pairing code. The device calls this to start the pairing flow.
     * Returns the code and its expiry time.
     */
    fun createPairCode(deviceName: String = ""): PairCode {
        cleanupExpired()

        val code = generateCode()
        val pairCode = PairCode(
            code = code,
            device_name = deviceName,
            status = PairStatus.PENDING.name,
            expires_at = LocalDateTime.now().plusMinutes(CODE_TTL_MINUTES)
        )
        pairCode.save()
        log.info("Pairing code created: {} (device: '{}')", code, deviceName)
        return pairCode
    }

    /**
     * Checks the status of a pairing code. Returns null if not found or expired.
     * When status is PAIRED, includes the device token for the device to store.
     */
    fun checkStatus(code: String): PairCodeStatus? {
        val pairCode = findActiveCode(code) ?: return null

        if (pairCode.expires_at.isBefore(LocalDateTime.now())) {
            pairCode.status = PairStatus.EXPIRED.name
            pairCode.save()
            return PairCodeStatus("expired", null, null)
        }

        return when (pairCode.status) {
            PairStatus.PAIRED.name -> {
                val user = pairCode.user_id?.let { AppUser.findById(it) }
                val rawToken = pairCode.server_url
                // Single-use: clear the raw token after first retrieval so it can't be
                // polled again by an attacker who guesses/brute-forces the pair code
                if (rawToken.isNotEmpty()) {
                    pairCode.server_url = ""
                    pairCode.save()
                    log.info("Pairing token delivered and cleared for code={}", pairCode.code)
                }
                PairCodeStatus("paired", rawToken.ifEmpty { null }, user?.username)
            }
            else -> PairCodeStatus("pending", null, null)
        }
    }

    data class PairCodeStatus(val status: String, val token: String?, val username: String?)

    /**
     * Confirms a pairing code, linking it to the authenticated user.
     * Creates a device token and marks the pair code as completed.
     * Returns the device name for display, or null if the code is invalid.
     */
    fun confirmPairing(code: String, user: AppUser): String? {
        val pairCode = findActiveCode(code) ?: return null

        if (pairCode.status != PairStatus.PENDING.name) return null
        if (pairCode.expires_at.isBefore(LocalDateTime.now())) return null

        // Create a permanent device token
        val rawToken = UUID.randomUUID().toString()
        val tokenHash = AuthService.hashToken(rawToken)

        DeviceToken(
            token_hash = tokenHash,
            user_id = user.id!!,
            device_name = pairCode.device_name.ifEmpty { "Roku Device" }
        ).save()

        // Mark pair code as completed — store raw token temporarily for the device to retrieve
        pairCode.status = PairStatus.PAIRED.name
        pairCode.user_id = user.id
        pairCode.token_hash = tokenHash
        pairCode.server_url = rawToken // reuse field to pass raw token to polling device
        pairCode.save()

        log.info("Pairing confirmed: code={} user='{}' device='{}'",
            code, user.username, pairCode.device_name)

        return pairCode.device_name.ifEmpty { "Roku Device" }
    }

    /**
     * Generates a QR code PNG encoding the given URL.
     * Returns the PNG bytes.
     */
    fun generateQrCode(url: String, size: Int = 400): ByteArray {
        val writer = QRCodeWriter()
        val hints = mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val bitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, size, size, hints)

        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until size) {
            for (y in 0 until size) {
                image.setRGB(x, y, if (bitMatrix.get(x, y)) 0x000000 else 0xFFFFFF)
            }
        }

        val outputStream = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", outputStream)
        return outputStream.toByteArray()
    }

    /**
     * Validates a device token and returns the associated user.
     * Updates last_used_at on successful validation.
     */
    fun validateDeviceToken(rawToken: String): AppUser? {
        val hash = AuthService.hashToken(rawToken)
        val deviceToken = DeviceToken.findAll()
            .firstOrNull { it.token_hash == hash } ?: return null

        val user = AppUser.findById(deviceToken.user_id) ?: return null

        // Update last-used timestamp
        deviceToken.last_used_at = LocalDateTime.now()
        deviceToken.save()

        return user
    }

    /**
     * Revokes all device tokens for a user. Called on password change.
     */
    fun revokeAllForUser(userId: Long) {
        val deleted = JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
            handle.createUpdate("DELETE FROM device_token WHERE user_id = :uid")
                .bind("uid", userId)
                .execute()
        }
        if (deleted > 0) {
            log.info("AUDIT: Revoked {} device tokens for user='{}'", deleted, AppUser.usernameFor(userId))
        }
    }

    /**
     * Revokes a single device token by ID. Returns true if found and deleted.
     */
    fun revokeToken(tokenId: Long): Boolean {
        val token = DeviceToken.findById(tokenId) ?: return false
        token.delete()
        log.info("AUDIT: Revoked device token id={} for user='{}'", tokenId, AppUser.usernameFor(token.user_id))
        return true
    }

    /**
     * Revokes a single device token by ID, only if it belongs to the specified user.
     * Returns true if found, owned by the user, and deleted.
     */
    fun revokeTokenForUser(tokenId: Long, userId: Long): Boolean {
        val token = DeviceToken.findById(tokenId) ?: return false
        if (token.user_id != userId) return false
        token.delete()
        log.info("AUDIT: Revoked device token id={} for user='{}'", tokenId, AppUser.usernameFor(token.user_id))
        return true
    }

    /**
     * Returns all device tokens for a user, for the Active Sessions view.
     */
    fun getDeviceTokensForUser(userId: Long): List<DeviceToken> {
        return DeviceToken.findAll()
            .filter { it.user_id == userId }
            .sortedByDescending { it.last_used_at }
    }

    /**
     * Returns all session tokens for a user, for the Active Sessions view.
     */
    fun getSessionTokensForUser(userId: Long): List<SessionToken> {
        return SessionToken.findAll()
            .filter { it.user_id == userId && it.expires_at.isAfter(LocalDateTime.now()) }
            .sortedByDescending { it.expires_at }
    }

    /**
     * Returns the count of non-expired PENDING pair codes.
     */
    fun countPendingCodes(): Int {
        val now = LocalDateTime.now()
        return PairCode.findAll().count {
            it.status == PairStatus.PENDING.name && it.expires_at.isAfter(now)
        }
    }

    private fun findActiveCode(code: String): PairCode? {
        return PairCode.findAll().firstOrNull {
            it.code.equals(code, ignoreCase = true)
        }
    }

    private fun generateCode(): String {
        val random = java.security.SecureRandom()
        // Retry on collision (extremely unlikely with 32^6 = 1B possibilities)
        repeat(10) {
            val code = (1..CODE_LENGTH).map { CODE_CHARS[random.nextInt(CODE_CHARS.length)] }.joinToString("")
            if (PairCode.findAll().none { it.code == code && it.status == PairStatus.PENDING.name }) {
                return code
            }
        }
        throw IllegalStateException("Failed to generate unique pair code")
    }

    private fun cleanupExpired() {
        JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
            handle.createUpdate("DELETE FROM pair_code WHERE expires_at < :now")
                .bind("now", LocalDateTime.now())
                .execute()
        }
    }
}
