package net.stewart.mediamanager

import com.github.mvysny.vaadinboot.VaadinBoot
import org.eclipse.jetty.ee10.servlet.ServletContextHandler
import org.eclipse.jetty.ee10.servlet.ServletHolder
import org.eclipse.jetty.server.ServerConnector
import net.stewart.mediamanager.service.AuthService
import net.stewart.mediamanager.service.DatabaseBackupService
import net.stewart.mediamanager.service.ForBrowserValidator
import net.stewart.mediamanager.service.NasScannerService
import net.stewart.mediamanager.service.MetricsRegistry
import net.stewart.mediamanager.service.SsdpResponder
import net.stewart.mediamanager.service.PopularityRefreshAgent
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
    var port: Int = 8080
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

    // Periodic maintenance: cleanup + database backup (every 24 hours)
    val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "maintenance-scheduler").apply { isDaemon = true }
    }
    scheduler.scheduleAtFixedRate({
        try {
            AuthService.cleanupOldAttempts()
            AuthService.cleanupExpiredTokens()
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
    Runtime.getRuntime().addShutdownHook(Thread { scheduler.shutdownNow() })

    // Internal-only server for /health and /metrics (not exposed to internet)
    val internalServer = startInternalServer(CommandLineFlags.internalPort)
    Runtime.getRuntime().addShutdownHook(Thread { internalServer.stop() })

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

    val boot = object : VaadinBoot() {
        override fun onStarted(context: org.eclipse.jetty.ee10.webapp.WebAppContext) {
            // Increase Jetty's idle timeout from 30s (default) to 5 minutes.
            // Roku streaming requests can have long pauses between range fetches;
            // the default 30s timeout kills the connection mid-playback.
            val server = context.server
            for (connector in server.connectors) {
                if (connector is org.eclipse.jetty.server.AbstractConnector) {
                    connector.idleTimeout = 300_000 // 5 minutes
                    log.info("Jetty connector idle timeout set to {}ms", connector.idleTimeout)
                }
            }
        }
    }
    boot.port = CommandLineFlags.port
    if (CommandLineFlags.listenOnAllInterfaces) {
        log.info("Listening on all IPv4 interfaces")
    } else {
        log.info("Listening only on loopback")
        boot.listenOn = "127.0.0.1"
    }
    boot.run()
}

/**
 * Starts a lightweight Jetty server on [port] serving /health, /metrics, /admin/logs, and /admin/requests.
 * Binds to all interfaces so Docker healthcheck and LAN Prometheus can reach it,
 * but the port is not forwarded through the router so it's not internet-accessible.
 */
fun startInternalServer(port: Int): org.eclipse.jetty.server.Server {
    val log = LoggerFactory.getLogger("net.stewart.mediamanager.InternalServer")
    val server = org.eclipse.jetty.server.Server()
    val connector = ServerConnector(server).apply { this.port = port }
    server.addConnector(connector)

    val context = ServletContextHandler(ServletContextHandler.NO_SESSIONS)
    context.contextPath = "/"
    context.addServlet(ServletHolder(HealthServlet()), "/health")
    context.addServlet(ServletHolder(MetricsServlet()), "/metrics")
    context.addServlet(ServletHolder(AppLogServlet()), "/admin/logs")
    context.addServlet(ServletHolder(RequestLogServlet()), "/admin/requests")
    server.handler = context

    server.start()
    log.info("Internal server started on port {} (health + metrics)", port)
    return server
}
