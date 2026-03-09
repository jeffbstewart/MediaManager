package net.stewart.mediamanager

import com.vaadin.flow.server.ServiceInitEvent
import com.vaadin.flow.server.VaadinServiceInitListener
import net.stewart.mediamanager.service.AuthService
import org.slf4j.LoggerFactory

class SecurityServiceInitListener : VaadinServiceInitListener {
    private val log = LoggerFactory.getLogger(SecurityServiceInitListener::class.java)

    private val publicRoutes = setOf("login", "setup", "change-password")
    // Keep sorted alphabetically
    private val adminRoutes = setOf("data-quality", "expand", "family", "import", "manual-entry", "purchase-wishes", "report", "scan", "settings", "tags", "transcodes", "users", "valuation")

    override fun serviceInit(event: ServiceInitEvent) {
        event.source.addUIInitListener { uiEvent ->
            uiEvent.ui.addBeforeEnterListener { beforeEvent ->
                val path = beforeEvent.location.path

                // Public routes always pass through
                if (path in publicRoutes) return@addBeforeEnterListener

                // If no users exist, redirect to setup
                if (!AuthService.hasUsers()) {
                    beforeEvent.forwardTo("setup")
                    return@addBeforeEnterListener
                }

                // Check if user is authenticated (session or cookie restore)
                var user = AuthService.getCurrentUser()
                if (user == null) {
                    user = AuthService.restoreFromCookie()
                }
                if (user == null) {
                    // Save the requested path so LoginView can redirect back after login
                    val fullPath = beforeEvent.location.pathWithQueryParameters
                    if (fullPath.isNotBlank() && fullPath != "login") {
                        uiEvent.ui.session.setAttribute("returnPath", fullPath)
                    }
                    beforeEvent.forwardTo("login")
                    return@addBeforeEnterListener
                }

                // M2 fix: re-fetch from DB to pick up privilege changes (promote/demote/delete)
                val freshUser = AuthService.refreshCurrentUser()
                if (freshUser == null) {
                    // User was deleted — force logout
                    AuthService.logout()
                    beforeEvent.forwardTo("login")
                    return@addBeforeEnterListener
                }
                user = freshUser

                // Force password change intercept
                if (user.must_change_password && path != "change-password") {
                    beforeEvent.forwardTo("change-password")
                    return@addBeforeEnterListener
                }

                // Admin routes require Level 2
                val isAdminRoute = adminRoutes.any { path == it || path.startsWith("$it/") }
                if (isAdminRoute && !user.isAdmin()) {
                    log.warn("User '{}' (level {}) tried to access admin route '{}'",
                        user.username, user.access_level, path)
                    beforeEvent.forwardTo("")
                    return@addBeforeEnterListener
                }
            }
        }
    }
}
