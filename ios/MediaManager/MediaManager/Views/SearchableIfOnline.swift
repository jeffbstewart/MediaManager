import SwiftUI

extension View {
    /// `.searchable` gated on whether the app currently has a server
    /// connection. Offline mode suppresses the search affordance
    /// entirely — for the four search-bearing surfaces the user can
    /// reach offline (Movies / TV / Books / Music), search has no
    /// useful target without the server-side filtering / scoring it
    /// depends on. Catalog filters could in principle work offline
    /// against the local cache, but the UX is more honest when the
    /// box just isn't there to invite a typed query.
    ///
    /// `.searchable` isn't conditionally applyable behind a normal
    /// `if`. A `@ViewBuilder` branch swaps the modifier in / out
    /// based on `isOnline`. When the gate flips from on→off the
    /// caller is responsible for clearing the bound query, so a
    /// stale search doesn't keep filtering the page after the box
    /// disappears — use `.onChange(of: isOnline)` at the call site.
    @ViewBuilder
    func searchableIfOnline(
        text: Binding<String>,
        isOnline: Bool,
        prompt: String
    ) -> some View {
        if isOnline {
            self.searchable(text: text, prompt: prompt)
        } else {
            self
        }
    }
}
