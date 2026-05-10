package net.stewart.mediamanager.demosetup

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

/**
 * Seeds media wishes for the App Store demo server. Used to populate
 * the wishlist + purchase-wishes screenshot states with a mix of
 * statuses so those views render with realistic content.
 *
 * Two-phase:
 *   1. Login as `viewer` and POST /api/v2/wishlist/add for each row.
 *   2. Login as DEMO_ADMIN_USER and POST
 *      /api/v2/admin/purchase-wishes/set-status for rows that have a
 *      non-blank `admin_status`.
 *
 * Idempotent — addWish returns ok:false / "Already on wish list" on
 * a duplicate, which is treated as already-seeded. Status setting is
 * a pure upsert.
 *
 * Inputs:
 *   secrets/.env — DEMO_BASE_URL, DEMO_ADMIN_USER, DEMO_ADMIN_PASSWORD,
 *                  DEMO_VIEWER_PASSWORD.
 *   fixtures/wishes.tsv
 *
 * The `demoMedia` argument is unused (signature parity with the
 * other seed-* subcommands).
 */
internal object SeedWishes {

    private val gson = Gson()

    fun run(@Suppress("UNUSED_PARAMETER") demoMedia: Path) {
        val env = Env.load()
        val baseUrl = Env.require(env, "DEMO_BASE_URL").trimEnd('/')
        val adminUser = Env.require(env, "DEMO_ADMIN_USER")
        val adminPassword = Env.require(env, "DEMO_ADMIN_PASSWORD")
        val viewerPassword = Env.require(env, "DEMO_VIEWER_PASSWORD")

        val rows = Tsv.read(Tsv.locateFixtures().resolve("wishes.tsv"))
        if (rows.isEmpty()) {
            println("No rows in wishes.tsv — nothing to do.")
            return
        }
        println("${rows.size} wish(es) to seed from wishes.tsv")

        // Phase 1: as `viewer`, add each wish.
        val viewerClient = Http.newSessionClient()
        login(viewerClient, baseUrl, "viewer", viewerPassword)
        println("Logged in as viewer")

        // Re-runs: if a fixture row was previously added with a null
        // poster_path (early seed-wishes versions did this), cancel
        // the existing wish so we can re-add with a proper poster.
        // Only fixture-matched wishes are touched; other viewer-side
        // wishes survive.
        val fixtureKeys = rows.mapNotNull {
            val id = it["tmdb_id"].orEmpty().toIntOrNull() ?: return@mapNotNull null
            id to it["media_type"].orEmpty().uppercase()
        }.toSet()
        val viewerWishes = listViewerWishes(viewerClient, baseUrl)
        for (w in viewerWishes) {
            val key = w.tmdbId to w.mediaType
            if (key in fixtureKeys) {
                println("  existing: tmdb=${w.tmdbId} ${w.mediaType} poster=${w.posterPath ?: "(null)"}")
                cancelWish(viewerClient, baseUrl, w.wishId)
                println("  reset:    tmdb=${w.tmdbId} ${w.mediaType}")
            }
        }

        var added = 0
        var alreadyOnList = 0
        for (row in rows) {
            val title = row["title"].orEmpty()
            val tmdbId = row["tmdb_id"].orEmpty().toIntOrNull()
                ?: error("wishes.tsv row '$title' missing/invalid tmdb_id")
            // The TSV uses lowercase "movie"/"tv" for friendliness; the
            // API expects the MediaType enum name ("MOVIE"/"TV").
            val mediaType = row["media_type"].orEmpty().uppercase()
            require(mediaType == "MOVIE" || mediaType == "TV") {
                "wishes.tsv row '$title' has invalid media_type '${row["media_type"]}' (expected 'movie' or 'tv')"
            }
            val releaseYear = row["release_year"].orEmpty().toIntOrNull()
            // Look up the poster path via the server's TMDB search so
            // the wishlist UI renders the hero tile instead of an
            // empty placeholder. Without this, addWish accepts a
            // null poster_path and the SPA shows a broken-image alt.
            val posterPath = lookupPosterPath(viewerClient, baseUrl, title, tmdbId)
            val result = addWish(viewerClient, baseUrl, tmdbId, mediaType, title, releaseYear, posterPath)
            val ppLabel = posterPath ?: "(none)"
            when (result) {
                AddResult.ADDED -> { added++; println("  add:   $title ($tmdbId, $mediaType) poster=$ppLabel") }
                AddResult.DUP   -> { alreadyOnList++; println("  skip:  $title — already on wish list") }
            }
        }
        println("Wishes added: $added, already-on-list: $alreadyOnList")

        // Phase 2: as admin, set statuses for rows that specify one.
        val statusRows = rows.filter { it["admin_status"].orEmpty().isNotBlank() }
        if (statusRows.isEmpty()) {
            println("No admin_status values in wishes.tsv — skipping status phase.")
            return
        }

        val adminClient = Http.newSessionClient()
        login(adminClient, baseUrl, adminUser, adminPassword)
        println("Logged in as $adminUser")

        var statused = 0
        for (row in statusRows) {
            val title = row["title"].orEmpty()
            val tmdbId = row["tmdb_id"].orEmpty().toInt()
            val mediaType = row["media_type"].orEmpty().uppercase()
            val status = row["admin_status"].orEmpty()
            setStatus(adminClient, baseUrl, tmdbId, mediaType, status)
            println("  status: $title -> $status")
            statused++
        }
        println("Statuses set: $statused.")
    }

