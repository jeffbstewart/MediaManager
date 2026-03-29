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
import com.gitlab.mvysny.jdbiorm.JdbiOrm
import net.stewart.mediamanager.entity.AppUser
import java.time.LocalDateTime
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.PasswordService
import org.slf4j.LoggerFactory

@Route("setup")
@PageTitle("Setup — Media Manager")
class SetupView : VerticalLayout(), BeforeEnterObserver {

    private val log = LoggerFactory.getLogger(SetupView::class.java)

    init {
        setSizeFull()
        justifyContentMode = FlexComponent.JustifyContentMode.CENTER
        defaultHorizontalComponentAlignment = FlexComponent.Alignment.CENTER
        maxWidth = "400px"
        style.set("margin", "0 auto")

        add(H2("Welcome to Media Manager"))
        add(Paragraph("Create your administrator account to get started."))

        val usernameField = TextField("Username").apply { width = "100%" }
        val displayNameField = TextField("Display Name").apply { width = "100%" }
        val passwordField = PasswordField("Password").apply { width = "100%" }
        val confirmField = PasswordField("Confirm Password").apply { width = "100%" }

        val createButton = Button("Create Account").apply {
            width = "100%"
            addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            addClickListener {
                val username = usernameField.value.trim()
                val displayName = displayNameField.value.trim()
                val password = passwordField.value
                val confirm = confirmField.value

                if (username.isBlank()) {
                    Notification.show("Username is required", 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@addClickListener
                }
                if (displayName.isBlank()) {
                    Notification.show("Display name is required", 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@addClickListener
                }
                val violations = PasswordService.validate(password, username)
                if (violations.isNotEmpty()) {
                    Notification.show(violations.first(), 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@addClickListener
                }
                if (password != confirm) {
                    Notification.show("Passwords do not match", 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@addClickListener
                }

                // Atomic check-and-create inside a transaction to prevent race condition
                val now = LocalDateTime.now()
                val user = try {
                    JdbiOrm.jdbi().inTransaction<AppUser, Exception> { handle ->
                        val count = handle.createQuery("SELECT COUNT(*) FROM app_user")
                            .mapTo(Int::class.java).one()
                        if (count > 0) throw IllegalStateException("Setup already complete")
                        val u = AppUser(
                            username = username,
                            display_name = displayName,
                            password_hash = PasswordService.hash(password),
                            access_level = 2, // Admin
                            created_at = now,
                            updated_at = now
                        )
                        u.save()
                        AuthService.invalidateHasUsersCache()
                        u
                    }
                } catch (e: IllegalStateException) {
                    Notification.show("Setup already complete", 3000, Notification.Position.MIDDLE)
                    UI.getCurrent().navigate("login")
                    return@addClickListener
                }
                log.info("AUDIT: First admin '{}' created via setup", username)

                // Auto-login
                val token = AuthService.establishSession(user)
                AuthService.setSessionCookie(token)
                UI.getCurrent().navigate("")
            }
        }

        add(usernameField, displayNameField, passwordField, confirmField, createButton)
    }

    override fun beforeEnter(event: BeforeEnterEvent) {
        // If users already exist, setup is disabled
        if (AuthService.hasUsers()) {
            event.forwardTo("login")
        }
    }
}
