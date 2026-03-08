package net.stewart.mediamanager

import com.vaadin.flow.component.UI
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
import net.stewart.mediamanager.service.LoginResult
import org.slf4j.LoggerFactory

@Route("login")
@PageTitle("Login — Media Manager")
class LoginView : VerticalLayout(), BeforeEnterObserver {

    private val log = LoggerFactory.getLogger(LoginView::class.java)
    private val loginForm = LoginForm()

    init {
        setSizeFull()
        justifyContentMode = FlexComponent.JustifyContentMode.CENTER
        defaultHorizontalComponentAlignment = FlexComponent.Alignment.CENTER

        loginForm.isForgotPasswordButtonVisible = false
        loginForm.addLoginListener { event ->
            val ip = (VaadinServletRequest.getCurrent()?.httpServletRequest?.remoteAddr) ?: "unknown"
            when (val result = AuthService.login(event.username, event.password, ip)) {
                is LoginResult.Success -> {
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
    }

    override fun beforeEnter(event: BeforeEnterEvent) {
        // Already logged in? Go home.
        if (AuthService.getCurrentUser() != null) {
            event.forwardTo("")
        }
    }
}
