package net.stewart.logging

import org.slf4j.ILoggerFactory
import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Logger factory that creates [BufferingLogger] instances.
 * Caches loggers by name (same behavior as slf4j-simple).
 */
class BufferingLoggerFactory : ILoggerFactory {

    private val loggerMap = ConcurrentHashMap<String, BufferingLogger>()

    override fun getLogger(name: String): Logger {
        return loggerMap.computeIfAbsent(name) { BufferingLogger(it) }
    }
}
