package net.stewart.mediamanager

import com.vaadin.flow.component.UI
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.login.LoginForm
import com.vaadin.flow.component.login.LoginI18n
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import com.vaadin.flow.server.VaadinServletRequest
import com.vaadin.flow.server.VaadinSession
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.LegalRequirements
import net.stewart.mediamanager.service.LoginResult
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

@Route("login")
@PageTitle("Login — Media Manager")
class LoginView : VerticalLayout(), BeforeEnterObserver {

    private val log = LoggerFactory.getLogger(LoginView::class.java)
    private val loginForm = LoginForm()
    private var legalCheckbox: Checkbox? = null

    init {
        setSizeFull()
        justifyContentMode = FlexComponent.JustifyContentMode.CENTER
        defaultHorizontalComponentAlignment = FlexComponent.Alignment.CENTER

        loginForm.isForgotPasswordButtonVisible = false
        loginForm.addLoginListener { event ->
            // Block login if legal agreement is required but not checked
            if (legalCheckbox != null && !legalCheckbox!!.value) {
                val i18n = LoginI18n.createDefault()
                i18n.errorMessage = LoginI18n.ErrorMessage().apply {
                    title = "Agreement required"
                    message = "You must agree to the Privacy Policy and Terms of Use to continue."
                }
                loginForm.setI18n(i18n)
                loginForm.isError = true
                return@addLoginListener
            }

            val ip = (VaadinServletRequest.getCurrent()?.httpServletRequest?.remoteAddr) ?: "unknown"
            when (val result = AuthService.login(event.username, event.password, ip)) {
                is LoginResult.Success -> {
                    // Record legal agreement if versions are configured
                    recordLegalAgreement(result.user)

                    val token = AuthService.establishSession(result.user)
                    AuthService.setSessionCookie(token)
                    if (result.user.must_change_password) {
                        UI.getCurrent().navigate("change-password")
                    } else {
                        val returnPath = VaadinSession.getCurrent()?.getAttribute("returnPath") as? String
                        VaadinSession.getCurrent()?.setAttribute("returnPath", null)
                        UI.getCurrent().navigate(returnPath ?: "")
                    }
                }
                is LoginResult.Failed -> {
                    loginForm.isError = true
                }
                is LoginResult.RateLimited -> {
                    val i18n = LoginI18n.createDefault()
                    i18n.errorMessage = LoginI18n.ErrorMessage().apply {
                        title = "Too many attempts"
                        message = "Too many failed attempts. Try again in ${result.retryAfterSeconds} seconds."
                    }
                    loginForm.setI18n(i18n)
                    loginForm.isError = true
                }
            }
        }

        add(loginForm)

        // Add legal agreement checkbox and links if configured
        val ppUrl = LegalRequirements.privacyPolicyUrl
        val webTouUrl = LegalRequirements.webTermsOfUseUrl
        if (ppUrl != null || webTouUrl != null) {
            val legalLayout = VerticalLayout().apply {
                isPadding = false
                isSpacing = false
                defaultHorizontalComponentAlignment = FlexComponent.Alignment.CENTER
                style.set("max-width", "360px")
            }

            legalCheckbox = Checkbox()
            val row = HorizontalLayout().apply {
                isSpacing = false
                defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                style.set("gap", "var(--lumo-space-xs)")
                style.set("flex-wrap", "wrap")
            }
            row.add(legalCheckbox)
            row.add(Span("I agree to the "))
            if (ppUrl != null) {
                row.add(Anchor(ppUrl, "Privacy Policy").apply {
                    setTarget("_blank")
                })
            }
            if (ppUrl != null && webTouUrl != null) {
                row.add(Span(" and "))
            }
            if (webTouUrl != null) {
                row.add(Anchor(webTouUrl, "Terms of Use").apply {
                    setTarget("_blank")
                })
            }
            legalLayout.add(row)

            add(legalLayout)
        }
    }

    override fun beforeEnter(event: BeforeEnterEvent) {
        // Already logged in? Go home.
        if (AuthService.getCurrentUser() != null) {
            event.forwardTo("")
        }
    }

    private fun recordLegalAgreement(user: net.stewart.mediamanager.entity.AppUser) {
        val now = LocalDateTime.now()
        var changed = false

        val requiredPp = LegalRequirements.privacyPolicyVersion
        if (requiredPp > 0 && (user.privacy_policy_version == null || user.privacy_policy_version!! < requiredPp)) {
            user.privacy_policy_version = requiredPp
            user.privacy_policy_accepted_at = now
            changed = true
        }

        val requiredTou = LegalRequirements.webTermsOfUseVersion
        if (requiredTou > 0 && (user.terms_of_use_version == null || user.terms_of_use_version!! < requiredTou)) {
            user.terms_of_use_version = requiredTou
            user.terms_of_use_accepted_at = now
            changed = true
        }

        if (changed) {
            user.save()
            LegalRequirements.recordAgreement(user.id!!, user.privacy_policy_version, user.terms_of_use_version)
        }
    }
}
