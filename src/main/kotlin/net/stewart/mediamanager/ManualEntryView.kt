package net.stewart.mediamanager

import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route

@Route(value = "manual-entry", layout = MainLayout::class)
@PageTitle("Add Title Manually")
class ManualEntryView : VerticalLayout(), BeforeEnterObserver {
    override fun beforeEnter(event: BeforeEnterEvent) {
        event.forwardTo("add")
    }
}
