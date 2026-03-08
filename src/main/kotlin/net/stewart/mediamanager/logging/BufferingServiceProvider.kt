package net.stewart.mediamanager.logging

import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.helpers.BasicMDCAdapter
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider

/**
 * SLF4J 2.x service provider that creates [BufferingLogger] instances.
 * Logs to stderr (like slf4j-simple) and captures INFO/WARN/ERROR to
 * [net.stewart.mediamanager.service.AppLogBuffer] ring buffers.
 *
 * Fields are eagerly initialized because SLF4J's LoggerFactory.bind() calls
 * getMDCAdapter() before initialize().
 */
class BufferingServiceProvider : SLF4JServiceProvider {

    private var loggerFactory: ILoggerFactory = BufferingLoggerFactory()
    private var markerFactory: IMarkerFactory = BasicMarkerFactory()
    private var mdcAdapter: MDCAdapter = BasicMDCAdapter()

    override fun getLoggerFactory(): ILoggerFactory = loggerFactory
    override fun getMarkerFactory(): IMarkerFactory = markerFactory
    override fun getMDCAdapter(): MDCAdapter = mdcAdapter
    override fun getRequestedApiVersion(): String = "2.0.99"

    override fun initialize() {
        // Already initialized eagerly in field declarations
    }
}
