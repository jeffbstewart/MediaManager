import SwiftUI

struct ActorRoute: Hashable {
    let tmdbPersonId: Int
    let name: String
}

struct ActorView: View {
    @Environment(AuthManager.self) private var authManager
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
                        // Hero section
                        heroSection(detail)

                        // Owned titles
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

                        // Other works
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
            AuthenticatedImage(
                path: detail.headshotUrl,
                apiClient: authManager.apiClient,
                cornerRadius: 60,
                contentMode: .fit
            )
            .frame(width: 120, height: 120)

            Text(detail.name)
                .font(.title2)
                .fontWeight(.bold)

            // Metadata row
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

            // Biography
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
            AuthenticatedImage(path: owned.title.posterUrl, apiClient: authManager.apiClient)
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
                // TMDB poster — public URL, no auth needed
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

                // Wish heart button
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
                Text(credit.mediaType == "TV" ? "TV" : "Film")
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

        // Update UI immediately
        localWished[credit.id] = !currentlyWished

        if currentlyWished {
            let response: ApiWishListResponse? = try? await authManager.apiClient.get("wishlist")
            if let wish = response?.wishes.first(where: { $0.tmdbId == credit.tmdbId && $0.mediaType == credit.mediaType }),
               let wishId = wish.wishId {
                try? await authManager.apiClient.delete("wishlist/\(wishId)")
            }
        } else {
            var body: [String: Any] = [
                "tmdb_id": credit.tmdbId,
                "media_type": credit.mediaType,
                "title": credit.title,
                "popularity": credit.popularity
            ]
            if let posterUrl = credit.posterUrl {
                body["poster_path"] = posterUrl.replacingOccurrences(of: "https://image.tmdb.org/t/p/w500", with: "")
            }
            if let year = credit.releaseYear {
                body["release_year"] = year
            }
            try? await authManager.apiClient.post("wishlist", body: body)
        }
    }

    private func loadActor() async {
        loading = detail == nil
        detail = try? await authManager.apiClient.get("catalog/actors/\(route.tmdbPersonId)")
        loading = false
    }
}
