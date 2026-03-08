package net.stewart.mediamanager

import com.github.vokorm.findAll
import com.vaadin.flow.component.html.*
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import net.stewart.mediamanager.entity.CastMember
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TmdbId
import net.stewart.mediamanager.service.*

@Route(value = "actor/:personId", layout = MainLayout::class)
@PageTitle("Actor")
class ActorView : VerticalLayout(), BeforeEnterObserver {

    private val tmdbService = TmdbService()

    override fun beforeEnter(event: BeforeEnterEvent) {
        val personId = event.routeParameters.get("personId").orElse(null)?.toIntOrNull()
        if (personId == null) {
            event.forwardTo("")
            return
        }
        buildContent(personId)
    }

    private fun buildContent(personId: Int) {
        removeAll()
        isPadding = false
        isSpacing = false
        width = "100%"

        val person = tmdbService.fetchPersonDetails(personId)
        val allCredits = tmdbService.fetchPersonCredits(personId)

        // Find our titles featuring this actor
        val ownedCastMembers = CastMember.findAll().filter { it.tmdb_person_id == personId }
        val ownedTitleIds = ownedCastMembers.map { it.title_id }.distinct()
        val ownedTitles = if (ownedTitleIds.isNotEmpty()) {
            Title.findAll().filter { it.id in ownedTitleIds }
        } else emptyList()

        // Build a map of titleId -> character name from our cast data
        val characterByTitleId = ownedCastMembers.associate { it.title_id to it.character_name }

        add(buildHeroSection(person, personId))

        val contentArea = VerticalLayout().apply {
            isPadding = true
            isSpacing = true
            width = "100%"
            style.set("max-width", "1200px")
            style.set("margin", "0 auto")
            style.set("padding-top", "var(--lumo-space-l)")
        }

        buildOwnedSection(ownedTitles, characterByTitleId, contentArea)
        buildOtherWorksSection(allCredits, ownedTitles, contentArea)

        add(contentArea)
    }

    private fun buildHeroSection(person: TmdbPersonResult, personId: Int): HorizontalLayout {
        return HorizontalLayout().apply {
            width = "100%"
            isPadding = true
            isSpacing = true
            defaultVerticalComponentAlignment = FlexComponent.Alignment.START
            style.set("background", "linear-gradient(180deg, rgba(0,0,0,0.3) 0%, transparent 100%)")
            style.set("padding", "var(--lumo-space-xl)")

            // Headshot
            if (person.profilePath != null) {
                add(Image("https://image.tmdb.org/t/p/w300${person.profilePath}",
                    person.name ?: "Actor").apply {
                    width = "200px"
                    height = "200px"
                    style.set("border-radius", "50%")
                    style.set("object-fit", "cover")
                    style.set("box-shadow", "0 8px 24px rgba(0,0,0,0.4)")
                    style.set("flex-shrink", "0")
                })
            } else {
                // Placeholder circle
                val initial = (person.name ?: "?").firstOrNull()?.uppercase() ?: "?"
                add(Span(initial).apply {
                    style.set("display", "flex")
                    style.set("align-items", "center")
                    style.set("justify-content", "center")
                    style.set("width", "200px")
                    style.set("height", "200px")
                    style.set("border-radius", "50%")
                    style.set("background", "rgba(255,255,255,0.15)")
                    style.set("color", "rgba(255,255,255,0.6)")
                    style.set("font-size", "4em")
                    style.set("font-weight", "bold")
                    style.set("flex-shrink", "0")
                })
            }

            // Info column
            val info = VerticalLayout().apply {
                isPadding = false
                isSpacing = false
                style.set("max-width", "700px")

                val displayName = person.name ?: "Unknown Actor"
                add(H1(displayName).apply {
                    style.set("margin", "0 0 var(--lumo-space-s) 0")
                    style.set("font-size", "2em")
                    style.set("line-height", "1.2")
                })

                // Metadata row
                val metaRow = HorizontalLayout().apply {
                    isSpacing = true
                    isPadding = false
                    defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
                    style.set("gap", "var(--lumo-space-m)")
                    style.set("flex-wrap", "wrap")
                    style.set("margin-bottom", "var(--lumo-space-m)")

                    if (person.knownForDepartment != null) {
                        add(Span(person.knownForDepartment).apply {
                            style.set("color", "rgba(255,255,255,0.8)")
                            style.set("font-size", "var(--lumo-font-size-s)")
                        })
                    }

                    val lifespan = buildLifespan(person)
                    if (lifespan != null) {
                        add(Span(lifespan).apply {
                            style.set("color", "rgba(255,255,255,0.6)")
                            style.set("font-size", "var(--lumo-font-size-s)")
                        })
                    }

                    if (person.placeOfBirth != null) {
                        add(Span(person.placeOfBirth).apply {
                            style.set("color", "rgba(255,255,255,0.6)")
                            style.set("font-size", "var(--lumo-font-size-s)")
                        })
                    }
                }
                add(metaRow)

                // Biography
                if (!person.biography.isNullOrBlank()) {
                    val bioText = person.biography
                    val truncated = bioText.length > 500
                    val shortBio = if (truncated) bioText.substring(0, 500) + "..." else bioText

                    val bioParagraph = Paragraph(shortBio).apply {
                        style.set("color", "rgba(255,255,255,0.8)")
                        style.set("max-width", "700px")
                        style.set("line-height", "1.6")
                        style.set("margin", "0")
                    }
                    add(bioParagraph)

                    if (truncated) {
                        val toggleLink = Anchor("#", "Show more").apply {
                            style.set("color", "var(--lumo-primary-color)")
                            style.set("font-size", "var(--lumo-font-size-s)")
                            style.set("cursor", "pointer")
                            style.set("text-decoration", "none")
                        }
                        var expanded = false
                        toggleLink.element.addEventListener("click") {
                            expanded = !expanded
                            if (expanded) {
                                bioParagraph.text = bioText
                                toggleLink.text = "Show less"
                            } else {
                                bioParagraph.text = shortBio
                                toggleLink.text = "Show more"
                            }
                        }.addEventData("event.preventDefault()")
                        add(toggleLink)
                    }
                }
            }
            add(info)
            expand(info)
        }
    }

