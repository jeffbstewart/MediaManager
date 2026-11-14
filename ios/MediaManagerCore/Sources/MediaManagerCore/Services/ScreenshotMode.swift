import Foundation

/// Screenshot mode: the app shows no real title, person or artwork.
///
/// Published screenshots (the App Store listing) must not name actual
/// films, shows, books or albums, nor show their artwork. In this mode
/// the app can stay connected to a real server with a real library:
/// every name is swapped for an invented one (`Fiction`) where the
/// adapters hand text to the views, and every image is replaced by
/// generated abstract artwork (`GeneratedArt`) where `ImageProvider`
/// hands images to the views. Ids are untouched, so navigation works.
///
/// It is for taking screenshots only. Text the app sends back to the
/// server is not translated back, so do not edit names, add wishes or
/// save a profile while it is on. Video frames, book pages and
/// downloads saved earlier are not covered.
///
/// Two ways in:
///   - the `-MMSnapshotMode` launch argument, used by the UI test that
///     captures the App Store screenshots; and
///   - a stored switch, flipped by a hidden gesture on the About
///     screen (`ScreenshotMode.setStored`), for use on a device.
///
/// The value is read once per launch: flipping the stored switch takes
/// effect the next time the app starts, so nothing already loaded has
/// to be torn down.
public enum ScreenshotMode {
    /// Launch argument. Owned here; the capture test passes it.
    public static let launchArgument = "-MMSnapshotMode"
    /// UserDefaults key for the stored switch.
    static let defaultsKey = "mm.screenshotMode"

    public static let isActive: Bool =
        ProcessInfo.processInfo.arguments.contains(launchArgument)
        || UserDefaults.standard.bool(forKey: defaultsKey)

    /// What the stored switch says now (it may differ from `isActive`
    /// until the next launch).
    public static var stored: Bool {
        UserDefaults.standard.bool(forKey: defaultsKey)
    }

    public static func setStored(_ on: Bool) {
        UserDefaults.standard.set(on, forKey: defaultsKey)
    }
}

/// Invented stand-ins for real names. Each function returns its input
/// unchanged unless screenshot mode is active.
///
/// A name is chosen by hashing the real one, so the same title reads
/// the same on every screen without a lookup table to maintain, and a
/// library of any size is covered. The vocabulary is the one behind
/// the web app's published screenshots
/// (`web-app/tests/helpers/fictional-names.ts`: Harbor Lights, Glass
/// City, Far Orbit, Daniel Okafor, ...), widened so a grid of posters
/// rarely repeats a name.
public enum Fiction {

    // MARK: Titles (films, shows, episodes, books, albums, tracks)

    public static func title(_ real: String) -> String {
        guard ScreenshotMode.isActive, !real.isEmpty else { return real }
        return makeTitle(hash(real))
    }
    public static func title(_ real: String?) -> String? { real.map { title($0) } }

    /// A collection or series: an invented title with a suffix.
    public static func collection(_ real: String) -> String {
        guard ScreenshotMode.isActive, !real.isEmpty else { return real }
        return makeTitle(hash(real)) + " Collection"
    }

    // MARK: People

    public static func person(_ real: String) -> String {
        guard ScreenshotMode.isActive, !real.isEmpty else { return real }
        let h = hash(real)
        return "\(pick(firstNames, h)) \(pick(lastNames, h >> 9))"
    }
    public static func person(_ real: String?) -> String? { real.map { person($0) } }
    public static func people(_ real: [String]) -> [String] { real.map { person($0) } }

    /// "Last, First" forms used for sorting.
    public static func personSortName(_ real: String?) -> String? {
        guard ScreenshotMode.isActive, let real, !real.isEmpty else { return real }
        let h = hash(real)
        return "\(pick(lastNames, h >> 9)), \(pick(firstNames, h))"
    }

    /// A role in a cast list: a single given name.
    public static func character(_ real: String?) -> String? {
        guard ScreenshotMode.isActive, let real, !real.isEmpty else { return real }
        return pick(firstNames, hash(real) >> 5)
    }

