package net.stewart.transcodebuddy

import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun main(args: Array<String>) {
    val log = LoggerFactory.getLogger("TranscodeBuddy")

    val configPath = args.firstOrNull() ?: "buddy.properties"
    log.info("Loading config from: {}", configPath)

    val config = try {
        BuddyConfig.load(configPath)
    } catch (e: Exception) {
        log.error("Failed to load config: {}", e.message)
        System.err.println("Error: ${e.message}")
        System.err.println("Usage: transcode-buddy [config-file]")
        System.err.println("  Default config file: buddy.properties")
        System.exit(1)
        return
    }

    log.info("Config loaded:")
    log.info("  Server:    {}", config.serverUrl)
    log.info("  Buddy:     {}", config.buddyName)
    log.info("  NAS Root:  {}", config.nasRoot)
    log.info("  Workers:   {}", config.workerCount)
    log.info("  Encoders:  {}", config.encoderPreference)

    // Verify NAS mount
    val nasDir = File(config.nasRoot)
    if (!nasDir.exists() || !nasDir.isDirectory) {
        log.error("NAS root not accessible: {}", config.nasRoot)
        System.err.println("Error: NAS root directory not found: ${config.nasRoot}")
        System.exit(1)
        return
    }

    // Verify FFmpeg
    if (!File(config.ffmpegPath).exists()) {
        log.error("FFmpeg not found: {}", config.ffmpegPath)
        System.err.println("Error: FFmpeg not found at: ${config.ffmpegPath}")
        System.exit(1)
        return
    }

    // Detect best encoder
    val encoder = EncoderDetector.detectBestEncoder(config.ffmpegPath, config.encoderPreference)
    log.info("Selected encoder: {} ({})", encoder.name, encoder.ffmpegEncoder)

    // Test server connectivity
    val apiClient = BuddyApiClient(config)
    val status = apiClient.getStatus()
    if (status == null) {
        log.error("Cannot reach server at {}", config.serverUrl)
        System.err.println("Error: Cannot connect to server at ${config.serverUrl}")
        System.err.println("Check server_url and api_key in your config file")
        System.exit(1)
        return
    }
    log.info("Server connected: {} pending, {} active leases, {} completed today",
        status.get("pending"), status.get("active_leases"), status.get("completed_today"))

    // Release any stale leases from a previous run of this buddy
    val released = apiClient.releaseLeases()
    if (released > 0) {
        log.info("Released {} stale lease(s) from previous session", released)
    }

    // Initialize local file cache if configured
    val localCache = if (config.localTempDir != null) {
        val tempDir = File(config.localTempDir)
        log.info("Local file cache: {}", tempDir.absolutePath)
        val cache = LocalFileCache(tempDir, apiClient)
        cache.startupCleanup()
        cache
    } else {
        log.info("Local file cache: disabled (no local_temp_dir configured)")
        null
    }

    val pathTranslator = PathTranslator(config.nasRoot)
    val running = AtomicBoolean(true)
    val executor = Executors.newFixedThreadPool(config.workerCount)

    // Shutdown hook
    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutting down...")
        running.set(false)
        SleepInhibitor.shutdown()
        executor.shutdownNow()
        executor.awaitTermination(10, TimeUnit.SECONDS)
        log.info("Shutdown complete")
    })

    // Start workers
    for (i in 0 until config.workerCount) {
        val worker = TranscodeWorker(config, apiClient, pathTranslator, encoder, i, running, localCache)
        executor.submit(worker)
    }

    log.info("Transcode Buddy running with {} worker(s). Press Ctrl+C to stop.", config.workerCount)

    // Block main thread until shutdown
    try {
        while (running.get()) {
            Thread.sleep(1000)
        }
    } catch (_: InterruptedException) {
        // Expected on shutdown
    }
}
