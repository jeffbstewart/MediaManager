package net.stewart.mediamanager.service

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Mints + validates short-lived signed tokens for the unauthenticated
 * `/public/album-art/{token}` endpoint.
 *
 * Why a token at all: iOS / macOS render lock-screen now-playing artwork
 * via an OS-level fetch that doesn't share the browser's cookies or JWT
 * header, so a normal authenticated `/posters/...` URL returns 401 and
 * the lock screen falls back to a generic icon. The endpoint has to be
 * reachable without auth, but we don't want to open the entire poster
 * catalogue to anyone who can guess `/posters/w500/42` — that would leak
 * who-owns-what. So we sign a short-lived token that grants access to a
 * single album's art and nothing else.
 *
 * Token shape: `base64url(payload).base64url(hmac)`, payload is JSON
 * `{titleId, exp}`. HMAC-SHA256 keyed off the existing JWT signing key
 * (same threat model as the WebAuthn challenge tokens — no need for a
 * separate secret to manage). Tokens last 12 hours so a single sign-in
 * carries comfortably across a day's listening session without the
 * web-app having to refresh mid-track.
 */
object PublicArtTokenService {

    private val log = LoggerFactory.getLogger(PublicArtTokenService::class.java)
    private val gson = Gson()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder = Base64.getUrlDecoder()

    /** 12-hour validity. */
    const val TOKEN_TTL_SECONDS: Long = 12 * 60 * 60

    fun mint(titleId: Long): String {
        val payload = JsonObject().apply {
            addProperty("titleId", titleId)
            addProperty("exp", Instant.now().epochSecond + TOKEN_TTL_SECONDS)
        }
        val payloadBytes = payload.toString().toByteArray()
        val mac = hmacSha256(payloadBytes)
        return urlEncoder.encodeToString(payloadBytes) + "." + urlEncoder.encodeToString(mac)
    }

    /** Returns the title id encoded in the token, or null if invalid / expired. */
    fun validate(token: String): Long? {
        try {
            val parts = token.split(".", limit = 2)
            if (parts.size != 2) return null
            val payloadBytes = urlDecoder.decode(parts[0])
            val claimedMac = urlDecoder.decode(parts[1])
            val expectedMac = hmacSha256(payloadBytes)
            if (!constantTimeEquals(claimedMac, expectedMac)) return null
            val payload = gson.fromJson(String(payloadBytes), JsonObject::class.java)
            val exp = payload.getAsJsonPrimitive("exp").asLong
            if (Instant.now().epochSecond >= exp) return null
            return payload.getAsJsonPrimitive("titleId").asLong
        } catch (e: Exception) {
            log.debug("Invalid public-art token: {}", e.message)
            return null
        }
    }

    private fun hmacSha256(data: ByteArray): ByteArray {
        val key = JwtService.getSigningKeyBytes()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].toInt() xor b[i].toInt())
        return result == 0
    }
}
