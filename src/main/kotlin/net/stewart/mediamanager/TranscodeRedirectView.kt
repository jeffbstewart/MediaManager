package net.stewart.mediamanager

import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route

@Route(value = "transcodes", layout = MainLayout::class)
@PageTitle("Transcodes")
class TranscodeRedirectView : com.vaadin.flow.component.orderedlayout.VerticalLayout(), BeforeEnterObserver {
    override fun beforeEnter(event: BeforeEnterEvent) {
        event.forwardTo("transcodes/status")
    }
}
