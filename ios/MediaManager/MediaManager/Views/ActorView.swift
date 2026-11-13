import SwiftUI
import MediaManagerCore
import MediaManagerProtos

struct ActorRoute: Hashable {
    let tmdbPersonId: TmdbPersonID
    let name: String
}

struct ActorView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    @Environment(\.marquee) private var layout
    let route: ActorRoute
    @State private var detail: ApiActorDetail?
    @State private var loading = true
    @State private var bioExpanded = false
    @State private var localWished: [String: Bool] = [:]

    private var columns: [GridItem] {
        layout.gridColumns
    }

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .mmPage()
            } else if let detail {
                ScrollView {
                    VStack(alignment: .leading, spacing: layout.sectionGap) {
                        heroSection(detail)

                        if !detail.ownedTitles.isEmpty {
                            VStack(alignment: .leading, spacing: MarqueeTheme.Spacing.headToRow) {
                                MMSectionHead("In Your Collection")
                                MMRow {
                                    ForEach(detail.ownedTitles, id: \.title.id) { owned in
                                        NavigationLink(value: owned.title) {
                                            ownedCard(owned)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                            }
                        }

                        if !detail.otherWorks.isEmpty {
                            VStack(alignment: .leading, spacing: MarqueeTheme.Spacing.headToRow) {
                                MMSectionHead("Other Works")
                                LazyVGrid(columns: columns, spacing: layout.sectionGap) {
                                    ForEach(detail.otherWorks) { credit in
                                        creditCard(credit)
                                    }
                                }
                                .padding(.horizontal, layout.pageX)
                            }
                        }
                    }
                    .padding(.bottom, 48)
                }
                .mmGlowPage()
            } else {
                ContentUnavailableView("Actor not found", systemImage: "person.slash")
                    .mmPage()
            }
        }
        .navigationTitle(route.name)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await loadActor()
        }
    }

    @ViewBuilder
    private func heroSection(_ detail: ApiActorDetail) -> some View {
        VStack(spacing: 12) {
            // Round headshot over the seeded placeholder (the cast
            // row pattern from TitleDetailView, at hero size).
            ZStack {
                MMPlaceholderArt(name: detail.name, systemImage: "person.fill")
                CachedImage(
                    ref: .headshot(tmdbPersonId: route.tmdbPersonId.protoValue),
                    cornerRadius: 0,
                    transparentPlaceholder: true
                )
            }
            .frame(width: 128, height: 128)
            .clipShape(Circle())
            .overlay(Circle().strokeBorder(MarqueeTheme.Colors.hairline, lineWidth: 1))
            .shadow(color: .black.opacity(0.2), radius: 4, x: 0, y: 4)

            Text(detail.name)
                .font(layout.hero)
                .foregroundStyle(MarqueeTheme.Colors.text)
                .multilineTextAlignment(.center)
                .minimumScaleFactor(0.7)

            HStack(spacing: 8) {
                if let dept = detail.knownForDepartment {
                    Text(dept)
                }
                if let lifespan = lifespanText(detail) {
                    Text(lifespan)
                }
            }
            .font(MarqueeTheme.Fonts.meta)
            .foregroundStyle(MarqueeTheme.Colors.text2)

            if let place = detail.placeOfBirth {
                Text(place)
                    .font(MarqueeTheme.Fonts.meta)
                    .foregroundStyle(MarqueeTheme.Colors.text3)
            }

            if let bio = detail.biography, !bio.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text(bio)
                        .font(MarqueeTheme.Fonts.body)
                        .foregroundStyle(MarqueeTheme.Colors.text2)
                        .lineLimit(bioExpanded ? nil : 4)

                    if bio.count > 200 {
                        Button(bioExpanded ? "Show less" : "Show more") {
                            withAnimation { bioExpanded.toggle() }
                        }
                        .font(MarqueeTheme.Fonts.control)
                        .foregroundStyle(MarqueeTheme.Colors.accent)
                    }
                }
                .padding(.horizontal, layout.pageX)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top)
    }

    @ViewBuilder
    private func ownedCard(_ owned: ApiOwnedCredit) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            MMPosterTile(
                name: owned.title.name,
                ref: .posterThumbnail(titleId: owned.title.id.protoValue))
                .overlay(alignment: .bottomTrailing) {
                    // Tile-variant corner badge. Same shape used by
                    // CollectionDetailView, PosterCard, and
                    // ArtistDetailView so a downloaded item reads the
                    // same anywhere it appears on a poster tile.
                    if dataModel.downloads.offlineTitleIds.contains(owned.title.id.protoValue) {
                        MMArtBadge(systemImage: "arrow.down", label: "Downloaded")
                    }
                }

            MMCaption(
                title: owned.title.name,
                meta: owned.title.year.map(String.init),
                credit: owned.characterName,
                width: layout.posterWidth)
        }
        .frame(width: layout.posterWidth, alignment: .topLeading)
    }

    private func isWished(_ credit: ApiCreditEntry) -> Bool {
        localWished[credit.id] ?? credit.wished
    }

    @ViewBuilder
    private func creditCard(_ credit: ApiCreditEntry) -> some View {
        let wished = isWished(credit)
        VStack(alignment: .leading, spacing: 0) {
            ZStack(alignment: .topTrailing) {
                MMPosterTile(
                    name: credit.title,
                    ref: .tmdbPoster(
                        tmdbId: credit.tmdbId.protoValue,
                        mediaType: credit.mediaType.protoMediaType))

                Button {
                    Task { await toggleWish(credit) }
                } label: {
                    Image(systemName: wished ? "heart.fill" : "heart")
                        .font(.body)
                        .foregroundStyle(wished ? MarqueeTheme.Colors.pink : .white)
                        .padding(6)
                        .background(MarqueeTheme.Colors.scrimStrong, in: Circle())
                }
                .padding(4)
                .accessibilityLabel(wished ? "Remove \(credit.title) from wish list" : "Add \(credit.title) to wish list")
            }

            MMCaption(
                title: credit.title,
                meta: [credit.releaseYear.map(String.init), credit.mediaType == .tv ? "TV" : "Film"]
                    .compactMap { $0 }.joined(separator: " • "),
                credit: credit.characterName,
                width: layout.gridCellWidth)
        }
        .frame(width: layout.gridCellWidth, alignment: .topLeading)
    }

    private func lifespanText(_ detail: ApiActorDetail) -> String? {
        let birthYear = detail.birthday.map { String($0.prefix(4)) }
        let deathYear = detail.deathday.map { String($0.prefix(4)) }
        switch (birthYear, deathYear) {
        case let (b?, d?): return "(\(b) – \(d))"
        case let (b?, nil): return "Born \(b)"
        default: return nil
        }
    }

    private func toggleWish(_ credit: ApiCreditEntry) async {
        let currentlyWished = isWished(credit)
        localWished[credit.id] = !currentlyWished

        if currentlyWished {
            let response = try? await dataModel.wishList()
            if let wish = response?.wishes.first(where: { $0.tmdbId == credit.tmdbId && $0.mediaType == credit.mediaType }),
               let wishId = wish.wishId {
                try? await dataModel.deleteWish(id: wishId)
            }
        } else {
            try? await dataModel.addWish(
                tmdbId: credit.tmdbId,
                mediaType: credit.mediaType,
                title: credit.title,
                year: credit.releaseYear,
                seasonNumber: nil
            )
        }
    }

    private func loadActor() async {
        loading = detail == nil
        detail = try? await dataModel.actorDetail(id: route.tmdbPersonId)
        loading = false
    }
}
