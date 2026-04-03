package net.stewart.mediamanager.tv.auth

import android.content.Context
import android.util.Base64

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

    // ── Server connection ────────────────────────────────────────────

    var useTls: Boolean
        get() = prefs.getBoolean("use_tls", true)
        private set(value) { prefs.edit().putBoolean("use_tls", value).apply() }

    var grpcHost: String?
        get() = prefs.getString("grpc_host", null)
        private set(value) { prefs.edit().putString("grpc_host", value).apply() }

    var grpcPort: Int
        get() = prefs.getInt("grpc_port", if (useTls) 8443 else 9090)
        private set(value) { prefs.edit().putInt("grpc_port", value).apply() }

    var httpHost: String?
        get() = prefs.getString("http_host", null)
        private set(value) { prefs.edit().putString("http_host", value).apply() }

    var httpPort: Int
        get() = prefs.getInt("http_port", if (useTls) 8443 else 8080)
        private set(value) { prefs.edit().putInt("http_port", value).apply() }

    /** Friendly label for the login screen. */
    val serverHost: String? get() = grpcHost

    /** HTTP base URL for images and video. */
    val httpBaseUrl: String?
        get() {
            val host = httpHost ?: return null
            val scheme = if (useTls) "https" else "http"
            return "$scheme://$host:$httpPort"
        }

    fun configureTlsServer(host: String, port: Int = 8443) {
        this.useTls = true
        this.grpcHost = host
        this.httpHost = host
        this.grpcPort = port
        this.httpPort = port
    }

    fun configurePlaintextServer(host: String, grpcPort: Int = 9090, httpPort: Int = 8080) {
        this.useTls = false
        this.grpcHost = host
        this.httpHost = host
        this.grpcPort = grpcPort
        this.httpPort = httpPort
    }

    fun clearServer() {
        prefs.edit().clear().apply()
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
        if (grpcHost == null) return AppState.NEEDS_SERVER
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
