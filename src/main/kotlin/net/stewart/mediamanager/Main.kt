package net.stewart.mediamanager

import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.DatabaseBackupService
import net.stewart.mediamanager.service.ForBrowserValidator
import net.stewart.mediamanager.service.ForMobileService
import net.stewart.mediamanager.service.NasScannerService
import net.stewart.mediamanager.service.LegalRequirements
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.Go2rtcAgent
import net.stewart.mediamanager.service.EventLoopPacemaker
import net.stewart.mediamanager.service.HealthWatchdog
import net.stewart.mediamanager.service.JwtService
import net.stewart.mediamanager.service.LiveTvStreamManager
import net.stewart.mediamanager.service.SsdpResponder
import net.stewart.mediamanager.service.CollectionRefreshAgent
import net.stewart.mediamanager.service.PopularityRefreshAgent
import net.stewart.mediamanager.service.PriceLookupAgent
import net.stewart.mediamanager.service.TmdbEnrichmentAgent
import net.stewart.mediamanager.service.TranscoderAgent
import net.stewart.mediamanager.service.UpcLookupAgent
import org.h2.tools.Server
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object CommandLineFlags {
    var developerMode: Boolean = false
    var listenOnAllInterfaces: Boolean = false
    var port: Int = 9090
    var h2ConsolePort: Int = 8082
    var maxTranscodeDeletes: Int = 25
    var disableLocalTranscoding: Boolean = false
    var internalPort: Int = 8081

    fun parseFlags(args: Array<String>) {
        developerMode = args.contains("--developer_mode")
        listenOnAllInterfaces = args.contains("--listen_on_all_interfaces")
        disableLocalTranscoding = args.contains("--disable_local_transcoding")
        args.forEachIndexed { i, arg ->
            when (arg) {
                "--port" -> args.getOrNull(i + 1)?.toIntOrNull()?.let { port = it }
                "--h2_console_port" -> args.getOrNull(i + 1)?.toIntOrNull()?.let { h2ConsolePort = it }
                "--max_transcode_deletes" -> args.getOrNull(i + 1)?.toIntOrNull()?.let { maxTranscodeDeletes = it }
                "--internal_port" -> args.getOrNull(i + 1)?.toIntOrNull()?.let { internalPort = it }
            }
        }
    }
}

