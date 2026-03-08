package net.stewart.mediamanager

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.PairingService
import org.slf4j.LoggerFactory

/**
 * Confirmation page for QR code device pairing.
 *
 * When a user scans the QR code displayed on their Roku, their phone opens
 * this page. If they're logged in, they see a confirmation button. If not,
 * they're redirected to login first (SecurityServiceInitListener handles that).
 *
 * Route: /pair?code=X7K9M2
 */
@Route("pair")
@PageTitle("Link Device — Media Manager")
class PairConfirmView : VerticalLayout(), BeforeEnterObserver {

    private val log = LoggerFactory.getLogger(PairConfirmView::class.java)
    private var pairCode: String? = null

    init {
        setSizeFull()
        justifyContentMode = FlexComponent.JustifyContentMode.CENTER
        defaultHorizontalComponentAlignment = FlexComponent.Alignment.CENTER
        maxWidth = "500px"
        style.set("margin", "0 auto")
        style.set("padding", "2em")
    }

    override fun beforeEnter(event: BeforeEnterEvent) {
        pairCode = event.location.queryParameters.parameters["code"]?.firstOrNull()

        if (pairCode.isNullOrBlank()) {
            showError("No pairing code provided.")
            return
        }

        val user = AuthService.getCurrentUser() ?: AuthService.restoreFromCookie()
        if (user == null) {
            // SecurityServiceInitListener will redirect to login.
            // After login, user will be sent back here with the code param preserved.
            return
        }

        // Check if the code is valid
        val status = PairingService.checkStatus(pairCode!!)
        if (status == null) {
            showError("This pairing code has expired or is invalid. Please start a new pairing on your device.")
            return
        }
        if (status.status == "paired") {
            showAlreadyPaired(status.username ?: "unknown")
            return
        }
        if (status.status == "expired") {
            showError("This pairing code has expired. Please start a new pairing on your device.")
            return
        }

        showConfirmation(user.display_name.ifEmpty { user.username })
    }

    private fun showConfirmation(displayName: String) {
        removeAll()

        add(H2("Link Device"))
        add(Paragraph("Link this device to your account?"))

        val userBadge = Span(displayName).apply {
            style.set("background", "#6366f1")
            style.set("color", "white")
            style.set("padding", "0.3em 1em")
            style.set("border-radius", "1em")
            style.set("font-size", "1.2em")
            style.set("font-weight", "bold")
        }
        add(userBadge)

        add(Paragraph("The device will be able to access your media library, " +
            "respecting your rating limits and preferences.").apply {
            style.set("color", "#888")
            style.set("margin-top", "1em")
        })

        val confirmButton = Button("Confirm Link").apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            width = "100%"
            style.set("margin-top", "1.5em")
            addClickListener { doConfirm() }
        }

        val cancelButton = Button("Cancel").apply {
            width = "100%"
            addClickListener {
                Notification.show("Pairing cancelled", 3000, Notification.Position.MIDDLE)
            }
        }

        add(confirmButton, cancelButton)
    }

    private fun doConfirm() {
        val user = AuthService.getCurrentUser()
        if (user == null || pairCode == null) {
            showError("Session expired. Please log in and try again.")
            return
        }

        val deviceName = PairingService.confirmPairing(pairCode!!, user)
        if (deviceName == null) {
            showError("This pairing code has expired or was already used. Please start a new pairing on your device.")
            return
        }

        removeAll()

        add(H2("Device Linked"))

        val checkmark = Span("✓").apply {
            style.set("font-size", "4em")
            style.set("color", "#22c55e")
        }
        add(checkmark)

        add(Paragraph("\"$deviceName\" is now linked to your account."))
        add(Paragraph("You can close this page. Your device will start loading momentarily.").apply {
            style.set("color", "#888")
        })

        log.info("AUDIT: Device '{}' paired to user '{}'", deviceName, user.username)
    }

    private fun showError(message: String) {
        removeAll()
        add(H2("Pairing Failed"))
        add(Paragraph(message))
    }

    private fun showAlreadyPaired(username: String) {
        removeAll()
        add(H2("Already Paired"))
        add(Paragraph("This device is already linked to $username."))
    }
}