    private fun buildLifespan(person: TmdbPersonResult): String? {
        val birthYear = person.birthday?.takeIf { it.length >= 4 }?.substring(0, 4)
        val deathYear = person.deathday?.takeIf { it.length >= 4 }?.substring(0, 4)
        return when {
            birthYear != null && deathYear != null -> "($birthYear \u2013 $deathYear)"
            birthYear != null -> "Born $birthYear"
            else -> null
        }
    }

    private fun buildOwnedSection(
        ownedTitles: List<Title>,
        characterByTitleId: Map<Long, String?>,
        container: VerticalLayout
    ) {
        if (ownedTitles.isEmpty()) return

        container.add(H3("In Your Collection").apply {
            style.set("margin-top", "var(--lumo-space-l)")
            style.set("margin-bottom", "var(--lumo-space-s)")
        })

        val scrollRow = HorizontalLayout().apply {
            isSpacing = true
            isPadding = false
            width = "100%"
            style.set("overflow-x", "auto")
            style.set("padding-bottom", "var(--lumo-space-s)")
        }

        for (title in ownedTitles) {
            val character = characterByTitleId[title.id]
            scrollRow.add(buildOwnedTitleCard(title, character))
        }

        container.add(scrollRow)
    }

    private fun buildOwnedTitleCard(title: Title, characterName: String?): VerticalLayout {
        return VerticalLayout().apply {
            isPadding = false
            isSpacing = false
            width = "150px"
            style.set("min-width", "150px")
            style.set("align-items", "center")
            style.set("cursor", "pointer")

            // Poster
            val posterUrl = title.posterUrl(PosterSize.THUMBNAIL)
            if (posterUrl != null) {
                add(Image(posterUrl, title.name).apply {
                    width = "130px"
                    height = "195px"
                    style.set("border-radius", "8px")
                    style.set("object-fit", "cover")
                })
            } else {
                add(Div().apply {
                    style.set("width", "130px")
                    style.set("height", "195px")
                    style.set("border-radius", "8px")
                    style.set("background", "rgba(255,255,255,0.1)")
                    style.set("display", "flex")
                    style.set("align-items", "center")
                    style.set("justify-content", "center")
                    add(Span(title.name.take(1)).apply {
                        style.set("color", "rgba(255,255,255,0.4)")
                        style.set("font-size", "var(--lumo-font-size-xl)")
                    })
                })
            }

            // Title name
            add(Span(title.name).apply {
                style.set("color", "#FFFFFF")
                style.set("font-size", "var(--lumo-font-size-xs)")
                style.set("text-align", "center")
                style.set("margin-top", "var(--lumo-space-xs)")
                style.set("overflow", "hidden")
                style.set("text-overflow", "ellipsis")
                style.set("white-space", "nowrap")
                style.set("max-width", "150px")
            })

            // Year
            if (title.release_year != null) {
                add(Span(title.release_year.toString()).apply {
                    style.set("color", "rgba(255,255,255,0.5)")
                    style.set("font-size", "var(--lumo-font-size-xxs)")
                })
            }

            // Character
            if (characterName != null) {
                add(Span(characterName).apply {
                    style.set("color", "rgba(255,255,255,0.4)")
                    style.set("font-size", "var(--lumo-font-size-xxs)")
                    style.set("text-align", "center")
                    style.set("overflow", "hidden")
                    style.set("text-overflow", "ellipsis")
                    style.set("white-space", "nowrap")
                    style.set("max-width", "150px")
                })
            }

            // Click navigates to title detail
            element.addEventListener("click") {
                ui.ifPresent { it.navigate("title/${title.id}") }
            }
        }
    }

