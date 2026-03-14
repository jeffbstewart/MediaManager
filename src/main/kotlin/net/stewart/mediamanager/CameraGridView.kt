package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.findAll
import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import net.stewart.mediamanager.entity.Camera

/**
 * Live camera grid view showing MJPEG streams from all enabled cameras.
 * Accessible to all authenticated users (viewer + admin).
 * Each cell shows a live MJPEG stream in an <img> tag (natively rendered by the browser).
 * Click a cell to view fullscreen.
 */
@Route(value = "cameras", layout = MainLayout::class)
@PageTitle("Cameras")
class CameraGridView : KComposite() {

    private lateinit var gridContainer: Div
    private var useMjpeg = true

    private val root = ui {
        verticalLayout {
            setSizeFull()

            val headerRow = HorizontalLayout().apply {
                width = "100%"
                defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            }
            val title = Span("Cameras").apply {
                style.set("font-size", "var(--lumo-font-size-xxl)")
                style.set("font-weight", "700")
                style.set("flex-grow", "1")
            }
            val toggleBtn = Button("Snapshots") {
                useMjpeg = !useMjpeg
                (it.source as Button).text = if (useMjpeg) "Snapshots" else "Live"
                buildGrid()
            }
            toggleBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY)
            toggleBtn.element.setAttribute("title", "Toggle between live MJPEG and snapshot polling")
            headerRow.add(title, toggleBtn)
            add(headerRow)

            gridContainer = Div().apply {
                setSizeFull()
                style.set("display", "grid")
                style.set("gap", "var(--lumo-space-s)")
                style.set("padding", "var(--lumo-space-s)")
            }
            add(gridContainer)

            buildGrid()
        }
    }

    private fun buildGrid() {
        gridContainer.removeAll()
        val cameras = Camera.findAll().filter { it.enabled }.sortedBy { it.display_order }

        if (cameras.isEmpty()) {
            gridContainer.add(Span("No cameras configured.").apply {
                style.set("color", "var(--lumo-secondary-text-color)")
                style.set("padding", "var(--lumo-space-l)")
            })
            return
        }

        // Responsive grid columns based on camera count
        val cols = when {
            cameras.size <= 1 -> "1fr"
            cameras.size <= 4 -> "repeat(2, 1fr)"
            else -> "repeat(3, 1fr)"
        }
        gridContainer.style.set("grid-template-columns", cols)

        for (camera in cameras) {
            val cell = Div().apply {
                style.set("position", "relative")
                style.set("border-radius", "var(--lumo-border-radius-l)")
                style.set("overflow", "hidden")
                style.set("background", "var(--lumo-contrast-5pct)")
                style.set("cursor", "pointer")

                val imgSrc = if (useMjpeg) {
                    "/cameras/${camera.id}/mjpeg"
                } else {
                    "/cameras/${camera.id}/snapshot.jpg?t=${System.currentTimeMillis()}"
                }

                element.setProperty("innerHTML", """
                    <img src="$imgSrc" alt="${camera.name}"
                         style="width:100%;height:auto;display:block;aspect-ratio:16/9;object-fit:cover;"
                         onerror="this.style.display='none'" />
                """.trimIndent())

                // Name overlay
                add(Span(camera.name).apply {
                    style.set("position", "absolute")
                    style.set("bottom", "0")
                    style.set("left", "0")
                    style.set("right", "0")
                    style.set("padding", "var(--lumo-space-xs) var(--lumo-space-s)")
                    style.set("background", "linear-gradient(transparent, rgba(0,0,0,0.7))")
                    style.set("color", "white")
                    style.set("font-size", "var(--lumo-font-size-s)")
                    style.set("font-weight", "600")
                })

                // Click to fullscreen
                element.addEventListener("click") {
                    showFullscreen(camera)
                }
            }
            gridContainer.add(cell)
        }

        // If using snapshot mode, set up periodic refresh
        if (!useMjpeg) {
            setupSnapshotRefresh(cameras)
        }
    }

    private fun showFullscreen(camera: Camera) {
        val dialog = Dialog()
        dialog.headerTitle = camera.name
        dialog.width = "90vw"
        dialog.height = "90vh"

        val imgSrc = "/cameras/${camera.id}/mjpeg"
        val container = Div().apply {
            setSizeFull()
            element.setProperty("innerHTML", """
                <img src="$imgSrc" alt="${camera.name}"
                     style="width:100%;height:100%;object-fit:contain;" />
            """.trimIndent())
        }
        dialog.add(container)
        dialog.footer.add(Button("Close") { dialog.close() })
        dialog.open()
    }

    private fun setupSnapshotRefresh(cameras: List<Camera>) {
        // Use Vaadin's page JS to refresh snapshots every 3 seconds
        val js = cameras.joinToString(";") { camera ->
            """
            setInterval(function(){
                var imgs = document.querySelectorAll('img[src*="/cameras/${camera.id}/snapshot"]');
                imgs.forEach(function(img){img.src='/cameras/${camera.id}/snapshot.jpg?t='+Date.now()});
            }, 3000)
            """.trimIndent()
        }
        ui.ifPresent { it.page.executeJs(js) }
    }
}