fun main(args: Array<String>) {
    val log = LoggerFactory.getLogger("net.stewart.mediamanager.Main")
    CommandLineFlags.parseFlags(args)

    Bootstrap.init()
    LegalRequirements.refresh()
    MetricsRegistry.registerEntityGauges()

    val lookupAgent = UpcLookupAgent()
    lookupAgent.start()
    Runtime.getRuntime().addShutdownHook(Thread { lookupAgent.stop() })

    val enrichmentAgent = TmdbEnrichmentAgent()
    enrichmentAgent.start()
    Runtime.getRuntime().addShutdownHook(Thread { enrichmentAgent.stop() })

    if (CommandLineFlags.disableLocalTranscoding) {
        log.info("Local transcoding DISABLED (--disable_local_transcoding). Remote buddies can still claim work.")
    } else {
        val transcoderAgent = TranscoderAgent()
        transcoderAgent.start()
        Runtime.getRuntime().addShutdownHook(Thread { transcoderAgent.stop() })
    }

    val validator = ForBrowserValidator()
    validator.start()
    Runtime.getRuntime().addShutdownHook(Thread { validator.stop() })

    val popularityAgent = PopularityRefreshAgent()
    popularityAgent.start()
    Runtime.getRuntime().addShutdownHook(Thread { popularityAgent.stop() })

    val collectionAgent = CollectionRefreshAgent()
    collectionAgent.start()
    Runtime.getRuntime().addShutdownHook(Thread { collectionAgent.stop() })

    val priceLookupAgent = PriceLookupAgent()
    PriceLookupAgent.instance = priceLookupAgent
    priceLookupAgent.start()
    Runtime.getRuntime().addShutdownHook(Thread { priceLookupAgent.stop() })

    val go2rtcAgent = Go2rtcAgent()
    Go2rtcAgent.instance = go2rtcAgent
    go2rtcAgent.start()
    Runtime.getRuntime().addShutdownHook(Thread { go2rtcAgent.stop() })

    LiveTvStreamManager.start()
    Runtime.getRuntime().addShutdownHook(Thread { LiveTvStreamManager.stopAll() })

    // Periodic maintenance: cleanup + database backup (every 24 hours)
    val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "maintenance-scheduler").apply { isDaemon = true }
    }
    scheduler.scheduleAtFixedRate({
        try {
            AuthService.cleanupOldAttempts()
            AuthService.cleanupExpiredTokens()
            JwtService.cleanupExpiredTokens()
        } catch (e: Exception) {
            log.warn("Periodic cleanup failed: {}", e.message)
        }
        try {
            DatabaseBackupService.runBackup()
        } catch (e: Exception) {
            log.warn("Database backup failed: {}", e.message)
        }
        try {
            NasScannerService.scan()
        } catch (e: Exception) {
            log.warn("Scheduled NAS scan failed: {}", e.message)
        }
    }, 24, 24, TimeUnit.HOURS)
    // Also run a backup immediately on startup; NAS scan after 5 minutes
    scheduler.schedule({
        try {
            DatabaseBackupService.runBackup()
        } catch (e: Exception) {
            log.warn("Startup database backup failed: {}", e.message)
        }
    }, 1, TimeUnit.MINUTES)
    scheduler.schedule({
        try {
            log.info("Running scheduled NAS scan")
            NasScannerService.scan()
        } catch (e: Exception) {
            log.warn("Startup NAS scan failed: {}", e.message)
        }
    }, 5, TimeUnit.MINUTES)
    scheduler.schedule({
        try {
            ForMobileService.reconcile()
        } catch (e: Exception) {
            log.warn("ForMobile reconciliation failed: {}", e.message)
        }
    }, 3, TimeUnit.MINUTES)
    Runtime.getRuntime().addShutdownHook(Thread { scheduler.shutdownNow() })

    // Armeria server: gRPC + REST + SPA on main port, monitoring on internal port
    val armeriaServer = net.stewart.mediamanager.grpc.ArmeriaServer.start(
        port = CommandLineFlags.port,
        internalPort = CommandLineFlags.internalPort
    )
    Runtime.getRuntime().addShutdownHook(Thread { net.stewart.mediamanager.grpc.ArmeriaServer.stop() })

    // Watchdog: probe the main port and dump threads if it stops responding
    val watchdog = HealthWatchdog(CommandLineFlags.port)
    watchdog.start()
    Runtime.getRuntime().addShutdownHook(Thread { watchdog.stop() })

    // Pacemaker: measure event loop queue latency (detects blocked event loops)
    val pacemaker = EventLoopPacemaker(armeriaServer)
    pacemaker.start()
    Runtime.getRuntime().addShutdownHook(Thread { pacemaker.stop() })

    // SSDP responder for Roku device discovery
    val ssdpResponder = SsdpResponder(CommandLineFlags.port)
    ssdpResponder.start()
    Runtime.getRuntime().addShutdownHook(Thread { ssdpResponder.shutdown() })

    if (CommandLineFlags.developerMode) {
        val h2Web = Server.createWebServer(
            "-webPort", CommandLineFlags.h2ConsolePort.toString(),
            "-webAllowOthers", "false"
        ).start()
        log.info("H2 Console started at http://localhost:${CommandLineFlags.h2ConsolePort} (localhost only)")
        Runtime.getRuntime().addShutdownHook(Thread { h2Web.stop() })
    }

    // Block main thread (Armeria server runs in its own threads)
    log.info("Media Manager started on port {} (internal: {})", CommandLineFlags.port, CommandLineFlags.internalPort)
    Thread.currentThread().join()
}
