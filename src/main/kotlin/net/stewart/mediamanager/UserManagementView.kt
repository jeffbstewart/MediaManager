package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.findAll
import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.PasswordField
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.ContentRating
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.PasswordService
import org.slf4j.LoggerFactory
import java.time.format.DateTimeFormatter

@Route("users", layout = MainLayout::class)
@PageTitle("Users — Media Manager")
class UserManagementView : KComposite() {

    private val log = LoggerFactory.getLogger(UserManagementView::class.java)
    private lateinit var grid: Grid<AppUser>
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private val root = ui {
        verticalLayout {
            isPadding = true; isSpacing = true

            horizontalLayout {
                defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                width = "100%"
                val title = Span("User Management").apply {
                    style.set("font-size", "var(--lumo-font-size-xl)")
                    style.set("font-weight", "600")
                }
                add(title)

                val addBtn = Button("Add User", VaadinIcon.PLUS.create()) {
                    openAddUserDialog()
                }.apply {
                    addThemeVariants(ButtonVariant.LUMO_PRIMARY)
                }
                add(addBtn)
                expand(title)
            }

            grid = grid<AppUser> {
                width = "100%"

                addColumn { it.username }.setHeader("Username").setSortable(true)
                addColumn { it.display_name }.setHeader("Display Name").setSortable(true)
                addComponentColumn { user ->
                    HorizontalLayout().apply {
                        isSpacing = true
                        defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                        add(Span(if (user.isAdmin()) "Admin" else "Viewer"))
                        if (user.locked) {
                            add(Span("Locked").apply {
                                style.set("background-color", "var(--lumo-error-color)")
                                style.set("color", "var(--lumo-error-contrast-color, white)")
                                style.set("padding", "2px 6px")
                                style.set("border-radius", "var(--lumo-border-radius-s)")
                                style.set("font-size", "var(--lumo-font-size-xs)")
                                style.set("font-weight", "600")
                            })
                        }
                        if (user.must_change_password) {
                            add(Span("PW Change").apply {
                                style.set("background-color", "var(--lumo-warning-color, #e7c200)")
                                style.set("color", "var(--lumo-warning-contrast-color, #1a1a1a)")
                                style.set("padding", "2px 6px")
                                style.set("border-radius", "var(--lumo-border-radius-s)")
                                style.set("font-size", "var(--lumo-font-size-xs)")
                                style.set("font-weight", "600")
                            })
                        }
                    }
                }.setHeader("Access Level").setSortable(true)
                addColumn { user ->
                    if (user.isAdmin()) "—"
                    else user.rating_ceiling?.let { ContentRating.ceilingLabel(it) } ?: "Unrestricted"
                }.setHeader("Rating Ceiling").setSortable(false)
                addColumn { it.created_at?.format(dateFmt) ?: "" }.setHeader("Created").setSortable(true)

                addComponentColumn { user -> createActionButtons(user) }
                    .setHeader("Actions")
                    .setFlexGrow(0)
                    .setWidth("480px")
            }

            refreshGrid()
        }
    }

    private fun refreshGrid() {
        grid.setItems(AppUser.findAll().sortedBy { it.username })
    }

