package net.stewart.mediamanager

import com.github.vokorm.findAll
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.applayout.AppLayout
import com.vaadin.flow.component.applayout.DrawerToggle
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.textfield.IntegerField
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Hr
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.menubar.MenuBar
import com.vaadin.flow.component.menubar.MenuBarVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.AfterNavigationEvent
import com.vaadin.flow.router.AfterNavigationObserver
import com.vaadin.flow.router.RouterLink
import net.stewart.mediamanager.entity.Camera
import net.stewart.mediamanager.entity.CastMember
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.TmdbCollection
import net.stewart.mediamanager.entity.TmdbCollectionPart
import com.gitlab.mvysny.jdbiorm.JdbiOrm
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.SearchIndexService
import net.stewart.mediamanager.service.TagService
import net.stewart.mediamanager.service.UserTitleFlagService

/** Wrapper for search results that can be a Title, Actor, or Collection. */
private sealed class SearchItem(val displayName: String, val popularity: Double) {
    class TitleItem(val title: Title) : SearchItem(title.name, title.popularity ?: 0.0)
    class ActorItem(
        val name: String,
        val personId: Int,
        val headshotCastId: Long?,    // any CastMember.id for this person (for headshot servlet)
        popularity: Double
    ) : SearchItem(name, popularity)
    class CollectionItem(
        val collection: TmdbCollection,
        val titleCount: Int
    ) : SearchItem(collection.name, 0.0)
    class TagItem(
        val tag: Tag,
        val titleCount: Int
    ) : SearchItem(tag.name, 0.0)
}

class MainLayout : AppLayout(), AfterNavigationObserver {

    private val profileMenuBar: MenuBar
    private var profileDisplayName: Span
    private val adminSection: VerticalLayout
    private val drawerItems: List<Pair<HorizontalLayout, String>> // layout to route
    private var contentParent: HorizontalLayout
    private var contentChildren: VerticalLayout
    private var contentChevron: Icon
    private var purchasesParent: HorizontalLayout
    private var purchasesChildren: VerticalLayout
    private var purchasesChevron: Icon
    private var transcodesParent: HorizontalLayout
    private var transcodesChildren: VerticalLayout
    private var transcodesChevron: Icon
    private val unmatchedBadge: Span
    private val transcodesParentBadge: Span
    private val expandBadge: Span
    private val purchasesParentBadge: Span
    private val dataQualityBadge: Span
    private val wishListBadge: Span

    init {
        // --- Top navbar: [DrawerToggle] [Title] [Search] [..spacer..] [Profile] ---

        val toggle = DrawerToggle().apply {
            style.set("color", "rgba(255,255,255,0.7)")
        }

        val appName = Span("Media Manager").apply {
            addClassName("app-title")
            style.set("font-size", "var(--lumo-font-size-l)")
            style.set("font-weight", "700")
            style.set("color", "#FFFFFF")
            style.set("white-space", "nowrap")
        }

        val searchCombo = createSearchComboBox().apply {
            addClassName("navbar-search")
        }

        // Profile menu — just Logout (Wish List moved to drawer)
        val currentUser = AuthService.getCurrentUser()
        profileDisplayName = Span(currentUser?.display_name ?: "").apply {
            addClassName("profile-name")
        }
        profileMenuBar = MenuBar().apply {
            addThemeVariants(MenuBarVariant.LUMO_TERTIARY_INLINE)
            val userIcon = VaadinIcon.USER.create().apply { setSize("14px") }
            val profileLabel = Span(userIcon, Span(" "), profileDisplayName)
            val profile = addItem(profileLabel)
            profile.subMenu.addItem("Change Password") {
                ui.ifPresent { it.navigate("change-password") }
            }
            profile.subMenu.addItem("Active Sessions") {
                ui.ifPresent { it.navigate("sessions") }
            }
            profile.subMenu.addItem("Logout") {
                AuthService.logout()
                ui.ifPresent { ui ->
                    // Clear legacy mm_session cookie (non-HttpOnly, set by older versions)
                    ui.page.executeJs("document.cookie='mm_session=;path=/;max-age=0;SameSite=Lax'")
                    ui.navigate("login")
                }
            }

            style.set("font-size", "var(--lumo-font-size-s)")
            style.set("white-space", "nowrap")

            addAttachListener {
                element.executeJs(
                    "requestAnimationFrame(function(){" +
                    "var r=this.shadowRoot; if(!r) return;" +
                    "if(r.querySelector('#profile-mb')) return;" +
                    "var s=document.createElement('style'); s.id='profile-mb';" +
                    "s.textContent='" +
                    ":host{min-width:auto!important}" +
                    "::slotted(vaadin-menu-bar-button){" +
                    "color:rgba(255,255,255,0.7)!important;" +
                    "padding:var(--lumo-space-xs) var(--lumo-space-s)!important;" +
                    "margin:0!important;min-width:auto!important;" +
                    "border-radius:var(--lumo-border-radius-m)!important;" +
                    "font-size:var(--lumo-font-size-s)!important;" +
                    "cursor:pointer!important;" +
                    "transition:color 0.2s,background-color 0.2s!important}" +
                    "::slotted(vaadin-menu-bar-button:hover){" +
                    "color:#FFFFFF!important;" +
                    "background-color:rgba(255,255,255,0.1)!important}" +
                    "';" +
                    "r.appendChild(s);" +
                    "}.bind(this))"
                )
            }
        }

        val rightSection = HorizontalLayout().apply {
            addClassName("navbar-right")
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            isSpacing = true
            style.set("flex-shrink", "0")
            if (CommandLineFlags.developerMode) {
                val h2Link = Anchor(
                    "http://localhost:${CommandLineFlags.h2ConsolePort}",
                    "H2 Console"
                ).apply {
                    style.set("font-size", "var(--lumo-font-size-s)")
                    style.set("color", "rgba(255,255,255,0.6)")
                }
                add(h2Link)
            }
            add(profileMenuBar)
        }

        val header = HorizontalLayout().apply {
            addClassName("navbar-header")
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            isPadding = true
            width = "100%"
            style.set("gap", "var(--lumo-space-m)")
        }

        // Mobile search icon button — visible only on mobile when idle
        val searchButton = Button(Icon(VaadinIcon.SEARCH)).apply {
            addClassName("mobile-search-btn")
            addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE)
            style.set("color", "rgba(255,255,255,0.7)")
            style.set("min-width", "36px")
            style.set("width", "36px")
            style.set("height", "36px")
            style.set("padding", "0")
            addClickListener {
                header.addClassName("search-active")
                searchCombo.focus()
            }
        }

