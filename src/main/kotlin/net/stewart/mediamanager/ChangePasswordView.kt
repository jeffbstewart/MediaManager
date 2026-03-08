package net.stewart.mediamanager

import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.PasswordField
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.PasswordService
import org.slf4j.LoggerFactory

@Route("change-password")
@PageTitle("Change Password — Media Manager")
class ChangePasswordView : VerticalLayout(), BeforeEnterObserver {

    private val log = LoggerFactory.getLogger(ChangePasswordView::class.java)
    private var passwordAttempts = 0
    private val maxPasswordAttempts = 5
    private val subtitle: Paragraph
    private var forced = false

    init {
        setSizeFull()
        justifyContentMode = FlexComponent.JustifyContentMode.CENTER
        defaultHorizontalComponentAlignment = FlexComponent.Alignment.CENTER
        maxWidth = "400px"
        style.set("margin", "0 auto")

        add(H2("Change Your Password"))
        subtitle = Paragraph()
        add(subtitle)

        val user = AuthService.getCurrentUser()
        forced = user?.must_change_password == true
        subtitle.text = if (forced) "Your account requires a password change before continuing."
            else "Enter your current password and choose a new one."

        val usernameField = TextField("Username").apply {
            width = "100%"
            value = user?.username ?: ""
            isReadOnly = true
        }
        val currentPwField = PasswordField("Current Password").apply { width = "100%" }
        val newPwField = PasswordField("New Password").apply {
            width = "100%"
            helperText = "At least ${PasswordService.MIN_PASSWORD_LENGTH} characters, cannot match username"
        }
        val confirmPwField = PasswordField("Confirm New Password").apply { width = "100%" }

        val changeButton = Button("Change Password").apply {
            width = "100%"
            isEnabled = false
            addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            addClickListener {
                val currentUser = AuthService.getCurrentUser()
                if (currentUser == null) {
                    UI.getCurrent().navigate("login")
                    return@addClickListener
                }

                val currentPw = currentPwField.value
                val newPw = newPwField.value
                val confirmPw = confirmPwField.value

                if (passwordAttempts >= maxPasswordAttempts) {
                    Notification.show("Too many failed attempts. Please log out and try again.", 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@addClickListener
                }
                if (!PasswordService.verify(currentPw, currentUser.password_hash)) {
                    passwordAttempts++
                    val remaining = maxPasswordAttempts - passwordAttempts
                    val msg = if (remaining > 0) "Current password is incorrect ($remaining attempts remaining)"
                        else "Too many failed attempts. Please log out and try again."
                    Notification.show(msg, 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@addClickListener
                }

                val violations = PasswordService.validate(newPw, currentUser.username, currentUser.password_hash)
                if (violations.isNotEmpty()) {
                    Notification.show(violations.first(), 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@addClickListener
                }
                if (newPw != confirmPw) {
                    Notification.show("Passwords do not match", 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@addClickListener
                }

                // Re-fetch from DB to avoid stale data
                val fresh = net.stewart.mediamanager.entity.AppUser.findById(currentUser.id!!)
                if (fresh == null) {
                    UI.getCurrent().navigate("login")
                    return@addClickListener
                }
                fresh.password_hash = PasswordService.hash(newPw)
                fresh.must_change_password = false
                fresh.updated_at = java.time.LocalDateTime.now()
                fresh.save()

                // Invalidate all sessions (logs out other devices), then establish a new one for this session
                AuthService.invalidateUserSessions(fresh.id!!)
                val newToken = AuthService.establishSession(fresh)
                AuthService.setSessionCookie(newToken)

                log.info("AUDIT: Password changed by user '{}' — all sessions invalidated", fresh.username)
                Notification.show("Password changed successfully", 3000, Notification.Position.MIDDLE)
                UI.getCurrent().navigate("")
            }
        }

        // Live validation: enable button only when all fields are filled and passwords match
        val validate = {
            val hasCurrentPw = currentPwField.value.isNotEmpty()
            val newPw = newPwField.value
            val confirmPw = confirmPwField.value
            val newLongEnough = newPw.length in PasswordService.MIN_PASSWORD_LENGTH..PasswordService.MAX_PASSWORD_LENGTH
            val passwordsMatch = newPw == confirmPw

            newPwField.isInvalid = newPw.isNotEmpty() && !newLongEnough
            newPwField.errorMessage = if (newPwField.isInvalid) "Must be ${PasswordService.MIN_PASSWORD_LENGTH}–${PasswordService.MAX_PASSWORD_LENGTH} characters" else null
            confirmPwField.isInvalid = confirmPw.isNotEmpty() && !passwordsMatch
            confirmPwField.errorMessage = if (confirmPwField.isInvalid) "Passwords do not match" else null

            changeButton.isEnabled = hasCurrentPw && newLongEnough && passwordsMatch
        }
        currentPwField.addValueChangeListener { validate() }
        newPwField.addValueChangeListener { validate() }
        confirmPwField.addValueChangeListener { validate() }

        add(usernameField, currentPwField, newPwField, confirmPwField, changeButton)
    }

    override fun beforeEnter(event: BeforeEnterEvent) {
        val user = AuthService.getCurrentUser() ?: AuthService.restoreFromCookie()
        if (user == null) {
            event.forwardTo("login")
        }
        // Allow both forced and voluntary access — no redirect when must_change_password is false
    }
}
