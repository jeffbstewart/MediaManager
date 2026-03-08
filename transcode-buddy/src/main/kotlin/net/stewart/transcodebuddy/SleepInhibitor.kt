package net.stewart.transcodebuddy

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Prevents Windows from sleeping while the buddy is actively transcoding.
 *
 * Reference-counted: workers call [acquire] when starting a transcode and
 * [release] when finished. Sleep prevention is only active while at least
 * one worker holds a reference. When the last worker releases, Windows can
 * sleep again.
 *
 * Uses SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED) via a
 * background PowerShell process. The state is automatically cleared when the
 * process exits (normal shutdown or crash).
 *
 * No-op on non-Windows platforms.
 */
object SleepInhibitor {

    private val log = LoggerFactory.getLogger(SleepInhibitor::class.java)
    private var process: Process? = null
    private val refCount = AtomicInteger(0)
    private val lock = Any()

    /**
     * Called by a worker when it starts transcoding.
     * Activates sleep prevention on the 0 → 1 transition.
     */
    fun acquire() {
        val count = refCount.incrementAndGet()
        if (count == 1) {
            synchronized(lock) {
                if (process == null) {
                    startInhibitor()
                }
            }
        }
    }

    /**
     * Called by a worker when it finishes transcoding (success or failure).
     * Deactivates sleep prevention on the 1 → 0 transition.
     */
    fun release() {
        val count = refCount.decrementAndGet()
        if (count <= 0) {
            refCount.set(0) // prevent underflow
            synchronized(lock) {
                stopInhibitor()
            }
        }
    }

    /**
     * Force-release all references and stop. Called from shutdown hook.
     */
    fun shutdown() {
        refCount.set(0)
        synchronized(lock) {
            stopInhibitor()
        }
    }

    private fun startInhibitor() {
        if (!System.getProperty("os.name").lowercase().contains("win")) return
        if (process != null) return

        try {
            val script = """
                Add-Type -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint esFlags);' -Name Win32 -Namespace System
                [System.Win32]::SetThreadExecutionState(0x80000003) | Out-Null
                while (${'$'}true) { Start-Sleep 60; [System.Win32]::SetThreadExecutionState(0x80000003) | Out-Null }
            """.trimIndent()

            process = ProcessBuilder("powershell", "-NoProfile", "-Command", script)
                .redirectErrorStream(true)
                .start()

            Thread({
                process?.inputStream?.bufferedReader()?.lines()?.forEach { _ -> }
            }, "sleep-inhibitor-drain").apply {
                isDaemon = true
                start()
            }

            log.info("Sleep inhibitor active — Windows will stay awake while transcoding")
        } catch (e: Exception) {
            log.warn("Failed to activate sleep inhibitor: {}", e.message)
        }
    }

    private fun stopInhibitor() {
        if (process != null) {
            process?.destroyForcibly()
            process = null
            log.info("Sleep inhibitor released — Windows can sleep normally")
        }
    }
}