        // Back arrow button — visible only on mobile in search-active mode
        val closeSearchButton = Button(Icon(VaadinIcon.ARROW_LEFT)).apply {
            addClassName("search-close-btn")
            addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE)
            style.set("color", "rgba(255,255,255,0.7)")
            style.set("min-width", "36px")
            style.set("width", "36px")
            style.set("height", "36px")
            style.set("padding", "0")
            addClickListener {
                header.removeClassName("search-active")
            }
        }

        // Exit search mode on ComboBox blur (200ms delay for dropdown clicks)
        searchCombo.addBlurListener {
            ui.ifPresent { ui ->
                ui.page.executeJs(
                    "setTimeout(function(){" +
                    "document.querySelector('.navbar-header')?.classList.remove('search-active');" +
                    "}, 200)"
                )
            }
        }

        header.add(toggle, appName, searchButton, closeSearchButton, searchCombo, rightSection)
        header.expand(searchCombo)
        rightSection.style.set("margin-left", "auto")

        addToNavbar(header)

        // Inject responsive CSS for mobile navbar
        header.addAttachListener {
            header.element.executeJs(
                "if(!document.getElementById('navbar-responsive')){" +
                "var s=document.createElement('style');s.id='navbar-responsive';" +
                "s.textContent='" +
                ".mobile-search-btn{display:none!important}" +
                ".search-close-btn{display:none!important}" +
                "@media(max-width:600px){" +
                ".mobile-search-btn{display:inline-flex!important}" +
                ".navbar-search{display:none!important}" +
                ".profile-name{display:none!important}" +
                ".search-active .mobile-search-btn{display:none!important}" +
                ".search-active .search-close-btn{display:inline-flex!important}" +
                ".search-active .navbar-search{display:flex!important;flex:1 1 0!important;min-width:0!important}" +
                ".search-active .app-title{display:none!important}" +
                ".search-active .navbar-right{display:none!important}" +
                "}" +
                "';" +
                "document.head.appendChild(s)}"
            )
        }

        // --- Drawer content ---

        val items = mutableListOf<Pair<HorizontalLayout, String>>()

        // Main section (all users)
        val homeItem = createDrawerItem(VaadinIcon.HOME, "Home", "")
        val wishListItem = createDrawerItem(VaadinIcon.HEART, "My Wish List", "wishlist")
        wishListBadge = Span().apply {
            style.set("background-color", "var(--lumo-success-color)")
            style.set("color", "white")
            style.set("font-size", "var(--lumo-font-size-xxs)")
            style.set("font-weight", "700")
            style.set("padding", "1px 6px")
            style.set("border-radius", "9999px")
            style.set("margin-left", "auto")
            style.set("flex-shrink", "0")
            isVisible = false
            element.setAttribute("title", "Wishes ready to watch")
        }
        wishListItem.first.add(wishListBadge)

        // Content collapsible group (replaces Browse)
        val cMoviesItem = createDrawerItem(null, "Movies", "content/movies", indent = true)
        val cTvItem = createDrawerItem(null, "TV Shows", "content/tv", indent = true)
        val cCollectionsItem = createDrawerItem(null, "Collections", "content/collections", indent = true)
        val cTagsItem = createDrawerItem(null, "Tags", "content/tags", indent = true)
        val cFamilyItem = createDrawerItem(null, "Family", "content/family", indent = true)
        val hasCameras = Camera.findAll().any { it.enabled }
        val cCamerasItem = createDrawerItem(null, "Cameras", "cameras", indent = true)
        cCamerasItem.first.isVisible = hasCameras

        contentChevron = VaadinIcon.CHEVRON_DOWN.create().apply {
            setSize("14px")
            style.set("transition", "transform 0.2s")
            style.set("flex-shrink", "0")
        }
        contentParent = HorizontalLayout().apply {
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            isPadding = false
            isSpacing = true
            width = "100%"
            style.set("padding", "var(--lumo-space-s) var(--lumo-space-m)")
            style.set("cursor", "pointer")
            style.set("border-radius", "var(--lumo-border-radius-m)")
            style.set("margin", "1px var(--lumo-space-xs)")
            style.set("transition", "background-color 0.2s")
            style.set("color", "rgba(255,255,255,0.7)")

            val gridIcon = VaadinIcon.GRID_BIG.create().apply {
                setSize("18px")
                style.set("flex-shrink", "0")
            }
            val labelSpan = Span("Content").apply {
                style.set("font-size", "var(--lumo-font-size-s)")
                style.set("flex-grow", "1")
            }
            add(gridIcon, labelSpan, contentChevron)

            addClickListener {
                val wasVisible = contentChildren.isVisible
                contentChildren.isVisible = !wasVisible
                contentChevron.style.set("transform", if (wasVisible) "rotate(0deg)" else "rotate(180deg)")
            }
        }

        contentChildren = VerticalLayout().apply {
            isPadding = false
            isSpacing = false
            width = "100%"
            isVisible = false
            add(cMoviesItem.first, cTvItem.first, cCollectionsItem.first, cTagsItem.first, cFamilyItem.first, cCamerasItem.first)
        }

        items.add(homeItem)
        items.add(cMoviesItem)
        items.add(cTvItem)
        items.add(cCollectionsItem)
        items.add(cTagsItem)
        items.add(cFamilyItem)
        items.add(cCamerasItem)
        items.add(wishListItem)

        val mainSection = VerticalLayout().apply {
            isPadding = false
            isSpacing = false
            width = "100%"
            add(homeItem.first, contentParent, contentChildren, wishListItem.first)
        }

        // Admin section (divider included so it hides with the section)
        val divider = Hr().apply {
            style.set("margin", "var(--lumo-space-s) var(--lumo-space-m)")
            style.set("border-color", "rgba(255,255,255,0.1)")
        }

        val manageHeader = Span("MANAGE").apply {
            style.set("font-size", "var(--lumo-font-size-xs)")
            style.set("color", "rgba(255,255,255,0.4)")
            style.set("font-weight", "600")
            style.set("letter-spacing", "0.05em")
            style.set("padding", "var(--lumo-space-s) var(--lumo-space-m)")
            style.set("padding-bottom", "var(--lumo-space-xs)")
        }

        val dataQualityItem = createDrawerItem(VaadinIcon.WRENCH, "Data Quality", "data-quality")
        dataQualityBadge = Span().apply {
            style.set("background-color", "var(--lumo-error-color)")
            style.set("color", "white")
            style.set("font-size", "var(--lumo-font-size-xxs)")
            style.set("font-weight", "700")
            style.set("padding", "1px 6px")
            style.set("border-radius", "9999px")
            style.set("margin-left", "auto")
            style.set("flex-shrink", "0")
            isVisible = false
            element.setAttribute("title", "Titles needing TMDB enrichment or metadata fixes")
        }
        dataQualityItem.first.add(dataQualityBadge)
        val addItemItem = createDrawerItem(VaadinIcon.PLUS, "Add Item", "add")
        val camerasSettingsItem = createDrawerItem(VaadinIcon.MOVIE, "Cameras", "cameras/settings")
        val liveTvSettingsItem = createDrawerItem(VaadinIcon.DESKTOP, "Live TV", "live-tv/settings")
        val settingsItem = createDrawerItem(VaadinIcon.COG, "Settings", "settings")
        val tagsItem = createDrawerItem(VaadinIcon.BOOKMARK, "Tags", "tags")
        val usersItem = createDrawerItem(VaadinIcon.USERS, "Users", "users")

        // Purchases collapsible group
        val pImportItem = createDrawerItem(null, "Amazon Order Import", "import", indent = true)
        val pExpandItem = createDrawerItem(null, "Expand", "expand", indent = true)
        expandBadge = Span().apply {
            style.set("background-color", "var(--lumo-error-color)")
            style.set("color", "white")
            style.set("font-size", "var(--lumo-font-size-xxs)")
            style.set("font-weight", "700")
            style.set("padding", "1px 6px")
            style.set("border-radius", "9999px")
            style.set("margin-left", "auto")
            style.set("flex-shrink", "0")
            isVisible = false
            element.setAttribute("title", "Multi-pack titles awaiting expansion")
        }
        pExpandItem.first.add(expandBadge)
        val pValuationItem = createDrawerItem(null, "Valuation", "valuation", indent = true)
        val pWishesItem = createDrawerItem(null, "User Wishes", "purchase-wishes", indent = true)
        val pOwnershipItem = createDrawerItem(null, "Document Ownership", "document-ownership", indent = true)
        val pReportItem = createDrawerItem(null, "Report", "report", indent = true)

        purchasesChevron = VaadinIcon.CHEVRON_DOWN.create().apply {
            setSize("14px")
            style.set("transition", "transform 0.2s")
            style.set("flex-shrink", "0")
        }
        purchasesParent = HorizontalLayout().apply {
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            isPadding = false
            isSpacing = true
            width = "100%"
            style.set("padding", "var(--lumo-space-s) var(--lumo-space-m)")
            style.set("cursor", "pointer")
            style.set("border-radius", "var(--lumo-border-radius-m)")
            style.set("margin", "1px var(--lumo-space-xs)")
            style.set("transition", "background-color 0.2s")
            style.set("color", "rgba(255,255,255,0.7)")

            val cartIcon = VaadinIcon.CART.create().apply {
                setSize("18px")
                style.set("flex-shrink", "0")
            }
            val labelSpan = Span("Purchases").apply {
                style.set("font-size", "var(--lumo-font-size-s)")
                style.set("flex-grow", "1")
            }
            purchasesParentBadge = Span().apply {
                style.set("background-color", "var(--lumo-error-color)")
                style.set("color", "white")
                style.set("font-size", "var(--lumo-font-size-xxs)")
                style.set("font-weight", "700")
                style.set("padding", "1px 6px")
                style.set("border-radius", "9999px")
                style.set("flex-shrink", "0")
                isVisible = false
                element.setAttribute("title", "Multi-pack titles awaiting expansion")
            }
            add(cartIcon, labelSpan, purchasesParentBadge, purchasesChevron)

            addClickListener {
                val wasVisible = purchasesChildren.isVisible
                purchasesChildren.isVisible = !wasVisible
                purchasesChevron.style.set("transform", if (wasVisible) "rotate(0deg)" else "rotate(180deg)")
            }
        }

        purchasesChildren = VerticalLayout().apply {
            isPadding = false
            isSpacing = false
            width = "100%"
            isVisible = false
            add(pImportItem.first, pExpandItem.first, pValuationItem.first, pWishesItem.first, pOwnershipItem.first, pReportItem.first)
        }

        // Transcodes collapsible group
        val tcStatusItem = createDrawerItem(null, "Status", "transcodes/status", indent = true)
        val tcUnmatchedItem = createDrawerItem(null, "Unmatched", "transcodes/unmatched", indent = true)
        unmatchedBadge = Span().apply {
            style.set("background-color", "var(--lumo-error-color)")
            style.set("color", "white")
            style.set("font-size", "var(--lumo-font-size-xxs)")
            style.set("font-weight", "700")
            style.set("padding", "1px 6px")
            style.set("border-radius", "9999px")
            style.set("margin-left", "auto")
            style.set("flex-shrink", "0")
            isVisible = false
            element.setAttribute("title", "NAS files not yet matched to catalog titles")
        }
        tcUnmatchedItem.first.add(unmatchedBadge)
        val tcLinkedItem = createDrawerItem(null, "Linked", "transcodes/linked", indent = true)
        val tcBacklogItem = createDrawerItem(null, "Backlog", "transcodes/backlog", indent = true)

        transcodesChevron = VaadinIcon.CHEVRON_DOWN.create().apply {
            setSize("14px")
            style.set("transition", "transform 0.2s")
            style.set("flex-shrink", "0")
        }
        transcodesParent = HorizontalLayout().apply {
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            isPadding = false
            isSpacing = true
            width = "100%"
            style.set("padding", "var(--lumo-space-s) var(--lumo-space-m)")
            style.set("cursor", "pointer")
            style.set("border-radius", "var(--lumo-border-radius-m)")
            style.set("margin", "1px var(--lumo-space-xs)")
            style.set("transition", "background-color 0.2s")
            style.set("color", "rgba(255,255,255,0.7)")

            val filmIcon = VaadinIcon.FILM.create().apply {
                setSize("18px")
                style.set("flex-shrink", "0")
            }
            val labelSpan = Span("Transcodes").apply {
                style.set("font-size", "var(--lumo-font-size-s)")
                style.set("flex-grow", "1")
            }
            transcodesParentBadge = Span().apply {
                style.set("background-color", "var(--lumo-error-color)")
                style.set("color", "white")
                style.set("font-size", "var(--lumo-font-size-xxs)")
                style.set("font-weight", "700")
                style.set("padding", "1px 6px")
                style.set("border-radius", "9999px")
                style.set("flex-shrink", "0")
                isVisible = false
                element.setAttribute("title", "Unmatched NAS files need attention")
            }
            add(filmIcon, labelSpan, transcodesParentBadge, transcodesChevron)

            addClickListener {
                val wasVisible = transcodesChildren.isVisible
                transcodesChildren.isVisible = !wasVisible
                transcodesChevron.style.set("transform", if (wasVisible) "rotate(0deg)" else "rotate(180deg)")
            }
        }

        transcodesChildren = VerticalLayout().apply {
            isPadding = false
            isSpacing = false
            width = "100%"
            isVisible = false
            add(tcStatusItem.first, tcUnmatchedItem.first, tcLinkedItem.first, tcBacklogItem.first)
        }

        items.add(dataQualityItem)
        items.add(addItemItem)
        items.add(pImportItem)
        items.add(pExpandItem)
        items.add(pValuationItem)
        items.add(pWishesItem)
        items.add(pOwnershipItem)
        items.add(camerasSettingsItem)
        items.add(liveTvSettingsItem)
        items.add(settingsItem)
        items.add(tagsItem)
        items.add(tcStatusItem)
        items.add(tcUnmatchedItem)
        items.add(tcLinkedItem)
        items.add(tcBacklogItem)
        items.add(usersItem)

        adminSection = VerticalLayout().apply {
            isPadding = false
            isSpacing = false
            width = "100%"
            add(divider, manageHeader)
            add(dataQualityItem.first, addItemItem.first, camerasSettingsItem.first, liveTvSettingsItem.first, purchasesParent, purchasesChildren,
                settingsItem.first, tagsItem.first, transcodesParent, transcodesChildren, usersItem.first)
        }

        drawerItems = items

        val drawerContent = VerticalLayout().apply {
            isPadding = false
            isSpacing = false
            width = "100%"
            style.set("padding-top", "var(--lumo-space-s)")
            add(mainSection, adminSection)
        }

        // Hidden IntegerField triggers Vaadin's UIDL to include lazy-chunk hashes
        // that transitively load the Dialog web component. Without a trigger component
        // like IntegerField, pages with only base-bundle components (Grid, TextField,
        // Button) never load the Dialog chunk, causing Dialog to render inline.
        // This replaces the fragile loadOnDemand hash hack. See docs/DIALOG_NOT_WORKING.md.
        drawerContent.add(IntegerField().apply { isVisible = false })

        addToDrawer(drawerContent)

        // Style the AppLayout drawer and navbar via shadow DOM
        addAttachListener {
            element.executeJs(
                "requestAnimationFrame(function(){" +
                "var r=this.shadowRoot; if(!r) return;" +
                "if(r.querySelector('#ml-style')) return;" +
                "var s=document.createElement('style'); s.id='ml-style';" +
                "s.textContent='" +
                "[part=drawer]{background-color:#1E1E1E;" +
                "border-right:1px solid rgba(255,255,255,0.1)}" +
                "[part=navbar]{background-color:#1E1E1E;" +
                "border-bottom:1px solid rgba(255,255,255,0.1)}" +
                "';" +
                "r.appendChild(s);" +
                "}.bind(this))"
            )
        }
    }

    private fun createDrawerItem(
        icon: VaadinIcon?, label: String, route: String, indent: Boolean = false
    ): Pair<HorizontalLayout, String> {
        val layout = HorizontalLayout().apply {
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            isPadding = false
            isSpacing = true
            width = "100%"
            val leftPad = if (indent) "calc(var(--lumo-space-m) + 26px)" else "var(--lumo-space-m)"
            style.set("padding", "var(--lumo-space-s) var(--lumo-space-m) var(--lumo-space-s) $leftPad")
            style.set("cursor", "pointer")
            style.set("border-radius", "var(--lumo-border-radius-m)")
            style.set("margin", "1px var(--lumo-space-xs)")
            style.set("transition", "background-color 0.2s")
            style.set("color", "rgba(255,255,255,0.7)")

            if (icon != null) {
                val iconComponent = icon.create().apply {
                    setSize("18px")
                    style.set("flex-shrink", "0")
                }
                add(iconComponent)
            }
            val labelSpan = Span(label).apply {
                style.set("font-size", "var(--lumo-font-size-s)")
            }
            add(labelSpan)

            addClickListener {
                ui.ifPresent { it.navigate(route) }
            }
        }
        return layout to route
    }

    private fun highlightDrawerItem(layout: HorizontalLayout, active: Boolean) {
        if (active) {
            layout.style.set("background-color", "rgba(255,255,255,0.1)")
            layout.style.set("color", "#FFFFFF")
            layout.style.set("font-weight", "600")
        } else {
            layout.style.remove("background-color")
            layout.style.set("color", "rgba(255,255,255,0.7)")
            layout.style.set("font-weight", "normal")
        }
    }

    private fun createSearchComboBox(): ComboBox<SearchItem> {
        return ComboBox<SearchItem>().apply {
            placeholder = "Search..."
            isClearButtonVisible = true
            isAllowCustomValue = false

            // Flexible width — no min-width so mobile CSS can collapse it
            style.set("max-width", "400px")
            style.set("flex-grow", "1")

            // Search icon prefix
            prefixComponent = VaadinIcon.SEARCH.create().apply {
                style.set("color", "rgba(255,255,255,0.4)")
                setSize("16px")
            }

            setItemLabelGenerator { it.displayName }

            // Load all non-hidden titles, filtered by rating ceiling and personal hide
            val searchUser = AuthService.getCurrentUser()
            val personallyHiddenIds = UserTitleFlagService.getHiddenTitleIds()
            val titleItems = Title.findAll()
                .filter { !it.hidden }
                .filter { it.id !in personallyHiddenIds }
                .filter { searchUser == null || searchUser.canSeeRating(it.content_rating) }
                .map { SearchItem.TitleItem(it) }

            // Load actors, deduplicated by tmdb_person_id
            val actorItems = CastMember.findAll()
                .groupBy { it.tmdb_person_id }
                .map { (personId, members) ->
                    val first = members.first()
                    SearchItem.ActorItem(
                        name = first.name,
                        personId = personId,
                        headshotCastId = members.firstOrNull { it.headshot_cache_id != null }?.id ?: first.id,
                        popularity = members.maxOfOrNull { it.popularity ?: 0.0 } ?: 0.0
                    )
                }

            // Load collections with part counts
            val allParts = TmdbCollectionPart.findAll()
            val partCountByCollectionId = allParts.groupBy { it.collection_id }
                .mapValues { it.value.size }
            val collectionItems = TmdbCollection.findAll()
                .map { SearchItem.CollectionItem(it, partCountByCollectionId[it.id] ?: 0) }

            // Load tags with title counts
            val tagTitleCounts = TagService.getTagTitleCounts()
            val tagItems = TagService.getAllTags()
                .filter { (tagTitleCounts[it.id] ?: 0) > 0 }
                .map { SearchItem.TagItem(it, tagTitleCounts[it.id] ?: 0) }

            val allItems: List<SearchItem> = (titleItems + actorItems + collectionItems + tagItems)
                .sortedByDescending { it.popularity }

            setItems(
                ComboBox.ItemFilter<SearchItem> { item, filterString ->
                    if (filterString.length < 2) false
                    else when (item) {
                        is SearchItem.TitleItem -> {
                            val matchingIds = SearchIndexService.search(filterString)
                            matchingIds != null && item.title.id in matchingIds
                        }
                        is SearchItem.ActorItem -> {
                            val lower = filterString.lowercase()
                            item.displayName.lowercase().contains(lower)
                        }
                        is SearchItem.CollectionItem -> {
                            val lower = filterString.lowercase()
                            item.displayName.lowercase().contains(lower)
                        }
                        is SearchItem.TagItem -> {
                            val lower = filterString.lowercase()
                            item.displayName.lowercase().contains(lower)
                        }
                    }
                },
                allItems
            )

            // Custom dropdown renderer: thumbnail + name + meta
            setRenderer(ComponentRenderer<Component, SearchItem> { item ->
                HorizontalLayout().apply {
                    isPadding = false
                    isSpacing = true
                    defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                    style.set("padding", "var(--lumo-space-xs) 0")

                    when (item) {
                        is SearchItem.TitleItem -> {
                            val posterUrl = item.title.posterUrl(PosterSize.THUMBNAIL)
                            if (posterUrl != null) {
                                add(Image(posterUrl, item.title.name).apply {
                                    width = "30px"; height = "45px"
                                    style.set("object-fit", "cover")
                                    style.set("border-radius", "2px")
                                    style.set("flex-shrink", "0")
                                })
                            }

                            add(Div().apply {
                                style.set("display", "flex")
                                style.set("flex-direction", "column")
                                style.set("overflow", "hidden")

                                add(Span(item.title.name).apply {
                                    style.set("font-weight", "500")
                                    style.set("white-space", "nowrap")
                                    style.set("overflow", "hidden")
                                    style.set("text-overflow", "ellipsis")
                                })

                                val meta = listOfNotNull(
                                    item.title.media_type,
                                    item.title.release_year?.toString()
                                ).joinToString(" \u00b7 ")

                                add(Span(meta).apply {
                                    style.set("color", "var(--lumo-secondary-text-color)")
                                    style.set("font-size", "var(--lumo-font-size-xs)")
                                })
                            })
                        }
                        is SearchItem.ActorItem -> {
                            // Circular headshot thumbnail
                            if (item.headshotCastId != null) {
                                add(Image("/headshots/${item.headshotCastId}", item.name).apply {
                                    width = "36px"; height = "36px"
                                    style.set("border-radius", "50%")
                                    style.set("object-fit", "cover")
                                    style.set("flex-shrink", "0")
                                })
                            } else {
                                val initial = item.name.firstOrNull()?.uppercase() ?: "?"
                                add(Span(initial).apply {
                                    style.set("display", "flex")
                                    style.set("align-items", "center")
                                    style.set("justify-content", "center")
                                    style.set("width", "36px")
                                    style.set("height", "36px")
                                    style.set("border-radius", "50%")
                                    style.set("background", "rgba(255,255,255,0.15)")
                                    style.set("color", "rgba(255,255,255,0.6)")
                                    style.set("font-size", "var(--lumo-font-size-s)")
                                    style.set("font-weight", "bold")
                                    style.set("flex-shrink", "0")
                                })
                            }

                            add(Div().apply {
                                style.set("display", "flex")
                                style.set("flex-direction", "column")
                                style.set("overflow", "hidden")

                                add(Span(item.name).apply {
                                    style.set("font-weight", "500")
                                    style.set("white-space", "nowrap")
                                    style.set("overflow", "hidden")
                                    style.set("text-overflow", "ellipsis")
                                })

                                add(Span("Actor").apply {
                                    style.set("color", "var(--lumo-secondary-text-color)")
                                    style.set("font-size", "var(--lumo-font-size-xs)")
                                })
                            })
                        }
                        is SearchItem.CollectionItem -> {
                            // Collection icon
                            add(VaadinIcon.FOLDER.create().apply {
                                setSize("30px")
                                style.set("color", "rgba(255,255,255,0.6)")
                                style.set("flex-shrink", "0")
                            })

                            add(Div().apply {
                                style.set("display", "flex")
                                style.set("flex-direction", "column")
                                style.set("overflow", "hidden")

                                add(Span(item.displayName).apply {
                                    style.set("font-weight", "500")
                                    style.set("white-space", "nowrap")
                                    style.set("overflow", "hidden")
                                    style.set("text-overflow", "ellipsis")
                                })

                                add(Span("Collection \u00b7 ${item.titleCount} titles").apply {
                                    style.set("color", "var(--lumo-secondary-text-color)")
                                    style.set("font-size", "var(--lumo-font-size-xs)")
                                })
                            })
                        }
                        is SearchItem.TagItem -> {
                            add(VaadinIcon.TAG.create().apply {
                                setSize("30px")
                                style.set("color", "rgba(255,255,255,0.6)")
                                style.set("flex-shrink", "0")
                            })

                            add(Div().apply {
                                style.set("display", "flex")
                                style.set("flex-direction", "column")
                                style.set("overflow", "hidden")

                                add(Span(item.displayName).apply {
                                    style.set("font-weight", "500")
                                    style.set("white-space", "nowrap")
                                    style.set("overflow", "hidden")
                                    style.set("text-overflow", "ellipsis")
                                })

                                add(Span("Tag \u00b7 ${item.titleCount} titles").apply {
                                    style.set("color", "var(--lumo-secondary-text-color)")
                                    style.set("font-size", "var(--lumo-font-size-xs)")
                                })
                            })
                        }
                    }
                }
            })

            // Navigate on selection, then clear and dismiss mobile search mode
            addValueChangeListener { event ->
                val item = event.value ?: return@addValueChangeListener
                value = null
                ui.ifPresent { ui ->
                    ui.page.executeJs(
                        "document.querySelector('.navbar-header')?.classList.remove('search-active')"
                    )
                    when (item) {
                        is SearchItem.TitleItem -> ui.navigate("title/${item.title.id}")
                        is SearchItem.ActorItem -> ui.navigate("actor/${item.personId}")
                        is SearchItem.CollectionItem -> ui.navigate("content/collection/${item.collection.id}")
                        is SearchItem.TagItem -> ui.navigate("tag/${item.tag.id}")
                    }
                }
            }

            // Style shadow DOM after element is attached — pill shape, hide toggle
            addAttachListener {
                element.executeJs(
                    "requestAnimationFrame(function(){" +
                    "var r=this.shadowRoot; if(!r) return;" +
                    "if(r.querySelector('#scb')) return;" +
                    "var s=document.createElement('style'); s.id='scb';" +
                    "s.textContent='" +
                    "#toggleButton{display:none!important}" +
                    "[part~=input-field]{border-radius:9999px!important;" +
                    "background:rgba(255,255,255,0.1)!important;" +
                    "border-color:transparent!important;" +
                    "height:36px!important;min-height:36px!important;" +
                    "padding-right:var(--lumo-space-m)!important}" +
                    "[part~=input-field]::after{display:none!important}" +
                    "';" +
                    "r.appendChild(s);" +
                    "}.bind(this))"
                )

                // Set overlay width for wider dropdown results
                element.executeJs(
                    "if(!document.getElementById('scb-overlay')){" +
                    "var s=document.createElement('style');s.id='scb-overlay';" +
                    "s.textContent='vaadin-combo-box-overlay{width:400px!important}';" +
                    "document.head.appendChild(s)}"
                )
            }
        }
    }

    fun refreshExpandBadge(user: net.stewart.mediamanager.entity.AppUser? = AuthService.getCurrentUser()) {
        if (user?.isAdmin() == true) {
            val count = JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
                handle.createQuery("SELECT COUNT(*) FROM media_item WHERE expansion_status = 'NEEDS_EXPANSION'")
                    .mapTo(Int::class.java).one()
            }
            expandBadge.text = count.toString()
            expandBadge.isVisible = count > 0
            purchasesParentBadge.text = count.toString()
            purchasesParentBadge.isVisible = count > 0 && !purchasesChildren.isVisible
        } else {
            expandBadge.isVisible = false
            purchasesParentBadge.isVisible = false
        }
    }

    fun refreshDataQualityBadge(user: net.stewart.mediamanager.entity.AppUser? = AuthService.getCurrentUser()) {
        if (user?.isAdmin() == true) {
            val enrichmentCount = JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
                handle.createQuery(
                    "SELECT COUNT(*) FROM title WHERE enrichment_status IS NOT NULL AND enrichment_status <> 'ENRICHED' AND media_type <> 'PERSONAL'"
                ).mapTo(Int::class.java).one()
            }
            // Count media_item_title rows with freetext seasons but no structured joins
            val unparseableCount = JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
                handle.createQuery(
                    """SELECT COUNT(*) FROM media_item_title mit
                       JOIN title t ON t.id = mit.title_id
                       WHERE mit.seasons IS NOT NULL AND mit.seasons <> ''
                       AND t.media_type = 'TV'
                       AND NOT EXISTS (
                           SELECT 1 FROM media_item_title_season mits WHERE mits.media_item_title_id = mit.id
                       )"""
                ).mapTo(Int::class.java).one()
            }
            val count = enrichmentCount + unparseableCount
            dataQualityBadge.text = count.toString()
            dataQualityBadge.isVisible = count > 0
        } else {
            dataQualityBadge.isVisible = false
        }
    }

    fun refreshWishListBadge(user: net.stewart.mediamanager.entity.AppUser? = AuthService.getCurrentUser()) {
        if (user != null) {
            val count = JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
                handle.createQuery(
                    "SELECT COUNT(*) FROM wish_list_item WHERE user_id = :userId AND wish_type = 'MEDIA' AND status = 'FULFILLED'"
                ).bind("userId", user.id).mapTo(Int::class.java).one()
            }
            wishListBadge.text = count.toString()
            wishListBadge.isVisible = count > 0
        } else {
            wishListBadge.isVisible = false
        }
    }

    fun refreshUnmatchedBadge(user: net.stewart.mediamanager.entity.AppUser? = AuthService.getCurrentUser()) {
        if (user?.isAdmin() == true) {
            val count = JdbiOrm.jdbi().withHandle<Int, Exception> { handle ->
                handle.createQuery("SELECT COUNT(*) FROM discovered_file WHERE match_status = 'UNMATCHED'")
                    .mapTo(Int::class.java).one()
            }
            unmatchedBadge.text = count.toString()
            unmatchedBadge.isVisible = count > 0
            transcodesParentBadge.text = count.toString()
            transcodesParentBadge.isVisible = count > 0 && !transcodesChildren.isVisible
        } else {
            unmatchedBadge.isVisible = false
            transcodesParentBadge.isVisible = false
        }
    }

    override fun afterNavigation(event: AfterNavigationEvent) {
        val path = event.location.path

        // Highlight active drawer item
        drawerItems.forEach { (layout, route) ->
            highlightDrawerItem(layout, path == route)
        }

        // Content group: auto-expand and highlight parent when on any sub-route
        val contentRoutes = setOf("content/movies", "content/tv", "content/collections", "content/tags", "content/family", "cameras")
        val onContent = path in contentRoutes || path.startsWith("content/collection/")
        if (onContent) {
            contentChildren.isVisible = true
            contentChevron.style.set("transform", "rotate(180deg)")
        }
        highlightDrawerItem(contentParent, onContent)

        // Purchases group: auto-expand and highlight parent when on any sub-route
        val purchaseRoutes = setOf("import", "expand", "valuation", "purchase-wishes", "report")
        val onPurchases = path in purchaseRoutes
        if (onPurchases) {
            purchasesChildren.isVisible = true
            purchasesChevron.style.set("transform", "rotate(180deg)")
        }
        highlightDrawerItem(purchasesParent, onPurchases)

        // Transcodes group: auto-expand and highlight parent when on any sub-route
        val onTranscodes = path.startsWith("transcodes/")
        if (onTranscodes) {
            transcodesChildren.isVisible = true
            transcodesChevron.style.set("transform", "rotate(180deg)")
        }
        highlightDrawerItem(transcodesParent, onTranscodes)

        // Re-read current user for admin checks and profile display
        val user = AuthService.getCurrentUser()
        adminSection.isVisible = user?.isAdmin() == true
        profileDisplayName.text = user?.display_name ?: ""

        refreshWishListBadge(user)
        refreshUnmatchedBadge(user)
        refreshExpandBadge(user)
        refreshDataQualityBadge(user)

        // Auto-close drawer on mobile after navigation
        element.executeJs("if(window.innerWidth < 800) this.drawerOpened = false")
    }
}