    private fun createActionButtons(user: AppUser): HorizontalLayout {
        return HorizontalLayout().apply {
            isSpacing = true
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER

            // Promote / Demote
            if (user.isAdmin()) {
                add(Button("Demote", VaadinIcon.ARROW_DOWN.create()) {
                    demoteUser(user)
                }.apply { addThemeVariants(ButtonVariant.LUMO_SMALL) })
            } else {
                add(Button("Promote", VaadinIcon.ARROW_UP.create()) {
                    promoteUser(user)
                }.apply { addThemeVariants(ButtonVariant.LUMO_SMALL) })
            }

            // Set Rating Ceiling (not for admins — they always bypass)
            if (!user.isAdmin()) {
                add(Button("Rating", VaadinIcon.LOCK.create()) {
                    openRatingCeilingDialog(user)
                }.apply { addThemeVariants(ButtonVariant.LUMO_SMALL) })
            }

            // Unlock
            if (user.locked) {
                add(Button("Unlock", VaadinIcon.UNLOCK.create()) {
                    unlockUser(user)
                }.apply { addThemeVariants(ButtonVariant.LUMO_SMALL) })
            }

            // Sessions
            add(Button("Sessions", VaadinIcon.CONNECT.create()) {
                ui.ifPresent { it.navigate("sessions?userId=${user.id}") }
            }.apply { addThemeVariants(ButtonVariant.LUMO_SMALL) })

            // Reset Password
            add(Button("Reset PW", VaadinIcon.KEY.create()) {
                openResetPasswordDialog(user)
            }.apply { addThemeVariants(ButtonVariant.LUMO_SMALL) })

            // Force Password Change
            if (!user.must_change_password) {
                add(Button("Force PW Change", VaadinIcon.EXCLAMATION_CIRCLE.create()) {
                    forcePasswordChange(user)
                }.apply { addThemeVariants(ButtonVariant.LUMO_SMALL) })
            }

            // Delete
            add(Button("Delete", VaadinIcon.TRASH.create()) {
                deleteUser(user)
            }.apply {
                addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR)
            })
        }
    }

    private fun promoteUser(user: AppUser) {
        user.access_level = 2
        user.save()
        AuthService.invalidateUserSessions(user.id!!)
        val currentUser = AuthService.getCurrentUser()
        log.info("AUDIT: User '{}' promoted to Admin by '{}' — all sessions invalidated", user.username, currentUser?.username)
        Notification.show("${user.display_name} promoted to Admin — sessions invalidated", 3000, Notification.Position.MIDDLE)
        refreshGrid()
    }

    private fun demoteUser(user: AppUser) {
        if (AuthService.countAdmins() <= 1) {
            Notification.show("Cannot demote the last admin", 3000, Notification.Position.MIDDLE)
                .addThemeVariants(NotificationVariant.LUMO_ERROR)
            return
        }
        user.access_level = 1
        user.save()
        AuthService.invalidateUserSessions(user.id!!)
        val currentUser = AuthService.getCurrentUser()
        log.info("AUDIT: User '{}' demoted to Viewer by '{}' — all sessions invalidated", user.username, currentUser?.username)
        Notification.show("${user.display_name} demoted to Viewer — sessions invalidated", 3000, Notification.Position.MIDDLE)
        refreshGrid()
    }

    private fun unlockUser(user: AppUser) {
        val fresh = AppUser.findById(user.id!!) ?: return
        fresh.locked = false
        fresh.updated_at = java.time.LocalDateTime.now()
        fresh.save()
        val currentUser = AuthService.getCurrentUser()
        log.info("AUDIT: Account '{}' unlocked by '{}'", user.username, currentUser?.username)
        Notification.show("${user.display_name} unlocked", 3000, Notification.Position.MIDDLE)
        refreshGrid()
    }

    private fun forcePasswordChange(user: AppUser) {
        val fresh = AppUser.findById(user.id!!) ?: return
        fresh.must_change_password = true
        fresh.updated_at = java.time.LocalDateTime.now()
        fresh.save()
        val currentUser = AuthService.getCurrentUser()
        log.info("AUDIT: Force password change set for '{}' by '{}'", user.username, currentUser?.username)
        Notification.show("${user.display_name} must change password on next login", 3000, Notification.Position.MIDDLE)
        refreshGrid()
    }

    private fun deleteUser(user: AppUser) {
        if (user.isAdmin() && AuthService.countAdmins() <= 1) {
            Notification.show("Cannot delete the last admin", 3000, Notification.Position.MIDDLE)
                .addThemeVariants(NotificationVariant.LUMO_ERROR)
            return
        }
        val currentUser = AuthService.getCurrentUser()
        if (currentUser?.id == user.id) {
            Notification.show("Cannot delete yourself", 3000, Notification.Position.MIDDLE)
                .addThemeVariants(NotificationVariant.LUMO_ERROR)
            return
        }

        val dialog = Dialog()
        dialog.headerTitle = "Delete User"
        dialog.add(Span("Are you sure you want to delete '${user.display_name}'?"))
        dialog.footer.add(
            Button("Cancel") { dialog.close() },
            Button("Delete") {
                // Delete session tokens and device tokens first
                AuthService.invalidateUserSessions(user.id!!)
                val currentUser = AuthService.getCurrentUser()
                log.info("AUDIT: User '{}' deleted by '{}'", user.username, currentUser?.username)
                user.delete()
                dialog.close()
                Notification.show("User deleted", 3000, Notification.Position.MIDDLE)
                refreshGrid()
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR) }
        )
        dialog.open()
    }

    private fun openResetPasswordDialog(user: AppUser) {
        val dialog = Dialog()
        dialog.headerTitle = "Reset Password — ${user.display_name}"

        val adminPw = PasswordField("Your Password").apply {
            width = "100%"
            helperText = "Confirm your identity to reset this user's password"
        }
        val newPw = PasswordField("New Password").apply { width = "100%" }
        val confirmPw = PasswordField("Confirm Password").apply { width = "100%" }
        val forcePwChange = Checkbox("Require password change on next login").apply {
            value = true
        }
        val content = VerticalLayout(adminPw, newPw, confirmPw, forcePwChange).apply { isPadding = false }
        dialog.add(content)

        val resetBtn = Button("Reset") {
                val currentUser = AuthService.getCurrentUser()
                if (currentUser == null || !PasswordService.verify(adminPw.value, currentUser.password_hash)) {
                    adminPw.isInvalid = true
                    adminPw.errorMessage = "Incorrect password"
                    Notification.show("Incorrect password", 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@Button
                }
                val violations = PasswordService.validate(newPw.value, user.username)
                if (violations.isNotEmpty()) {
                    Notification.show(violations.first(), 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@Button
                }
                if (newPw.value != confirmPw.value) {
                    Notification.show("Passwords do not match", 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@Button
                }
                val fresh = AppUser.findById(user.id!!) ?: run {
                    dialog.close()
                    return@Button
                }
                fresh.password_hash = PasswordService.hash(newPw.value)
                fresh.must_change_password = forcePwChange.value
                fresh.updated_at = java.time.LocalDateTime.now()
                fresh.save()
                AuthService.invalidateUserSessions(user.id!!)
                log.info("AUDIT: Password reset for '{}' by '{}' — all sessions invalidated, force_change={}",
                    user.username, currentUser.username, forcePwChange.value)
                dialog.close()
                Notification.show("Password reset for ${user.display_name} — all sessions invalidated", 3000, Notification.Position.MIDDLE)
                refreshGrid()
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY); isEnabled = false }

        // Live validation
        val validateReset = {
            val ap = adminPw.value
            val pw = newPw.value
            val cp = confirmPw.value
            val adminFilled = ap.isNotEmpty()
            val longEnough = pw.length in PasswordService.MIN_PASSWORD_LENGTH..PasswordService.MAX_PASSWORD_LENGTH
            val matches = pw == cp

            newPw.isInvalid = pw.isNotEmpty() && !longEnough
            newPw.errorMessage = if (newPw.isInvalid) "Must be ${PasswordService.MIN_PASSWORD_LENGTH}–${PasswordService.MAX_PASSWORD_LENGTH} characters" else null
            confirmPw.isInvalid = cp.isNotEmpty() && !matches
            confirmPw.errorMessage = if (confirmPw.isInvalid) "Passwords do not match" else null

            resetBtn.isEnabled = adminFilled && longEnough && matches
        }
        adminPw.addValueChangeListener { validateReset() }
        newPw.addValueChangeListener { validateReset() }
        confirmPw.addValueChangeListener { validateReset() }

        dialog.footer.add(
            Button("Cancel") { dialog.close() },
            resetBtn
        )
        dialog.open()
    }

    private fun openRatingCeilingDialog(user: AppUser) {
        val dialog = Dialog()
        dialog.headerTitle = "Rating Ceiling — ${user.display_name}"

        val choices = ContentRating.ceilingChoices()
        val combo = ComboBox<Pair<Int, String>>("Maximum Allowed Rating").apply {
            setItems(choices)
            setItemLabelGenerator { it.second }
            width = "100%"
            // Pre-select current ceiling
            val current = user.rating_ceiling
            if (current != null) {
                value = choices.firstOrNull { it.first == current }
            }
            isClearButtonVisible = true
            placeholder = "Unrestricted (no ceiling)"
        }

        val content = VerticalLayout(
            Span("Set the maximum content rating this user can access. Titles above this rating will be hidden. Clear the field for unrestricted access.").apply {
                style.set("font-size", "var(--lumo-font-size-s)")
                style.set("color", "var(--lumo-secondary-text-color)")
            },
            combo
        ).apply { isPadding = false }
        dialog.add(content)

        dialog.footer.add(
            Button("Cancel") { dialog.close() },
            Button("Save") {
                val fresh = AppUser.findById(user.id!!) ?: run {
                    dialog.close()
                    return@Button
                }
                fresh.rating_ceiling = combo.value?.first
                fresh.updated_at = java.time.LocalDateTime.now()
                fresh.save()
                AuthService.invalidateUserSessions(user.id!!)
                val currentUser = AuthService.getCurrentUser()
                val label = combo.value?.second ?: "Unrestricted"
                log.info("AUDIT: Rating ceiling for '{}' set to '{}' by '{}' — all sessions invalidated",
                    user.username, label, currentUser?.username)
                dialog.close()
                Notification.show("Rating ceiling set to $label — sessions invalidated", 3000, Notification.Position.MIDDLE)
                refreshGrid()
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }
        )
        dialog.open()
    }

    private fun openAddUserDialog() {
        val dialog = Dialog()
        dialog.headerTitle = "Add User"

        val usernameField = TextField("Username").apply { width = "100%" }
        val displayNameField = TextField("Display Name").apply { width = "100%" }
        val passwordField = PasswordField("Password").apply { width = "100%" }
        val confirmField = PasswordField("Confirm Password").apply { width = "100%" }
        val forcePwChange = Checkbox("Require password change on first login").apply {
            value = true
        }

        val content = VerticalLayout(usernameField, displayNameField, passwordField, confirmField, forcePwChange)
            .apply { isPadding = false }
        dialog.add(content)

        val createBtn = Button("Create") {
                val username = usernameField.value.trim()
                val displayName = displayNameField.value.trim()
                val password = passwordField.value

                if (username.isBlank()) {
                    Notification.show("Username is required", 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@Button
                }
                if (displayName.isBlank()) {
                    Notification.show("Display name is required", 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@Button
                }
                val violations = PasswordService.validate(password, username)
                if (violations.isNotEmpty()) {
                    Notification.show(violations.first(), 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@Button
                }
                if (password != confirmField.value) {
                    Notification.show("Passwords do not match", 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@Button
                }

                // Check uniqueness via SQL
                val exists = JdbiOrm.jdbi().withHandle<Boolean, Exception> { handle ->
                    handle.createQuery("SELECT COUNT(*) FROM app_user WHERE LOWER(username) = LOWER(:u)")
                        .bind("u", username)
                        .mapTo(Int::class.java)
                        .one() > 0
                }
                if (exists) {
                    Notification.show("Username already exists", 3000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR)
                    return@Button
                }

                val now = java.time.LocalDateTime.now()
                AppUser(
                    username = username,
                    display_name = displayName,
                    password_hash = PasswordService.hash(password),
                    access_level = 1, // Default to Viewer
                    must_change_password = forcePwChange.value,
                    created_at = now,
                    updated_at = now
                ).save()
                val currentUser = AuthService.getCurrentUser()
                log.info("AUDIT: User '{}' created by '{}' (force_pw_change={})", username, currentUser?.username, forcePwChange.value)
                dialog.close()
                Notification.show("User '$displayName' created", 3000, Notification.Position.MIDDLE)
                refreshGrid()
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY); isEnabled = false }

        // Live validation
        val validateCreate = {
            val pw = passwordField.value
            val cp = confirmField.value
            val longEnough = pw.length in PasswordService.MIN_PASSWORD_LENGTH..PasswordService.MAX_PASSWORD_LENGTH
            val matches = pw == cp
            val hasUsername = usernameField.value.trim().isNotEmpty()
            val hasDisplayName = displayNameField.value.trim().isNotEmpty()

            passwordField.isInvalid = pw.isNotEmpty() && !longEnough
            passwordField.errorMessage = if (passwordField.isInvalid) "Must be ${PasswordService.MIN_PASSWORD_LENGTH}–${PasswordService.MAX_PASSWORD_LENGTH} characters" else null
            confirmField.isInvalid = cp.isNotEmpty() && !matches
            confirmField.errorMessage = if (confirmField.isInvalid) "Passwords do not match" else null

            createBtn.isEnabled = hasUsername && hasDisplayName && longEnough && matches
        }
        usernameField.addValueChangeListener { validateCreate() }
        displayNameField.addValueChangeListener { validateCreate() }
        passwordField.addValueChangeListener { validateCreate() }
        confirmField.addValueChangeListener { validateCreate() }

        dialog.footer.add(
            Button("Cancel") { dialog.close() },
            createBtn
        )
        dialog.open()
    }
}
