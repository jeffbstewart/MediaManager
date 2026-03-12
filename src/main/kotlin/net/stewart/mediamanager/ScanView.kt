package net.stewart.mediamanager

import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route

@Route(value = "scan", layout = MainLayout::class)
@PageTitle("Barcode Scanner")
class ScanView : VerticalLayout(), BeforeEnterObserver {
    override fun beforeEnter(event: BeforeEnterEvent) {
        event.forwardTo("add")
    }
}
