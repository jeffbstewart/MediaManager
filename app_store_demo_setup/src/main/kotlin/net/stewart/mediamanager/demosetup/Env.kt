package net.stewart.mediamanager.demosetup

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Reads `app_store_demo_setup/secrets/.env` (gitignored, per-operator)
 * into a `Map<String, String>`. The file is shell-sourceable
 * `KEY=VALUE` lines — same format the lifecycle scripts use, so the
 * single file feeds both bash and the JVM seed tool.
 *
 * Lines that are blank or start with `#` are skipped. Values are
 * trimmed and surrounding single/double quotes are stripped. The
 * loader is forgiving about trailing CR (`\r`) on Windows-edited
 * files.
 */
internal object Env {

    /** Locate `app_store_demo_setup/secrets/.env`, mirroring [Tsv.locateFixtures]. */
    fun locateEnvFile(): Path {
        val candidates = listOf(
            Path.of("app_store_demo_setup", "secrets", ".env"),
            Path.of("..", "app_store_demo_setup", "secrets", ".env"),
            Path.of("secrets", ".env"),
        )
        for (rel in candidates) {
            val abs = rel.toAbsolutePath().normalize()
            if (abs.exists()) return abs
        }
        error(
            "could not find app_store_demo_setup/secrets/.env. Copy " +
            "app_store_demo_setup/secrets/example.env to .env, fill in " +
            "real values, and re-run from the repo root."
        )
    }

    fun load(path: Path = locateEnvFile()): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        Files.lines(path).use { stream ->
            for (raw in stream) {
                val line = raw.replace("\r", "").trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim()
                val rawValue = line.substring(eq + 1).trim()
                val value = stripQuotes(rawValue)
                out[key] = value
            }
        }
        return out
    }

    private fun stripQuotes(s: String): String {
        if (s.length < 2) return s
        val first = s[0]
        val last = s[s.length - 1]
        return if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            s.substring(1, s.length - 1)
        } else s
    }

    /** Throws with an actionable message if [key] is missing or blank. */
    fun require(env: Map<String, String>, key: String): String {
        val v = env[key]
        if (v.isNullOrBlank()) {
            error("missing or blank '$key' in secrets/.env. See app_store_demo_setup/secrets/example.env.")
        }
        return v
    }
}
