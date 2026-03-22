package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.findAll
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Hr
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.textfield.PasswordField
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.ContentRating
import net.stewart.mediamanager.entity.LiveTvTuner
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.DisplayTimezone
import net.stewart.mediamanager.service.PairingService
import net.stewart.mediamanager.service.PasswordService
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private data class ProfileSessionItem(
    val id: Long,
    val type: String,
    val deviceName: String,
    val rawUserAgent: String,
    val createdAt: LocalDateTime?,
    val lastUsedAt: LocalDateTime?,
    val expiresAt: LocalDateTime?,
    val isCurrent: Boolean
)

@Route("profile", layout = MainLayout::class)
@PageTitle("Profile")
class ProfileView : KComposite(), BeforeEnterObserver {

    private val log = LoggerFactory.getLogger(ProfileView::class.java)
    @Suppress("unused") private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private lateinit var sessionGrid: Grid<ProfileSessionItem>
    private var passwordAttempts = 0
    private val maxPasswordAttempts = 5

    private val root = ui {
        verticalLayout {
            isPadding = true
            isSpacing = true
            maxWidth = "800px"
            style.set("margin", "0 auto")

            val user = AuthService.getCurrentUser()

            // ── Account Info ──
            span("Account") {
                style.set("font-size", "var(--lumo-font-size-xl)")
                style.set("font-weight", "600")
            }

            horizontalLayout {
                isSpacing = true
                defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE

                span("Username: ") { style.set("color", "var(--lumo-secondary-text-color)") }
                span(user?.username ?: "") { style.set("font-weight", "500") }
            }

            horizontalLayout {
                isSpacing = true
                defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE

                span("Display Name: ") { style.set("color", "var(--lumo-secondary-text-color)") }
                span(user?.display_name ?: "") { style.set("font-weight", "500") }
            }

            horizontalLayout {
                isSpacing = true
                defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE

                span("Role: ") { style.set("color", "var(--lumo-secondary-text-color)") }
                span(if (user?.isAdmin() == true) "Admin" else "Viewer") { style.set("font-weight", "500") }
            }

            // Content rating ceiling (read-only, admin-enforced)
            val ceilingLevel = user?.rating_ceiling
            horizontalLayout {
                isSpacing = true
                defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE

                span("Content Rating Limit: ") { style.set("color", "var(--lumo-secondary-text-color)") }
                val label = if (ceilingLevel == null) "Unrestricted" else ContentRating.ceilingLabel(ceilingLevel)
                span(label) { style.set("font-weight", "500") }
                span("(set by admin)") {
                    style.set("color", "var(--lumo-tertiary-text-color)")
                    style.set("font-size", "var(--lumo-font-size-xs)")
                }
            }

            // ── Live TV Quality Filter (only if tuners configured) ──
            val hasTuners = LiveTvTuner.findAll().any { it.enabled }
            if (hasTuners && user != null) {
                add(Hr().apply {
                    style.set("margin", "var(--lumo-space-m) 0")
                })

                span("Live TV") {
                    style.set("font-size", "var(--lumo-font-size-xl)")
                    style.set("font-weight", "600")
                }

                horizontalLayout {
                    isSpacing = true
                    defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE

                    span("Minimum channel quality: ") { style.set("color", "var(--lumo-secondary-text-color)") }

                    val qualityCombo = ComboBox<Int>().apply {
                        width = "200px"
                        setItems(1, 2, 3, 4, 5)
                        setItemLabelGenerator { stars ->
                            val label = when (stars) {
                                1 -> "1 star (all channels)"
                                5 -> "5 stars (best only)"
                                else -> "$stars stars"
                            }
                            label
                        }
                        value = user.live_tv_min_quality
                        addValueChangeListener { event ->
                            val newVal = event.value ?: return@addValueChangeListener
                            val fresh = AppUser.findById(user.id!!) ?: return@addValueChangeListener
                            fresh.live_tv_min_quality = newVal
                            fresh.save()
                            Notification.show("Quality filter updated", 2000, Notification.Position.BOTTOM_START)
                                .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                        }
                    }
                    add(qualityCombo)
                }

                span("Channels rated below your threshold are hidden from the Live TV viewer. An admin rates each channel's reception quality in Live TV Settings.") {
                    style.set("color", "var(--lumo-secondary-text-color)")
                    style.set("font-size", "var(--lumo-font-size-s)")
                }
            }

            // ── Change Password ──
            add(Hr().apply {
                style.set("margin", "var(--lumo-space-m) 0")
            })

            span("Change Password") {
                style.set("font-size", "var(--lumo-font-size-xl)")
                style.set("font-weight", "600")
            }

            val currentPwField = PasswordField("Current Password").apply { width = "100%" }
            val newPwField = PasswordField("New Password").apply {
                width = "100%"
                helperText = "At least ${PasswordService.MIN_PASSWORD_LENGTH} characters, cannot match username"
            }
            val confirmPwField = PasswordField("Confirm New Password").apply { width = "100%" }

            val changeButton = Button("Change Password").apply {
                addThemeVariants(ButtonVariant.LUMO_PRIMARY)
                isEnabled = false
                addClickListener {
                    val currentUser = AuthService.getCurrentUser()
                    if (currentUser == null) {
                        UI.getCurrent().navigate("login")
                        return@addClickListener
                    }

                    if (passwordAttempts >= maxPasswordAttempts) {
                        Notification.show("Too many failed attempts. Please log out and try again.", 3000, Notification.Position.MIDDLE)
                            .addThemeVariants(NotificationVariant.LUMO_ERROR)
                        return@addClickListener
                    }
                    if (!PasswordService.verify(currentPwField.value, currentUser.password_hash)) {
                        passwordAttempts++
                        val remaining = maxPasswordAttempts - passwordAttempts
                        val msg = if (remaining > 0) "Current password is incorrect ($remaining attempts remaining)"
                            else "Too many failed attempts. Please log out and try again."
                        Notification.show(msg, 3000, Notification.Position.MIDDLE)
                            .addThemeVariants(NotificationVariant.LUMO_ERROR)
                        return@addClickListener
                    }

                    val violations = PasswordService.validate(newPwField.value, currentUser.username, currentUser.password_hash)
                    if (violations.isNotEmpty()) {
                        Notification.show(violations.first(), 3000, Notification.Position.MIDDLE)
                            .addThemeVariants(NotificationVariant.LUMO_ERROR)
                        return@addClickListener
                    }
                    if (newPwField.value != confirmPwField.value) {
                        Notification.show("Passwords do not match", 3000, Notification.Position.MIDDLE)
                            .addThemeVariants(NotificationVariant.LUMO_ERROR)
                        return@addClickListener
                    }

                    val fresh = AppUser.findById(currentUser.id!!)
                    if (fresh == null) {
                        UI.getCurrent().navigate("login")
                        return@addClickListener
                    }
                    fresh.password_hash = PasswordService.hash(newPwField.value)
                    fresh.must_change_password = false
                    fresh.updated_at = LocalDateTime.now()
                    fresh.save()

                    AuthService.invalidateUserSessions(fresh.id!!)
                    val newToken = AuthService.establishSession(fresh)
                    AuthService.setSessionCookie(newToken)

                    log.info("AUDIT: Password changed by user '{}' — all sessions invalidated", fresh.username)
                    currentPwField.clear()
                    newPwField.clear()
                    confirmPwField.clear()
                    Notification.show("Password changed — all other sessions signed out", 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                    refreshSessionGrid()
                }
            }

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

            add(currentPwField, newPwField, confirmPwField, changeButton)

            // ── Active Sessions ──
            add(Hr().apply {
                style.set("margin", "var(--lumo-space-m) 0")
            })

            horizontalLayout {
                width = "100%"
                defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER

                span("Active Sessions") {
                    style.set("font-size", "var(--lumo-font-size-xl)")
                    style.set("font-weight", "600")
                    style.set("flex-grow", "1")
                }
                button("Revoke All Other Sessions", VaadinIcon.CLOSE_CIRCLE.create()) {
                    onLeftClick { confirmRevokeAll() }
                    addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR)
                }
            }

            sessionGrid = grid {
                width = "100%"

                addComponentColumn { item ->
                    Span(item.type).apply {
                        val bg = if (item.type == "Browser") "var(--lumo-primary-color)" else "#9c27b0"
                        style.set("background-color", bg)
                        style.set("color", "white")
                        style.set("padding", "2px 8px")
                        style.set("border-radius", "var(--lumo-border-radius-s)")
                        style.set("font-size", "var(--lumo-font-size-xs)")
                        style.set("font-weight", "600")
                    }
                }.setHeader("Type").setFlexGrow(0).setWidth("120px")

                addComponentColumn { item ->
                    val label = if (item.isCurrent) "${item.deviceName} (this session)" else item.deviceName
                    Span(label).apply {
                        if (item.rawUserAgent.isNotBlank()) {
                            element.setAttribute("title", item.rawUserAgent)
                        }
                    }
                }.setHeader("Device / Browser").setFlexGrow(1)

                addColumn { DisplayTimezone.formatFull(it.createdAt) }
                    .setHeader("Created").setFlexGrow(0).setWidth("160px")

                addColumn { DisplayTimezone.formatFull(it.lastUsedAt) }
                    .setHeader("Last Used").setFlexGrow(0).setWidth("160px")

                addColumn { item ->
                    DisplayTimezone.formatFull(item.expiresAt, "Never")
                }.setHeader("Expires").setFlexGrow(0).setWidth("160px")

                addComponentColumn { item ->
                    if (item.isCurrent) {
                        Span("Current").apply {
                            style.set("color", "var(--lumo-success-color)")
                            style.set("font-weight", "600")
                            style.set("font-size", "var(--lumo-font-size-s)")
                        }
                    } else {
                        Button("Revoke", VaadinIcon.CLOSE_SMALL.create()) {
                            revokeItem(item)
                        }.apply {
                            addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR)
                        }
                    }
                }.setHeader("").setFlexGrow(0).setWidth("120px")
            }
        }
    }

    override fun beforeEnter(event: BeforeEnterEvent) {
        val user = AuthService.getCurrentUser() ?: AuthService.restoreFromCookie()
        if (user == null) {
            event.forwardTo("login")
            return
        }
        refreshSessionGrid()
    }

    private fun refreshSessionGrid() {
        val userId = AuthService.getCurrentUser()?.id ?: return
        val currentTokenHash = AuthService.getCurrentTokenHash()

        val browserSessions = PairingService.getSessionTokensForUser(userId).map { st ->
            ProfileSessionItem(
                id = st.id!!,
                type = "Browser",
                deviceName = ActiveSessionsView.summarizeUserAgent(st.user_agent),
                rawUserAgent = st.user_agent,
                createdAt = st.created_at,
                lastUsedAt = st.last_used_at,
                expiresAt = st.expires_at,
                isCurrent = st.token_hash == currentTokenHash
            )
        }

        val deviceSessions = PairingService.getDeviceTokensForUser(userId).map { dt ->
            ProfileSessionItem(
                id = dt.id!!,
                type = "Roku Device",
                deviceName = dt.device_name.ifEmpty { "Unknown Device" },
                rawUserAgent = "",
                createdAt = dt.created_at,
                lastUsedAt = dt.last_used_at,
                expiresAt = null,
                isCurrent = false
            )
        }

        sessionGrid.setItems((browserSessions + deviceSessions).sortedByDescending { it.lastUsedAt })
    }

    private fun revokeItem(item: ProfileSessionItem) {
        val currentTokenHash = AuthService.getCurrentTokenHash()
        when (item.type) {
            "Browser" -> {
                if (AuthService.revokeSession(item.id, currentTokenHash)) {
                    Notification.show("Session revoked", 2000, Notification.Position.BOTTOM_START)
                } else {
                    Notification.show("Cannot revoke current session", 2000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                }
            }
            "Roku Device" -> {
                PairingService.revokeToken(item.id)
                Notification.show("Device token revoked", 2000, Notification.Position.BOTTOM_START)
            }
        }
        refreshSessionGrid()
    }

    private fun confirmRevokeAll() {
        val userId = AuthService.getCurrentUser()?.id ?: return
        val dialog = Dialog()
        dialog.headerTitle = "Revoke All Other Sessions"
        dialog.add(Span("This will sign out all other browser sessions and unpair all Roku devices. Continue?"))
        dialog.footer.add(
            Button("Cancel") { dialog.close() },
            Button("Revoke All") {
                val currentTokenHash = AuthService.getCurrentTokenHash()
                AuthService.revokeAllSessionsExceptCurrent(userId, currentTokenHash)
                PairingService.revokeAllForUser(userId)
                log.info("AUDIT: All other sessions revoked for user_id={} by '{}'",
                    userId, AuthService.getCurrentUser()?.username)
                refreshSessionGrid()
                dialog.close()
                Notification.show("All other sessions revoked", 3000, Notification.Position.BOTTOM_START)
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR) }
        )
        dialog.open()
    }
}
