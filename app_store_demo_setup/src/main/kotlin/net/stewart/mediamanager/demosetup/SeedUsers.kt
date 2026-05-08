package net.stewart.mediamanager.demosetup

import java.nio.file.Path

/**
 * Creates the additional test accounts described in
 * `fixtures/users.tsv`. The very first admin still has to be
 * bootstrapped via the server's `/setup` wizard — admin-create
 * requires an existing admin to authenticate. This subcommand:
 *
 *   1. Reads `secrets/.env` for `DEMO_BASE_URL`, `DEMO_ADMIN_USER`,
 *      `DEMO_ADMIN_PASSWORD`.
 *   2. Logs in as that admin via `/api/v2/auth/login`.
 *   3. Iterates `fixtures/users.tsv`, creating each row via the
 *      admin user-management endpoint.
 *   4. For each row, reads the password from the named env var so
 *      real-looking credentials never land in the tracked TSV.
 */
internal object SeedUsers {
    fun run(demoMedia: Path) {
        TODO("not yet implemented — needs the auth-login + admin user-create " +
            "endpoints wired and the secrets/.env loader")
    }
}
