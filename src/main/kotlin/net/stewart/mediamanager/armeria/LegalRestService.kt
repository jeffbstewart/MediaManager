package net.stewart.mediamanager.armeria

import com.google.gson.Gson
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Post
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.service.LegalRequirements
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * REST endpoints for the legal terms agreement flow.
 * These are behind ArmeriaAuthDecorator (user must be authenticated)
 * but exempt from legal compliance checks (to avoid redirect loops).
 */
@Blocking
class LegalRestService {

    private val log = LoggerFactory.getLogger(LegalRestService::class.java)
    private val gson = Gson()

    /**
     * Returns the current user's legal compliance status and required versions.
     * Used by the Angular auth guard and terms agreement page.
     */
    @Get("/api/v2/legal/status")
    fun status(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val requiredTou = LegalRequirements.webTermsOfUseVersion
        val compliant = LegalRequirements.isCompliant(user.id!!, user.isAdmin(), requiredTou)

        val body = mapOf(
            "compliant" to compliant,
            "required_privacy_policy_version" to LegalRequirements.privacyPolicyVersion,
            "required_terms_of_use_version" to requiredTou,
            "agreed_privacy_policy_version" to user.privacy_policy_version,
            "agreed_terms_of_use_version" to user.terms_of_use_version,
            "privacy_policy_url" to LegalRequirements.privacyPolicyUrl,
            "terms_of_use_url" to LegalRequirements.webTermsOfUseUrl
        )

        return jsonResponse(gson.toJson(body))
    }

    /**
     * Records the user's agreement to the current legal terms.
     * The client must send the exact version numbers that match the current required versions.
     */
    @Post("/api/v2/legal/agree")
    fun agree(ctx: ServiceRequestContext): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)

        val body = ctx.request().aggregate().join().contentUtf8()
        val map = gson.fromJson(body, Map::class.java)
        val ppVersion = (map["privacy_policy_version"] as? Number)?.toInt()
        val touVersion = (map["terms_of_use_version"] as? Number)?.toInt()

        // Validate that the submitted versions exactly match current requirements.
        // Exact match prevents a client from pre-agreeing to future versions it hasn't seen.
        val requiredPp = LegalRequirements.privacyPolicyVersion
        val requiredTou = LegalRequirements.webTermsOfUseVersion

        if (requiredPp > 0 && ppVersion != requiredPp) {
            return badRequest("Privacy policy version must be $requiredPp")
        }
        if (requiredTou > 0 && touVersion != requiredTou) {
            return badRequest("Terms of use version must be $requiredTou")
        }

        // Update user record
        val fresh = AppUser.findById(user.id!!)
            ?: return HttpResponse.of(HttpStatus.NOT_FOUND)

        val now = LocalDateTime.now()
        if (ppVersion != null && (fresh.privacy_policy_version == null || ppVersion > fresh.privacy_policy_version!!)) {
            fresh.privacy_policy_version = ppVersion
            fresh.privacy_policy_accepted_at = now
        }
        if (touVersion != null && (fresh.terms_of_use_version == null || touVersion > fresh.terms_of_use_version!!)) {
            fresh.terms_of_use_version = touVersion
            fresh.terms_of_use_accepted_at = now
        }
        fresh.save()

        LegalRequirements.recordAgreement(user.id!!, fresh.privacy_policy_version, fresh.terms_of_use_version)

        log.info("AUDIT: User '{}' agreed to legal terms (pp={}, tou={})", fresh.username, ppVersion, touVersion)
        return jsonResponse(gson.toJson(mapOf("ok" to true)))
    }

    private fun jsonResponse(json: String): HttpResponse {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }

    private fun badRequest(msg: String): HttpResponse {
        val bytes = gson.toJson(mapOf("error" to msg)).toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }
}