    /// The signed-in user's own name, as in "Good evening, ...".
    public static func displayName(_ real: String) -> String {
        guard ScreenshotMode.isActive, !real.isEmpty else { return real }
        return pick(firstNames, hash(real) >> 3)
    }
    public static func displayName(_ real: String?) -> String? { real.map { displayName($0) } }

    // MARK: Longer text

    /// A plot summary or series description.
    public static func blurb(_ real: String) -> String {
        guard ScreenshotMode.isActive, !real.isEmpty else { return real }
        return pick(blurbs, hash(real))
    }
    public static func blurb(_ real: String?) -> String? { real.map { blurb($0) } }

    /// A person's biography.
    public static func biography(_ real: String?) -> String? {
        guard ScreenshotMode.isActive, let real, !real.isEmpty else { return real }
        return pick(biographies, hash(real))
    }

    public static func place(_ real: String?) -> String? {
        guard ScreenshotMode.isActive, let real, !real.isEmpty else { return real }
        return pick(places, hash(real))
    }

    // MARK: Other named things

    public static func recordLabel(_ real: String?) -> String? {
        guard ScreenshotMode.isActive, let real, !real.isEmpty else { return real }
        return pick(recordLabels, hash(real))
    }

    public static func camera(_ real: String) -> String {
        guard ScreenshotMode.isActive, !real.isEmpty else { return real }
        return pick(cameras, hash(real))
    }

    /// A broadcast channel: call signs become "Channel 7".
    public static func channel(_ real: String, number: String) -> String {
        guard ScreenshotMode.isActive, !real.isEmpty else { return real }
        let whole = number.split(separator: ".").first.map(String.init) ?? number
        return whole.isEmpty ? "Channel" : "Channel \(whole)"
    }

    // MARK: Generation

    private static func makeTitle(_ h: UInt32) -> String {
        let first = pick(titleFirsts, h)
        var second = pick(titleSeconds, h >> 8)
        if second == first { second = pick(titleSeconds, (h >> 8) &+ 1) }
        // One title in six takes a leading article.
        let article = (h >> 20) % 6 == 0 ? "The " : ""
        return "\(article)\(first) \(second)"
    }

    private static func pick(_ list: [String], _ h: UInt32) -> String {
        list[Int(h % UInt32(list.count))]
    }

    /// FNV-1a over the UTF-8 bytes: stable across launches and devices
    /// (Swift's own `hashValue` is seeded per process).
    static func hash(_ s: String) -> UInt32 {
        var h: UInt32 = 0x811c9dc5
        for byte in s.utf8 {
            h ^= UInt32(byte)
            h = h &* 0x01000193
        }
        // The low bits of FNV-1a mix poorly for short inputs; fold the
        // high half in so `%` over a small list spreads evenly.
        return h ^ (h >> 15)
    }

    // MARK: Vocabulary

