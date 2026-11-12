package net.stewart.mediamanager.entity

/**
 * Web UI themes. [id] is the value stored in `app_user.web_theme` and
 * the value of `data-theme` on the SPA's `<html>` element. Ids are
 * persisted: never rename one without a migration that rewrites the
 * stored values.
 *
 * One theme ships today. `classic` was retired on 2026-11-10 (V103
 * clears it from `app_user.web_theme`); the per-user pin, the default,
 * and the SPA's theme plumbing stay so a second theme can be added
 * without redoing them.
 */
enum class WebTheme(val id: String) {
    MARQUEE("marquee");

    companion object {
        /**
         * The built-in default for users who have not picked a theme
         * (`web_theme` is NULL). The only place the default lives; the
         * SPA learns it from `GET /api/v2/profile` (`default_theme`).
         */
        val DEFAULT: WebTheme = MARQUEE

        /** Returns the theme with this exact [id], or null for an unknown or retired id. */
        fun fromId(id: String?): WebTheme? = entries.firstOrNull { it.id == id }
    }
}
