package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.DeviceToken
import net.stewart.mediamanager.entity.SessionToken
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.PairingService
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Unified session item for displaying both browser sessions and device tokens. */
private data class SessionItem(
    val id: Long,
    val type: String,          // "Browser" or "Roku Device"
    val deviceName: String,    // user-agent summary or device name
    val createdAt: LocalDateTime?,
    val lastUsedAt: LocalDateTime?,
    val expiresAt: LocalDateTime?,  // null for device tokens (no expiry)
    val isCurrent: Boolean
)

@Route("sessions", layout = MainLayout::class)
@PageTitle("Active Sessions — Media Manager")
class ActiveSessionsView : KComposite(), BeforeEnterObserver {

    private val log = LoggerFactory.getLogger(ActiveSessionsView::class.java)
    private lateinit var grid: Grid<SessionItem>
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private var targetUserId: Long? = null
    private var isAdminViewing = false

    private val root = ui {
        verticalLayout {
            isPadding = true; isSpacing = true

            horizontalLayout {
                defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                width = "100%"

                val title = Span("Active Sessions").apply {
                    style.set("font-size", "var(--lumo-font-size-xl)")
                    style.set("font-weight", "600")
                }
                add(title)

                val revokeAllBtn = Button("Revoke All Other Sessions", VaadinIcon.CLOSE_CIRCLE.create()) {
                    confirmRevokeAll()
                }.apply {
                    addThemeVariants(ButtonVariant.LUMO_ERROR)
                }
                add(revokeAllBtn)
                expand(title)
            }

            grid = grid {
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

                addColumn { item ->
                    if (item.isCurrent) "${item.deviceName} (this session)" else item.deviceName
                }.setHeader("Device / Browser").setFlexGrow(1)

                addColumn { it.createdAt?.format(dateFmt) ?: "" }
                    .setHeader("Created").setFlexGrow(0).setWidth("160px")

                addColumn { it.lastUsedAt?.format(dateFmt) ?: "" }
                    .setHeader("Last Used").setFlexGrow(0).setWidth("160px")

                addColumn { item ->
                    item.expiresAt?.format(dateFmt) ?: "Never"
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
        val currentUser = AuthService.getCurrentUser() ?: return
        val queryUserId = event.location.queryParameters
            .parameters["userId"]?.firstOrNull()?.toLongOrNull()

        if (queryUserId != null && currentUser.isAdmin() && queryUserId != currentUser.id) {
            targetUserId = queryUserId
            isAdminViewing = true
        } else {
            targetUserId = currentUser.id
            isAdminViewing = false
        }
        refreshGrid()
    }

    private fun refreshGrid() {
        val userId = targetUserId ?: return
        val currentTokenHash = AuthService.getCurrentTokenHash()

        val browserSessions = PairingService.getSessionTokensForUser(userId).map { st ->
            SessionItem(
                id = st.id!!,
                type = "Browser",
                deviceName = summarizeUserAgent(st.user_agent),
                createdAt = st.created_at,
                lastUsedAt = st.last_used_at,
                expiresAt = st.expires_at,
                isCurrent = !isAdminViewing && st.token_hash == currentTokenHash
            )
        }

        val deviceSessions = PairingService.getDeviceTokensForUser(userId).map { dt ->
            SessionItem(
                id = dt.id!!,
                type = "Roku Device",
                deviceName = dt.device_name.ifEmpty { "Unknown Device" },
                createdAt = dt.created_at,
                lastUsedAt = dt.last_used_at,
                expiresAt = null,
                isCurrent = false
            )
        }

        val items = (browserSessions + deviceSessions).sortedByDescending { it.lastUsedAt }
        grid.setItems(items)
    }

    private fun revokeItem(item: SessionItem) {
        val userId = targetUserId ?: return
        val currentTokenHash = if (isAdminViewing) null else AuthService.getCurrentTokenHash()

        when (item.type) {
            "Browser" -> {
                if (AuthService.revokeSession(item.id, currentTokenHash)) {
                    ui.ifPresent { it.access {
                        Notification.show("Session revoked", 2000, Notification.Position.MIDDLE)
                    } }
                } else {
                    ui.ifPresent { it.access {
                        Notification.show("Cannot revoke current session", 2000, Notification.Position.MIDDLE)
                            .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    } }
                }
            }
            "Roku Device" -> {
                PairingService.revokeToken(item.id)
                ui.ifPresent { it.access {
                    Notification.show("Device token revoked", 2000, Notification.Position.MIDDLE)
                } }
            }
        }
        refreshGrid()
    }

    private fun confirmRevokeAll() {
        val userId = targetUserId ?: return
        val dialog = Dialog()
        dialog.headerTitle = "Revoke All Other Sessions"
        dialog.add(Span("This will sign out all other browser sessions and unpair all Roku devices. Continue?"))
        dialog.footer.add(
            Button("Cancel") { dialog.close() },
            Button("Revoke All") {
                val currentTokenHash = if (isAdminViewing) null else AuthService.getCurrentTokenHash()
                AuthService.revokeAllSessionsExceptCurrent(userId, currentTokenHash)
                PairingService.revokeAllForUser(userId)
                val currentUser = AuthService.getCurrentUser()
                log.info("AUDIT: All other sessions revoked for user_id={} by '{}'",
                    userId, currentUser?.username)
                refreshGrid()
                dialog.close()
                ui.ifPresent { it.access {
                    Notification.show("All other sessions revoked", 3000, Notification.Position.MIDDLE)
                } }
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR) }
        )
        dialog.open()
    }

    companion object {
        fun summarizeUserAgent(ua: String): String {
            if (ua.isBlank()) return "Unknown Browser"

            // Extract browser name
            val browser = when {
                ua.contains("Edg/", ignoreCase = true) -> "Edge"
                ua.contains("Chrome/", ignoreCase = true) && !ua.contains("Edg/", ignoreCase = true) -> "Chrome"
                ua.contains("Firefox/", ignoreCase = true) -> "Firefox"
                ua.contains("Safari/", ignoreCase = true) && !ua.contains("Chrome/", ignoreCase = true) -> "Safari"
                else -> "Browser"
            }

            // Extract OS
            val os = when {
                ua.contains("Windows", ignoreCase = true) -> "Windows"
                ua.contains("Mac OS", ignoreCase = true) -> "macOS"
                ua.contains("iPhone", ignoreCase = true) -> "iPhone"
                ua.contains("iPad", ignoreCase = true) -> "iPad"
                ua.contains("Android", ignoreCase = true) -> "Android"
                ua.contains("Linux", ignoreCase = true) -> "Linux"
                else -> null
            }

            return if (os != null) "$browser on $os" else browser
        }
    }
}
