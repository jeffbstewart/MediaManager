import SwiftUI

struct ActorRoute: Hashable {
    let tmdbPersonId: TmdbPersonID
    let name: String
}

struct ActorView: View {
    @Environment(OnlineDataModel.self) private var dataModel
    let route: ActorRoute
    @State private var detail: ApiActorDetail?
    @State private var loading = true
    @State private var bioExpanded = false
    @State private var localWished: [String: Bool] = [:]

    private let columns = [
        GridItem(.adaptive(minimum: 110), spacing: 12)
    ]

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading...")
            } else if let detail {
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        heroSection(detail)

                        if !detail.ownedTitles.isEmpty {
                            sectionHeader("In Your Collection")
                            ScrollView(.horizontal, showsIndicators: false) {
                                LazyHStack(spacing: 12) {
                                    ForEach(detail.ownedTitles, id: \.title.id) { owned in
                                        NavigationLink(value: owned.title) {
                                            ownedCard(owned)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                                .padding(.horizontal)
                            }
                        }

                        if !detail.otherWorks.isEmpty {
                            sectionHeader("Other Works")
                            LazyVGrid(columns: columns, spacing: 16) {
                                ForEach(detail.otherWorks) { credit in
                                    creditCard(credit)
                                }
                            }
                            .padding(.horizontal)
                        }
                    }
                    .padding(.bottom)
                }
            } else {
                ContentUnavailableView("Actor not found", systemImage: "person.slash")
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
            CachedImage(
                ref: .headshot(tmdbPersonId: route.tmdbPersonId.protoValue),
                cornerRadius: 60,
                contentMode: .fit
            )
            .frame(width: 120, height: 120)

            Text(detail.name)
                .font(.title2)
                .fontWeight(.bold)

            HStack(spacing: 8) {
                if let dept = detail.knownForDepartment {
                    Text(dept)
                }
                if let lifespan = lifespanText(detail) {
                    Text(lifespan)
                }
            }
            .font(.subheadline)
            .foregroundStyle(.secondary)

            if let place = detail.placeOfBirth {
                Text(place)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if let bio = detail.biography, !bio.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text(bio)
                        .font(.body)
                        .lineLimit(bioExpanded ? nil : 4)

                    if bio.count > 200 {
                        Button(bioExpanded ? "Show less" : "Show more") {
                            withAnimation { bioExpanded.toggle() }
                        }
                        .font(.caption)
                    }
                }
                .padding(.horizontal)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top)
    }

    @ViewBuilder
    private func ownedCard(_ owned: ApiOwnedCredit) -> some View {
        VStack(alignment: .center, spacing: 4) {
            CachedImage(ref: .posterThumbnail(titleId: owned.title.id.protoValue))
                .frame(width: 120, height: 180)

            Text(owned.title.name)
                .font(.caption)
                .lineLimit(2)
                .multilineTextAlignment(.center)

            if let year = owned.title.year {
                Text(String(year))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            if let character = owned.characterName {
                Text(character)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .frame(width: 120)
    }

    private func isWished(_ credit: ApiCreditEntry) -> Bool {
        localWished[credit.id] ?? credit.wished
    }

    @ViewBuilder
    private func creditCard(_ credit: ApiCreditEntry) -> some View {
        let wished = isWished(credit)
        VStack(alignment: .center, spacing: 4) {
            ZStack(alignment: .topTrailing) {
                if let posterUrl = credit.posterUrl, let url = URL(string: posterUrl) {
                    AsyncImage(url: url) { image in
                        image.resizable().aspectRatio(contentMode: .fill)
                    } placeholder: {
                        Rectangle().fill(.quaternary)
                            .overlay { Image(systemName: "film").foregroundStyle(.secondary) }
                    }
                    .frame(width: 120, height: 180)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                } else {
                    Rectangle().fill(.quaternary)
                        .frame(width: 120, height: 180)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .overlay { Image(systemName: "film").foregroundStyle(.secondary) }
                }

                Button {
                    Task { await toggleWish(credit) }
                } label: {
                    Image(systemName: wished ? "heart.fill" : "heart")
                        .font(.body)
                        .foregroundStyle(wished ? .red : .white)
                        .padding(6)
                        .background(.black.opacity(0.5))
                        .clipShape(Circle())
                }
                .padding(4)
            }

            Text(credit.title)
                .font(.caption)
                .lineLimit(2)
                .multilineTextAlignment(.center)

            HStack(spacing: 4) {
                if let year = credit.releaseYear {
                    Text(String(year))
                }
                Text(credit.mediaType == .tv ? "TV" : "Film")
            }
            .font(.caption2)
            .foregroundStyle(.secondary)

            if let character = credit.characterName {
                Text(character)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .frame(width: 120)
    }

    private func sectionHeader(_ text: String) -> some View {
        Text(text)
            .font(.title3)
            .fontWeight(.bold)
            .padding(.horizontal)
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
            let posterPath = credit.posterUrl?.replacingOccurrences(of: "https://image.tmdb.org/t/p/w500", with: "")
            try? await dataModel.addWish(
                tmdbId: credit.tmdbId,
                mediaType: credit.mediaType,
                title: credit.title,
                year: credit.releaseYear,
                posterPath: posterPath,
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