    // ---------------------- helpers ----------------------

    private enum class AddResult { ADDED, DUP }

    private data class ViewerWish(
        val wishId: Long, val tmdbId: Int, val mediaType: String, val posterPath: String?
    )

    /** Lists the currently-logged-in user's MEDIA wishes (id, tmdb_id, tmdb_media_type, poster_path). */
    private fun listViewerWishes(client: HttpClient, baseUrl: String): List<ViewerWish> {
        val req = HttpRequest.newBuilder(URI.create("$baseUrl/api/v2/wishlist"))
            .header("Accept", "application/json")
            .GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            error("listViewerWishes HTTP ${resp.statusCode()} — ${resp.body()}")
        }
        val root = JsonParser.parseString(resp.body()).asJsonObject
        val arr = root.getAsJsonArray("media_wishes") ?: return emptyList()
        val out = mutableListOf<ViewerWish>()
        for (el in arr) {
            val o = el.asJsonObject
            val id = o.get("id")?.asLong ?: continue
            val tmdb = o.get("tmdb_id")?.asInt ?: continue
            val mt = o.get("tmdb_media_type")?.asString ?: continue
            val pp = o.get("tmdb_poster_path")?.let { if (it.isJsonNull) null else it.asString }
            out.add(ViewerWish(id, tmdb, mt, pp))
        }
        return out
    }

    private fun cancelWish(client: HttpClient, baseUrl: String, wishId: Long) {
        val req = HttpRequest.newBuilder(URI.create("$baseUrl/api/v2/wishlist/$wishId"))
            .header("Accept", "application/json")
            .DELETE().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            error("cancelWish(id=$wishId) HTTP ${resp.statusCode()} — ${resp.body()}")
        }
    }

    private fun login(client: HttpClient, baseUrl: String, username: String, password: String) {
        val body = gson.toJson(mapOf(
            "username" to username,
            "password" to password,
            "device_name" to "demo-seed-wishes"
        ))
        val (status, respBody) = Http.postJson(client, "$baseUrl/api/v2/auth/login", body)
        if (status !in 200..299) error("login($username) HTTP $status — $respBody")
    }

    /**
     * Hits `/api/v2/wishlist/search` and finds the result row whose
     * tmdb_id matches ours, returning its poster_path. The endpoint
     * proxies TMDB's search, so the server's TMDB_API_KEY does the
     * real lookup. Returns null on any miss — addWish then stores a
     * null poster_path and the wishlist tile falls back to a
     * letter-placeholder, which is still better than failing the
     * whole seed.
     */
    private fun lookupPosterPath(
        client: HttpClient, baseUrl: String, title: String, tmdbId: Int
    ): String? {
        val q = java.net.URLEncoder.encode(title, java.nio.charset.StandardCharsets.UTF_8)
        val req = HttpRequest.newBuilder(URI.create("$baseUrl/api/v2/wishlist/search?q=$q"))
            .header("Accept", "application/json")
            .GET().build()
        return try {
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) return null
            val results = JsonParser.parseString(resp.body()).asJsonObject.getAsJsonArray("results")
            for (el in results) {
                val o = el.asJsonObject
                if (o.get("tmdb_id")?.asInt == tmdbId) {
                    val pp = o.get("poster_path")
                    return if (pp == null || pp.isJsonNull) null else pp.asString
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun addWish(
        client: HttpClient,
        baseUrl: String,
        tmdbId: Int,
        mediaType: String,
        title: String,
        releaseYear: Int?,
        posterPath: String?,
    ): AddResult {
        val payload = mutableMapOf<String, Any>(
            "tmdb_id" to tmdbId,
            "media_type" to mediaType,
            "title" to title,
        )
        if (releaseYear != null) payload["release_year"] = releaseYear
        if (posterPath != null) payload["poster_path"] = posterPath
        val (status, respBody) = Http.postJson(
            client, "$baseUrl/api/v2/wishlist/add", gson.toJson(payload)
        )
        if (status !in 200..299) {
            error("addWish('$title') HTTP $status — $respBody")
        }
        val parsed = JsonParser.parseString(respBody).asJsonObject
        return if (parsed.has("ok") && parsed.get("ok").asBoolean) AddResult.ADDED else AddResult.DUP
    }

    private fun setStatus(
        client: HttpClient,
        baseUrl: String,
        tmdbId: Int,
        mediaType: String,
        status: String
    ) {
        val body = gson.toJson(mapOf(
            "tmdb_id" to tmdbId,
            "media_type" to mediaType,
            "status" to status
        ))
        val (httpStatus, respBody) = Http.postJson(
            client, "$baseUrl/api/v2/admin/purchase-wishes/set-status", body
        )
        if (httpStatus !in 200..299) {
            error("setStatus(tmdb=$tmdbId, $status) HTTP $httpStatus — $respBody")
        }
    }
}
