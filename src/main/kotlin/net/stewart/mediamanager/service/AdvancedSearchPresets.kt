package net.stewart.mediamanager.service

/**
 * Canonical dance presets for the advanced-search surface. Shared by
 * the HTTP handler and the gRPC CatalogService so iOS and the web
 * client see the exact same list.
 *
 * BPM ranges follow competition / social-dance standards. Time
 * signatures are the dance's core meter; most dances that "work" at
 * other time sigs still expect 4/4 (or 3/4 for the waltzes). If a
 * track's file has no time_signature yet, a search that specifies
 * one will exclude it — users can either wait for the madmom pass or
 * broaden the filter.
 *
 * The list is intentionally small; we curate for "dances a typical
 * ballroom / social-dance catalog wants to filter by." Niche styles
 * (Paso Doble, Nightclub Two-Step, Zouk, etc.) can be added by the
 * same pattern if it becomes useful.
 */
object AdvancedSearchPresets {

    data class Preset(
        /** Stable id — URL-safe, suitable for analytics / bookmarks. */
        val key: String,
        /** Human display name. */
        val name: String,
        /** Short blurb shown as a tooltip or subtitle. */
        val description: String,
        val bpmMin: Int? = null,
        val bpmMax: Int? = null,
        val timeSignature: String? = null
    )

    val ALL: List<Preset> = listOf(
        // --- International Standard (smooth) ---------------------------------
        Preset("slow_waltz",      "Slow Waltz",      "Also called English Waltz.",
            bpmMin = 84,  bpmMax = 90,  timeSignature = "3/4"),
        Preset("viennese_waltz",  "Viennese Waltz",  "Fast, turning waltz.",
            bpmMin = 174, bpmMax = 180, timeSignature = "3/4"),
        Preset("foxtrot",         "Foxtrot",         "Slow-quick-quick American smooth staple.",
            bpmMin = 112, bpmMax = 128, timeSignature = "4/4"),
        Preset("quickstep",       "Quickstep",       "High-tempo smooth dance with lots of jumps.",
            bpmMin = 192, bpmMax = 208, timeSignature = "4/4"),
        Preset("ballroom_tango",  "Ballroom Tango",  "International / American rhythm tango.",
            bpmMin = 120, bpmMax = 132, timeSignature = "4/4"),

        // --- International Latin ---------------------------------------------
        Preset("cha_cha",         "Cha-Cha",         "Triple-step Cuban 4/4.",
            bpmMin = 118, bpmMax = 128, timeSignature = "4/4"),
        Preset("rumba",           "Rumba",           "Slower Cuban 4/4; American ballroom uses similar range.",
            bpmMin = 96,  bpmMax = 108, timeSignature = "4/4"),
        Preset("samba",           "Samba",           "Brazilian bouncing feel.",
            bpmMin = 96,  bpmMax = 104, timeSignature = "4/4"),
        Preset("jive",            "Jive",            "Fast swing-adjacent Latin.",
            bpmMin = 160, bpmMax = 176, timeSignature = "4/4"),

        // --- Social dances ---------------------------------------------------
        Preset("hustle",          "Hustle",          "Disco-era partner dance.",
            bpmMin = 110, bpmMax = 124, timeSignature = "4/4"),
        Preset("east_coast_swing","East Coast Swing","Classic triple-step swing.",
            bpmMin = 136, bpmMax = 144, timeSignature = "4/4"),
        Preset("west_coast_swing","West Coast Swing","Slotted, slower swing.",
            bpmMin = 88,  bpmMax = 120, timeSignature = "4/4"),
        Preset("salsa",           "Salsa",           "Fast Latin club dance.",
            bpmMin = 180, bpmMax = 220, timeSignature = "4/4"),
        Preset("bachata",         "Bachata",         "Dominican partner dance, 4-count with hip accent.",
            bpmMin = 108, bpmMax = 130, timeSignature = "4/4"),
        Preset("argentine_tango", "Argentine Tango", "Milonga tempo; slower than ballroom tango.",
            bpmMin = 110, bpmMax = 140, timeSignature = "4/4"),
    )

    fun byKey(key: String): Preset? = ALL.firstOrNull { it.key == key }
}
