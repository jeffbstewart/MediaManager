package net.stewart.mediamanager

import com.vaadin.flow.component.ClientCallable
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import net.stewart.mediamanager.service.OwnershipPhotoService
import java.util.Base64

/**
 * Reusable panel for capturing and displaying ownership photos.
 * Handles native camera capture via @ClientCallable and shows a photo strip with delete.
 *
 * Set [mediaItemId] and/or [upc] before the panel is attached, or call [refresh] after updating them.
 */
class OwnershipPhotoPanel : VerticalLayout() {

    var mediaItemId: Long? = null
    var upc: String? = null

    private val photoStrip = HorizontalLayout().apply {
        isPadding = false
        isSpacing = true
        width = "100%"
        style.set("flex-wrap", "wrap")
        style.set("gap", "var(--lumo-space-s)")
    }

    init {
        isPadding = false
        isSpacing = true
        width = "100%"

        val captureBtn = Button("Take Photo", VaadinIcon.CAMERA.create()).apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            width = "100%"
            height = "50px"
        }

        val captureContainer = Div().apply {
            style.set("position", "relative")
            width = "100%"
            add(captureBtn)
        }

        // Wire the button to a native file input with capture=environment
        element.executeJs(
            "var viewEl=\$0;" +
            "var container=\$1;" +
            "var input=document.createElement('input');" +
            "input.type='file';" +
            "input.accept='image/*';" +
            "input.capture='environment';" +
            "input.style.display='none';" +
            "container.appendChild(input);" +
            "container.firstElementChild.addEventListener('click',function(e){" +
            "e.preventDefault();e.stopPropagation();input.click();});" +
            "input.addEventListener('change',function(){" +
            "if(!input.files||!input.files[0])return;" +
            "var file=input.files[0];" +
            "var reader=new FileReader();" +
            "reader.onload=function(){" +
            "var base64=reader.result.split(',')[1];" +
            "var mimeType=file.type||'image/jpeg';" +
            "viewEl.\$server.onPhotoCapture(base64,mimeType);" +
            "};" +
            "reader.readAsDataURL(file);" +
            "input.value='';});",
            this.element, captureContainer.element
        )

        val photosLabel = Span("Evidence Photos").apply {
            style.set("font-weight", "600")
        }

        add(captureContainer, photosLabel, photoStrip)
        refreshPhotoStrip()
    }

    fun refresh() {
        refreshPhotoStrip()
    }

    private fun refreshPhotoStrip() {
        photoStrip.removeAll()
        val itemId = mediaItemId
        val upcVal = upc
        val photos = when {
            itemId != null -> OwnershipPhotoService.findAllForItem(itemId, upcVal)
            upcVal != null -> OwnershipPhotoService.findByUpc(upcVal)
            else -> emptyList()
        }

        if (photos.isEmpty()) {
            photoStrip.add(Span("No photos yet").apply {
                style.set("color", "rgba(255,255,255,0.4)")
                style.set("font-size", "var(--lumo-font-size-s)")
            })
            return
        }
        for (photo in photos) {
            val container = Div().apply {
                style.set("position", "relative")
                style.set("display", "inline-block")

                val img = Image("/ownership-photos/${photo.id}", "Evidence photo").apply {
                    height = "100px"
                    style.set("border-radius", "4px")
                    style.set("object-fit", "cover")
                    style.set("cursor", "pointer")
                    style.set("max-width", "150px")
                }
                img.element.addEventListener("click") {
                    img.element.executeJs(
                        "window.open('/ownership-photos/' + $0 + '?download=1', '_blank')",
                        photo.id!!
                    )
                }
                add(img)

                val deleteBtn = Div().apply {
                    style.set("position", "absolute")
                    style.set("top", "4px")
                    style.set("right", "4px")
                    style.set("width", "22px")
                    style.set("height", "22px")
                    style.set("border-radius", "50%")
                    style.set("background", "rgba(0,0,0,0.6)")
                    style.set("color", "rgba(255,255,255,0.7)")
                    style.set("display", "flex")
                    style.set("align-items", "center")
                    style.set("justify-content", "center")
                    style.set("cursor", "pointer")
                    style.set("font-size", "14px")
                    style.set("line-height", "1")
                    style.set("opacity", "0.6")
                    element.setProperty("innerHTML", "✕")
                    element.setAttribute("title", "Delete photo")
                    element.executeJs(
                        "this.addEventListener('mouseover',function(){this.style.opacity='1';this.style.background='rgba(0,0,0,0.8)'});" +
                        "this.addEventListener('mouseout',function(){this.style.opacity='0.6';this.style.background='rgba(0,0,0,0.6)'})"
                    )
                }
                deleteBtn.addClickListener {
                    OwnershipPhotoService.delete(photo.id!!)
                    Notification.show("Photo deleted", 2000, Notification.Position.BOTTOM_START)
                    refreshPhotoStrip()
                }
                add(deleteBtn)
            }
            photoStrip.add(container)
        }
    }

    @ClientCallable
    fun onPhotoCapture(base64Data: String, mimeType: String) {
        val bytes = Base64.getDecoder().decode(base64Data)
        val itemId = mediaItemId
        val upcVal = upc

        if (itemId != null) {
            OwnershipPhotoService.store(bytes, mimeType, itemId)
        } else if (upcVal != null) {
            OwnershipPhotoService.storeForUpc(bytes, mimeType, upcVal)
        } else {
            Notification.show("No item selected", 3000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.LUMO_ERROR)
            return
        }

        Notification.show("Photo saved", 2000, Notification.Position.BOTTOM_START)
            .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
        refreshPhotoStrip()
    }
}
