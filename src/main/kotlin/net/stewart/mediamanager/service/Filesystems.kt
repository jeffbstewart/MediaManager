package net.stewart.mediamanager.service

import java.nio.file.FileSystem
import java.nio.file.FileSystems

/**
 * Single swap point for the JVM-wide [FileSystem] used by disk-touching
 * code in this module. Production code reads [current] every time it
 * needs to resolve or check a path; tests substitute a Jimfs instance
 * for the lifetime of a test class via the test-only `JimfsRule`.
 *
 * Visibility is `internal` deliberately: nothing outside this module
 * should reference this object. Code outside the module should use
 * standard `java.nio.file.FileSystems.getDefault()` instead. Inside the
 * module, *production* code should never write [current] — only test
 * infrastructure does, and only inside a setup/teardown pair so the
 * default is restored.
 */
internal object Filesystems {
    /**
     * The active filesystem. Default [FileSystems.getDefault] in
     * production. The volatile read keeps it visible across threads
     * even when a test mutates it; the assumption is that swaps only
     * happen on the test thread before/after the body runs.
     */
    @Volatile
    internal var current: FileSystem = FileSystems.getDefault()
}
