package net.stewart.mediamanager.demosetup

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

/**
 * Creates the additional test accounts described in
 * `fixtures/users.tsv`. The very first admin still has to be
 * bootstrapped via the server's `/setup` wizard — admin-create
 * requires an existing admin to authenticate. This subcommand:
 *
 *   1. Reads `secrets/.env` for `DEMO_BASE_URL`, `DEMO_ADMIN_USER`,
 *      `DEMO_ADMIN_PASSWORD`, and the per-row password env vars
 *      named in the TSV's `password_env` column.
 *   2. Logs in as that admin via `/api/v2/auth/login` (carrying the
 *      mm_session cookie on a dedicated HttpClient).
 *   3. Iterates `fixtures/users.tsv`. For each row:
 *        - skips it if the username already exists (idempotent);
 *        - otherwise POSTs to /api/v2/admin/users with
 *          `force_change=false` so we can immediately log in as
 *          that account during screenshot capture without a
 *          password-change gate;
 *        - if `role=admin`, POSTs to `/promote`;
 *        - if `rating_ceiling` is set, POSTs to `/rating-ceiling`.
 *
 * The `demoMedia` argument is unused — the subcommand only needs
 * `secrets/.env` and `fixtures/users.tsv` — but the parameter is
 * kept for shape parity with the other `seed-*` subcommands so the
 * dispatcher in `Main.kt` doesn't need a special case.
 */
internal object SeedUsers {

    private val gson = Gson()

    fun run(@Suppress("UNUSED_PARAMETER") demoMedia: Path) {
        val env = Env.load()
        val baseUrl = Env.require(env, "DEMO_BASE_URL").trimEnd('/')
        val adminUser = Env.require(env, "DEMO_ADMIN_USER")
        val adminPassword = Env.require(env, "DEMO_ADMIN_PASSWORD")

        val rows = Tsv.read(Tsv.locateFixtures().resolve("users.tsv"))
        if (rows.isEmpty()) {
            println("No rows in users.tsv — nothing to do.")
            return
        }

        val client = Http.newSessionClient()
        login(client, baseUrl, adminUser, adminPassword)
        println("Logged in as $adminUser")

        val existing = fetchExistingUsers(client, baseUrl)
        println("${existing.size} user(s) already on the server: ${existing.keys.sorted()}")

        var created = 0
        var skipped = 0
        for (row in rows) {
            val username = row["username"].orEmpty()
            require(username.isNotEmpty()) { "users.tsv row has empty username: $row" }

            val role = row["role"].orEmpty().lowercase().ifEmpty { "viewer" }
            val ratingCeiling = row["rating_ceiling"].orEmpty().toIntOrNull()
            val passwordEnv = row["password_env"].orEmpty()
            require(passwordEnv.isNotEmpty()) {
                "users.tsv row '$username' missing password_env column"
            }
            val password = Env.require(env, passwordEnv)

            val existingId = existing[username]
            if (existingId != null) {
                println("  skip:   $username (already exists, id=$existingId)")
                skipped++
                // Re-runs should converge state. createUser is the only
                // step that's idempotent-by-skip; the others are safe
                // to re-apply.
                if (role == "admin") promote(client, baseUrl, existingId)
                if (ratingCeiling != null) setRatingCeiling(client, baseUrl, existingId, ratingCeiling)
                continue
            }

            createUser(client, baseUrl, username, password)
            val newId = fetchExistingUsers(client, baseUrl)[username]
                ?: error("created '$username' but it didn't appear in the user list")
            if (role == "admin") promote(client, baseUrl, newId)
            if (ratingCeiling != null) setRatingCeiling(client, baseUrl, newId, ratingCeiling)
            println("  create: $username role=$role ceiling=${ratingCeiling ?: "-"} id=$newId")
            created++
        }

        println("Done — created $created, skipped $skipped (already-exists).")
    }

    // ---------------------- HTTP helpers ----------------------

    private fun login(client: HttpClient, baseUrl: String, username: String, password: String) {
        val body = gson.toJson(mapOf(
            "username" to username,
            "password" to password,
            "device_name" to "demo-seed-tool"
        ))
        val (status, respBody) = Http.postJson(client, "$baseUrl/api/v2/auth/login", body)
        if (status !in 200..299) {
            error("login failed: HTTP $status — $respBody")
        }
    }

    private fun fetchExistingUsers(client: HttpClient, baseUrl: String): Map<String, Long> {
        val req = HttpRequest.newBuilder(URI.create("$baseUrl/api/v2/admin/users"))
            .header("Accept", "application/json")
            .GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            error("listing users failed: HTTP ${resp.statusCode()} — ${resp.body()}")
        }
        val root = JsonParser.parseString(resp.body()).asJsonObject
        val arr = root.getAsJsonArray("users")
        val out = LinkedHashMap<String, Long>()
        for (el in arr) {
            val o = el.asJsonObject
            out[o.get("username").asString] = o.get("id").asLong
        }
        return out
    }

    private fun createUser(client: HttpClient, baseUrl: String, username: String, password: String) {
        val body = gson.toJson(mapOf(
            "username" to username,
            "password" to password,
            "force_change" to false
        ))
        val (status, respBody) = Http.postJson(client, "$baseUrl/api/v2/admin/users", body)
        if (status !in 200..299) {
            error("createUser('$username') HTTP $status — $respBody")
        }
        // The endpoint returns 200 with {"ok":false,"error":"..."} on
        // username collision or password-policy failure — surface those
        // as errors instead of silent success.
        val parsed = JsonParser.parseString(respBody) as? JsonObject
        if (parsed != null && parsed.has("ok") && !parsed.get("ok").asBoolean) {
            error("createUser('$username') rejected: ${parsed.get("error")?.asString ?: respBody}")
        }
    }

    private fun promote(client: HttpClient, baseUrl: String, userId: Long) {
        val (status, respBody) = Http.postJson(client, "$baseUrl/api/v2/admin/users/$userId/promote", "")
        if (status !in 200..299) {
            error("promote(id=$userId) HTTP $status — $respBody")
        }
    }

    private fun setRatingCeiling(client: HttpClient, baseUrl: String, userId: Long, ceiling: Int) {
        val body = gson.toJson(mapOf("rating_ceiling" to ceiling))
        val (status, respBody) = Http.postJson(
            client, "$baseUrl/api/v2/admin/users/$userId/rating-ceiling", body
        )
        if (status !in 200..299) {
            error("setRatingCeiling(id=$userId, $ceiling) HTTP $status — $respBody")
        }
    }
}
