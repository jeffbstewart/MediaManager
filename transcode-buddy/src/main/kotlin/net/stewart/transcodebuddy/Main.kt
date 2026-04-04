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

    // Connect to server via gRPC
    val grpcClient = BuddyGrpcClient(config)
    log.info("Connecting to gRPC server at {}", config.grpcAddress)
    val connected = grpcClient.connect()
    if (connected == null) {
        log.error("Cannot connect to server at {}", config.grpcAddress)
        System.err.println("Error: Cannot connect to gRPC server at ${config.grpcAddress}")
        System.err.println("Check grpc_address and api_key in your config file")
        grpcClient.shutdown()
        System.exit(1)
        return
    }
    log.info("Server connected: {} pending, {} resumable leases",
        connected.pendingCount, connected.resumableLeasesCount)

    // Initialize local file cache if configured
    val localCache = if (config.localTempDir != null) {
        val tempDir = File(config.localTempDir)
        log.info("Local file cache: {}", tempDir.absolutePath)
        val cache = LocalFileCache(tempDir, grpcClient)
        cache.startupCleanup()
        cache
    } else {
        log.info("Local file cache: disabled (no local_temp_dir configured)")
        null
    }

    val pathTranslator = PathTranslator(config.nasRoot)
    val running = AtomicBoolean(true)
    val executor = Executors.newFixedThreadPool(config.workerCount)

    // Create workers with status tracking
    val workers = (0 until config.workerCount).map { i ->
        TranscodeWorker(config, grpcClient, pathTranslator, encoder, i, running, localCache)
    }

    // Start status server
    val statusServer = StatusServer(
        port = config.statusPort,
        config = config,
        grpcClient = grpcClient,
        workerStatuses = workers.map { it.status }
    )
    statusServer.start()

    // Shutdown hook
    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutting down...")
        running.set(false)
        statusServer.stop()
        SleepInhibitor.shutdown()
        grpcClient.shutdown()
        executor.shutdownNow()
        executor.awaitTermination(10, TimeUnit.SECONDS)
        log.info("Shutdown complete")
    })

    // Start workers
    for (worker in workers) {
        executor.submit(worker)
    }

    log.info("Transcode Buddy running with {} worker(s). Status: http://localhost:{}/status", config.workerCount, config.statusPort)

    // Block main thread until shutdown
    try {
        while (running.get()) {
            Thread.sleep(1000)
        }
    } catch (_: InterruptedException) {
        // Expected on shutdown
    }
}