    private static let titleFirsts = [
        "Harbor", "Glass", "Stone", "Far", "Quiet", "Night", "Paper", "Outer",
        "Second", "Lantern", "Little", "Blue", "Open", "Side", "Split", "Long",
        "Salt", "North", "Silver", "Winter", "Summer", "Hollow", "Copper", "Distant",
        "Low", "High", "Pale", "Still", "Iron", "Velvet", "Morning", "Evening",
        "Hidden", "Northern", "Southern", "Empty", "Crooked", "Borrowed", "Painted", "Narrow",
        "Slow", "Bright", "Granite", "Cedar", "Amber", "Tidal", "Upland", "Lower",
    ]
    private static let titleSeconds = [
        "Lights", "City", "Harbor", "Orbit", "Hours", "Market", "Lanterns", "Reach",
        "Sleep", "Road", "Kitchen", "Door", "Street", "Shift", "Marsh", "Field",
        "Tides", "Undertow", "Return", "Meridian", "Station", "Orchard", "Crossing", "Signal",
        "Parade", "Letters", "Harvest", "Weather", "Mile", "Room", "Coast", "Atlas",
        "Ferry", "Almanac", "Circuit", "Arcade", "Season", "Summit", "Quarter", "Ledger",
        "Platform", "Causeway", "Interval", "Pavilion", "Junction", "Terrace", "Estuary", "Foundry",
    ]
    private static let firstNames = [
        "Daniel", "Marcus", "Lena", "Peter", "Jesse", "Alice", "Theo", "Owen",
        "Ellis", "Nora", "Iris", "Caleb", "Maya", "Hugo", "Clara", "Felix",
        "Ruth", "Simon", "Tessa", "Victor", "Wren", "Amos", "Dara", "Elena",
        "Gideon", "Hattie", "Ivo", "June", "Kofi", "Lucia", "Milo", "Naomi",
        "Oscar", "Priya", "Quentin", "Rosa", "Soren", "Talia", "Uma", "Wyatt",
    ]
    private static let lastNames = [
        "Okafor", "Hale", "Marsh", "Vance", "Kline", "Rowan", "Marlowe", "Reyes",
        "Grant", "Castellan", "Vane", "Pembroke", "Ashby", "Bellamy", "Corrigan", "Delacroix",
        "Ellery", "Fenwick", "Garza", "Holloway", "Ibarra", "Jansen", "Kessler", "Lindqvist",
        "Moreau", "Nakata", "Oyelaran", "Prescott", "Quill", "Rasmussen", "Sato", "Thackeray",
        "Underhill", "Varga", "Whitlock", "Yoon", "Zeller", "Abara", "Brandt", "Calloway",
    ]
    private static let blurbs = [
        "A quiet town, a family business, and a secret nobody can keep.",
        "A coastal family's heir inherits the marsh and what lies beneath it.",
        "A crew leaves the solar system and finds the way home has moved.",
        "A night-shift programmer is told the city she knows is a story someone else is telling, and goes looking for the author.",
        "A small-town pharmacist keeps the family store open by means the town would not approve of.",
        "A sleep researcher is hired to make one patient forget a single afternoon, and finds it was never his to take.",
        "Two estranged sisters reopen their mother's ferry line the winter the river freezes for the first time in forty years.",
        "A cartographer sent to redraw a border discovers the village on it has been moving a little every night.",
        "When the last lighthouse keeper on the coast retires, his replacement finds a logbook that is still being written.",
        "A retired conductor takes a job at a rural radio station and starts answering letters meant for someone else.",
        "An apprentice clockmaker inherits a workshop, a debt, and one order that was never supposed to be finished.",
        "A night train stops at a station that is not on the timetable, and three passengers decide to get off.",
        "A market inspector with a perfect record is assigned to the one stall nobody has ever managed to find twice.",
        "Over one long summer, a harbor town prepares for a festival it has not held since the bridge came down.",
        "A translator hired for a single meeting realises both sides have been given different versions of the same letter.",
        "After the orchard is sold, the family that worked it for a century has one harvest left to decide what to keep.",
    ]
    private static let biographies = [
        "Trained in repertory theatre before moving to screen work, and known for understated supporting roles across three decades. Divides time between stage and film, and has taught workshops for young performers since the early part of a long career.",
        "Began as a session musician and arranger before leading a small ensemble of their own. The recordings from that period are noted for long takes and a preference for late-night studio hours.",
        "Worked as a merchant sailor and a schoolteacher before publishing a first novel in mid-life. The books that followed share a coastal setting and a habit of returning to the same few families.",
        "Started in regional television and came to wider attention with a run of character parts. Later credits include work as a producer and an occasional director.",
        "Raised in a harbor town and largely self-taught, with early work shown in community halls. Has since collaborated widely and is regarded as a generous mentor.",
    ]
    private static let places = [
        "Port Ellery", "Northfield", "Stone Harbor", "Saltmarsh", "Lantern Road", "Outer Reach",
    ]
    private static let recordLabels = [
        "Meridian Records", "Side Street Recordings", "Blue Hours Music", "Lantern Road Records",
        "Open Door Sound", "Harbor Lights Recordings",
    ]
    private static let cameras = [
        "Front Door", "Driveway", "Back Garden", "Garage", "Side Gate", "Porch", "Workshop", "Hallway",
    ]
}