    private fun buildOtherWorksSection(
        allCredits: List<TmdbCreditEntry>,
        ownedTitles: List<Title>,
        container: VerticalLayout
    ) {
        if (allCredits.isEmpty()) return

        val ownedTmdbKeys = ownedTitles.mapNotNull { it.tmdbKey() }.toSet()
        val otherWorks = allCredits.filter { it.tmdbKey() !in ownedTmdbKeys }
        if (otherWorks.isEmpty()) return

        val wishedTmdbKeys =
            WishListService.getActiveMediaWishes()
                .mapNotNull { it.tmdbKey() }
                .toMutableSet()

        container.add(H3("Other Works").apply {
            style.set("margin-top", "var(--lumo-space-l)")
            style.set("margin-bottom", "var(--lumo-space-s)")
        })

        val grid = Div().apply {
            style.set("display", "grid")
            style.set("grid-template-columns", "repeat(auto-fill, minmax(150px, 1fr))")
            style.set("gap", "var(--lumo-space-m)")
            style.set("width", "100%")
        }

        for (credit in otherWorks) {
            grid.add(buildCreditCard(credit, wishedTmdbKeys))
        }

        container.add(grid)
    }

    private fun buildCreditCard(
        credit: TmdbCreditEntry,
        wishedTmdbKeys: MutableSet<TmdbId>
    ): VerticalLayout {
        return VerticalLayout().apply {
            isPadding = false
            isSpacing = false
            style.set("align-items", "center")
            style.set("position", "relative")

            // Poster wrapper with heart overlay
            val posterWrapper = Div().apply {
                style.set("position", "relative")
                style.set("width", "130px")
                style.set("height", "195px")
            }

            if (credit.posterPath != null) {
                posterWrapper.add(Image("https://image.tmdb.org/t/p/w185${credit.posterPath}",
                    credit.title).apply {
                    width = "130px"
                    height = "195px"
                    style.set("border-radius", "8px")
                    style.set("object-fit", "cover")
                })
            } else {
                posterWrapper.add(Div().apply {
                    style.set("width", "130px")
                    style.set("height", "195px")
                    style.set("border-radius", "8px")
                    style.set("background", "rgba(255,255,255,0.1)")
                    style.set("display", "flex")
                    style.set("align-items", "center")
                    style.set("justify-content", "center")
                    add(Span(credit.title.take(1)).apply {
                        style.set("color", "rgba(255,255,255,0.4)")
                        style.set("font-size", "var(--lumo-font-size-xl)")
                    })
                })
            }

            // Heart button overlay
            if (AuthService.getCurrentUser() != null) {
                val creditKey = credit.tmdbKey()
                val isWished = creditKey in wishedTmdbKeys
                val heartBtn = Button(
                    if (isWished) VaadinIcon.HEART.create() else VaadinIcon.HEART_O.create()
                ).apply {
                    addThemeVariants(ButtonVariant.LUMO_ICON, ButtonVariant.LUMO_TERTIARY)
                    style.set("position", "absolute")
                    style.set("top", "4px")
                    style.set("right", "4px")
                    style.set("background", "rgba(0,0,0,0.6)")
                    style.set("border-radius", "50%")
                    style.set("min-width", "32px")
                    style.set("width", "32px")
                    style.set("height", "32px")
                    style.set("padding", "0")
                    style.set("color", if (isWished) "var(--lumo-error-color)" else "rgba(255,255,255,0.8)")
                    element.setAttribute("title", if (isWished) "Remove from wish list" else "Add to wish list")

                    addClickListener {
                        val currentlyWished = creditKey in wishedTmdbKeys
                        if (currentlyWished) {
                            // Find and remove the wish
                            val wishes = WishListService.getActiveMediaWishes()
                            val wish = wishes.firstOrNull { it.tmdbKey() == creditKey }
                            if (wish != null) {
                                WishListService.removeWish(wish.id!!)
                                wishedTmdbKeys.remove(creditKey)
                                icon = VaadinIcon.HEART_O.create()
                                style.set("color", "rgba(255,255,255,0.8)")
                                element.setAttribute("title", "Add to wish list")
                                Notification.show("Removed from wish list", 2000, Notification.Position.BOTTOM_START)
                            }
                        } else {
                            // First-wish interstitial
                            val doAdd = {
                                val result = WishListService.addMediaWish(
                                    creditKey, credit.title,
                                    credit.posterPath, credit.releaseYear, credit.popularity
                                )
                                if (result != null) {
                                    wishedTmdbKeys.add(creditKey)
                                    icon = VaadinIcon.HEART.create()
                                    style.set("color", "var(--lumo-error-color)")
                                    element.setAttribute("title", "Remove from wish list")
                                    Notification.show("Added to wish list: ${credit.title}", 3000, Notification.Position.BOTTOM_START)
                                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
                                }
                            }

                            if (!WishListService.userHasAnyMediaWish()) {
                                val dialog = Dialog().apply {
                                    headerTitle = "Heads up"
                                    @Suppress("DEPRECATION")
                                    isModal = true

                                    add(Span("Your media wish list entries are shared with admins to help inform media purchase decisions. Continue?").apply {
                                        style.set("padding", "var(--lumo-space-m)")
                                    })

                                    val footer = HorizontalLayout().apply {
                                        justifyContentMode = FlexComponent.JustifyContentMode.END
                                        width = "100%"
                                        isSpacing = true
                                    }
                                    val cancelBtn = Button("Cancel") { close() }
                                    val confirmBtn = Button("Got it, add to wish list").apply {
                                        addThemeVariants(ButtonVariant.LUMO_PRIMARY)
                                        addClickListener {
                                            close()
                                            doAdd()
                                        }
                                    }
                                    footer.add(cancelBtn, confirmBtn)
                                    footer.element.setAttribute("slot", "footer")
                                    add(footer)
                                }
                                dialog.open()
                            } else {
                                doAdd()
                            }
                        }
                    }
                }
                posterWrapper.add(heartBtn)
            }

            add(posterWrapper)

            // Title
            add(Span(credit.title).apply {
                style.set("color", "#FFFFFF")
                style.set("font-size", "var(--lumo-font-size-xs)")
                style.set("text-align", "center")
                style.set("margin-top", "var(--lumo-space-xs)")
                style.set("overflow", "hidden")
                style.set("text-overflow", "ellipsis")
                style.set("white-space", "nowrap")
                style.set("max-width", "150px")
            })

            // Year + media type badge
            val metaParts = mutableListOf<String>()
            if (credit.releaseYear != null) metaParts.add(credit.releaseYear.toString())
            metaParts.add(if (credit.mediaType == "TV") "TV" else "Film")

            add(Span(metaParts.joinToString(" \u00b7 ")).apply {
                style.set("color", "rgba(255,255,255,0.5)")
                style.set("font-size", "var(--lumo-font-size-xxs)")
            })

            // Character
            if (credit.characterName != null) {
                add(Span(credit.characterName).apply {
                    style.set("color", "rgba(255,255,255,0.4)")
                    style.set("font-size", "var(--lumo-font-size-xxs)")
                    style.set("text-align", "center")
                    style.set("overflow", "hidden")
                    style.set("text-overflow", "ellipsis")
                    style.set("white-space", "nowrap")
                    style.set("max-width", "150px")
                })
            }
        }
    }
}
