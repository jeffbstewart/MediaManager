package net.stewart.mediamanager.service

import java.time.Duration

/**
 * In-memory test double for [SubprocessRunner]. Tests register rules
 * keyed by an arbitrary command-list predicate; the first rule that
 * matches the actual invocation wins. Unmatched calls fail the test
 * loudly so silent passes can't hide.
 *
 * Rules carry an optional `sideEffect` — a callback the fake invokes
 * before returning the canned result. That's how we model commands
 * like Essentia, which write their output to a path the caller passed
 * as an argv entry: the side effect drops the JSON file in the right
 * place so the caller's parser sees real bytes.
 */
internal class FakeSubprocessRunner : SubprocessRunner {

    /** Every command observed (one-shot run + streaming start), in order. */
    val invocations: MutableList<List<String>> = mutableListOf()

    /** Every streaming process the fake handed back, in start order. */
    val streamingInvocations: MutableList<FakeStreamingProcess> = mutableListOf()

    private val rules = mutableListOf<Rule>()
    private val startRules = mutableListOf<StartRule>()

    /**
     * Register a response. [matcher] receives the full argv; the first
     * rule whose [matcher] returns true wins. Later rules act as
     * fallbacks for the same matcher predicate.
     */
    fun on(
        matcher: (List<String>) -> Boolean,
        exitCode: Int = 0,
        stdout: String = "",
        stderr: String = "",
        timedOut: Boolean = false,
        sideEffect: ((List<String>) -> Unit)? = null,
    ) {
        rules += Rule(matcher, exitCode, stdout, stderr, timedOut, sideEffect)
    }

    /**
     * Convenience matcher — fires when the binary path of the
     * invocation ends with [name] (so `/usr/bin/ffprobe` and `ffprobe`
     * both match `"ffprobe"`).
     */
    fun onBinary(
        name: String,
        exitCode: Int = 0,
        stdout: String = "",
        stderr: String = "",
        timedOut: Boolean = false,
        sideEffect: ((List<String>) -> Unit)? = null,
    ) {
        on(
            matcher = { argv ->
                val first = argv.firstOrNull() ?: return@on false
                first == name || first.endsWith("/$name") || first.endsWith("\\$name")
            },
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            timedOut = timedOut,
            sideEffect = sideEffect,
        )
    }

    /**
     * Register a streaming-start response. Tests pass a [build] lambda
     * that returns a [FakeStreamingProcess] (each invocation can
     * customize, e.g. different stdout per call). The fake records the
     * argv and the produced process before handing it to the caller.
     *
     * Side effects (writing files, etc.) belong in [build] — it runs
     * before the streaming process is exposed to production code.
     */
    fun onStart(
        matcher: (List<String>) -> Boolean,
        build: (List<String>) -> FakeStreamingProcess = { FakeStreamingProcess() },
    ) {
        startRules += StartRule(matcher, build)
    }

    /** Convenience: match streaming starts by binary basename, like [onBinary]. */
    fun onStartBinary(
        name: String,
        build: (List<String>) -> FakeStreamingProcess = { FakeStreamingProcess() },
    ) {
        onStart(
            matcher = { argv ->
                val first = argv.firstOrNull() ?: return@onStart false
                first == name || first.endsWith("/$name") || first.endsWith("\\$name")
            },
            build = build,
        )
    }

    override fun start(
        command: List<String>,
        redirectErrorStream: Boolean,
        workingDir: java.nio.file.Path?,
    ): StreamingProcess {
        invocations += command
        val rule = startRules.firstOrNull { it.matcher(command) }
            ?: error(
                "FakeSubprocessRunner has no streaming rule for: " +
                    command.joinToString(" ") +
                    "\nAdd one with onStart(...) / onStartBinary(...) before the call."
            )
        val proc = rule.build(command)
        streamingInvocations += proc
        return proc
    }

    override fun run(
        command: List<String>,
        timeout: Duration,
        redirectErrorStream: Boolean,
    ): SubprocessResult {
        invocations += command
        val rule = rules.firstOrNull { it.matcher(command) }
            ?: error(
                "FakeSubprocessRunner has no rule for: ${command.joinToString(" ")}\n" +
                    "Add one with onBinary(...) or on(matcher) before the call."
            )
        rule.sideEffect?.invoke(command)
        // When redirectErrorStream is true, real ProcessBuilder dumps
        // stderr into stdout. Mirror that so callers don't see a split
        // in test that they wouldn't see in production.
        return if (redirectErrorStream) {
            SubprocessResult(
                exitCode = rule.exitCode,
                timedOut = rule.timedOut,
                stdout = rule.stdout + rule.stderr,
                stderr = "",
            )
        } else {
            SubprocessResult(
                exitCode = rule.exitCode,
                timedOut = rule.timedOut,
                stdout = rule.stdout,
                stderr = rule.stderr,
            )
        }
    }

    private class Rule(
        val matcher: (List<String>) -> Boolean,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        val sideEffect: ((List<String>) -> Unit)?,
    )

    private class StartRule(
        val matcher: (List<String>) -> Boolean,
        val build: (List<String>) -> FakeStreamingProcess,
    )
}
