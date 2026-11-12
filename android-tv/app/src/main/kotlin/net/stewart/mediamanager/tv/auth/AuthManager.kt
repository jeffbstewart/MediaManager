package net.stewart.mediamanager.tv.auth

import android.content.Context
import android.util.Base64
import net.stewart.mediamanager.tv.log.TvLog

/**
 * Navigation state derived from stored server config and accounts.
 */
enum class AppState {
    NEEDS_SERVER,       // no server configured
    NEEDS_LOGIN,        // server configured, no accounts saved
    PICK_ACCOUNT,       // multiple accounts, user must pick
    AUTHENTICATED       // single account auto-selected, or user already picked
}

/**
 * Manages server connection config and multiple user accounts.
 *
 * TV apps typically let several household members each have their own
 * login. Tokens are stored per-username so switching accounts doesn't
 * require re-entering credentials.
 */
class AuthManager(context: Context) {
    private val prefs = context.getSharedPreferences("mm_auth", Context.MODE_PRIVATE)

    companion object {
        /**
         * Version of the stored server configuration. Bump this when a
         * server endpoint migration must invalidate every previously
         * stored endpoint: [appState] discards any config stamped with an
         * older version, returning the app to the server-setup screen as
         * though no server had ever been configured.
         *
         * v2 (2026-08): the household hosting endpoint moved; v1 configs
         * all point at a dead address.
         */
        const val SERVER_CONFIG_VERSION = 2
        private const val KEY_CONFIG_VERSION = "server_config_version"
    }

    // ── Server connection ────────────────────────────────────────────

    // The server exposes gRPC, HTTP, and streaming on a single Armeria
    // port, so one host + one port fully describes the endpoint. (Older
    // builds stored split grpc/http host+port pairs; those keys are
    // pre-v2 and get purged by the config-version gate in appState.)

    var useTls: Boolean
        get() = prefs.getBoolean("use_tls", true)
        private set(value) { prefs.edit().putBoolean("use_tls", value).apply() }

    var host: String?
        get() = prefs.getString("server_host", null)
        private set(value) { prefs.edit().putString("server_host", value).apply() }

    var port: Int
        get() = prefs.getInt("server_port", if (useTls) 443 else 9090)
        private set(value) { prefs.edit().putInt("server_port", value).apply() }

    /** Friendly label for the login screen. */
    val serverHost: String? get() = host

    /** HTTP base URL for images and video. */
    val httpBaseUrl: String?
        get() {
            val h = host ?: return null
            val scheme = if (useTls) "https" else "http"
            val defaultPort = if (useTls) 443 else 80
            return if (port == defaultPort) "$scheme://$h" else "$scheme://$h:$port"
        }

    fun configureTlsServer(host: String, port: Int = 443) {
        this.useTls = true
        this.host = host
        this.port = port
        prefs.edit().putInt(KEY_CONFIG_VERSION, SERVER_CONFIG_VERSION).apply()
    }

    fun configurePlaintextServer(host: String, port: Int = 9090) {
        this.useTls = false
        this.host = host
        this.port = port
        prefs.edit().putInt(KEY_CONFIG_VERSION, SERVER_CONFIG_VERSION).apply()
    }

    /**
     * Forget the server endpoint but keep stored accounts. Access and
     * refresh tokens are issued by the server, not bound to its address,
     * so if the same server is later configured at a new address the
     * accounts sign straight back in.
     */
    fun clearServer() {
        prefs.edit()
            .remove("use_tls")
            .remove("server_host")
            .remove("server_port")
            // Legacy split-endpoint keys from pre-v2 builds.
            .remove("grpc_host")
            .remove("grpc_port")
            .remove("http_host")
            .remove("http_port")
            .remove(KEY_CONFIG_VERSION)
            .apply()
    }

    // ── Multi-account ────────────────────────────────────────────────

    /** Ordered list of stored usernames. */
    fun getAccountUsernames(): List<String> {
        val raw = prefs.getString("account_usernames", null) ?: return emptyList()
        return raw.split(",").filter { it.isNotEmpty() }
    }

    private fun setAccountUsernames(usernames: List<String>) {
        prefs.edit().putString("account_usernames", usernames.joinToString(",")).apply()
    }

    var activeUsername: String?
        get() = prefs.getString("active_username", null)
        private set(value) { prefs.edit().putString("active_username", value).apply() }

    /** Add or update an account after successful login. Automatically selects it. */
    fun addAccount(username: String, access: ByteArray, refresh: ByteArray) {
        val usernames = getAccountUsernames().toMutableList()
        if (username !in usernames) usernames.add(username)
        setAccountUsernames(usernames)

        prefs.edit()
            .putString("acct_${username}_access", Base64.encodeToString(access, Base64.NO_WRAP))
            .putString("acct_${username}_refresh", Base64.encodeToString(refresh, Base64.NO_WRAP))
            .apply()

        activeUsername = username
    }

    fun removeAccount(username: String) {
        val usernames = getAccountUsernames().toMutableList()
        usernames.remove(username)
        setAccountUsernames(usernames)

        prefs.edit()
            .remove("acct_${username}_access")
            .remove("acct_${username}_refresh")
            .apply()

        if (activeUsername == username) {
            activeUsername = usernames.firstOrNull()
        }
    }

    fun selectAccount(username: String) {
        activeUsername = username
    }

    /** Deselect the active account (return to picker). */
    fun deselectAccount() {
        activeUsername = null
    }

    // ── Active account's token (used by GrpcClient and Coil) ─────────

    val accessToken: ByteArray?
        get() {
            val user = activeUsername ?: return null
            return prefs.getString("acct_${user}_access", null)
                ?.let { Base64.decode(it, Base64.NO_WRAP) }
        }

    val refreshToken: ByteArray?
        get() {
            val user = activeUsername ?: return null
            return prefs.getString("acct_${user}_refresh", null)
                ?.let { Base64.decode(it, Base64.NO_WRAP) }
        }

    // ── Derived navigation state ─────────────────────────────────────

    fun appState(): AppState {
        if (host == null || prefs.getInt(KEY_CONFIG_VERSION, 1) < SERVER_CONFIG_VERSION) {
            // No endpoint, or one stored before the current migration —
            // pre-migration endpoints point at a dead address. Discard and
            // re-run server setup; accounts survive and reconnect once the
            // new endpoint is entered.
            if (prefs.contains("server_host") || prefs.contains("grpc_host")) {
                TvLog.info("auth", "stored server config predates v$SERVER_CONFIG_VERSION, discarding endpoint")
                clearServer()
            }
            return AppState.NEEDS_SERVER
        }
        val accounts = getAccountUsernames()
        if (accounts.isEmpty()) return AppState.NEEDS_LOGIN
        if (accounts.size == 1) {
            // Auto-select the only account
            if (activeUsername != accounts[0]) activeUsername = accounts[0]
            return AppState.AUTHENTICATED
        }
        // Multiple accounts — need to pick if none active
        return if (activeUsername != null && activeUsername in accounts) {
            AppState.AUTHENTICATED
        } else {
            AppState.PICK_ACCOUNT
        }
    }
}
