package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.LocalDateTime

object MetricsRegistry {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    init {
        JvmMemoryMetrics().bindTo(registry)
        JvmGcMetrics().bindTo(registry)
        JvmThreadMetrics().bindTo(registry)
        ClassLoaderMetrics().bindTo(registry)
        ProcessorMetrics().bindTo(registry)
        UptimeMetrics().bindTo(registry)
    }

    fun countHttpResponse(servlet: String, status: Int) {
        registry.counter("mm_http_responses_total", "servlet", servlet, "status", status.toString()).increment()
    }

    fun registerEntityGauges() {
        registry.gauge("mm_active_sessions", this) {
            try {
                JdbiOrm.jdbi().withHandle<Double, Exception> { handle ->
                    handle.createQuery("SELECT COUNT(*) FROM session_token WHERE expires_at > :now")
                        .bind("now", LocalDateTime.now())
                        .mapTo(Int::class.java)
                        .one()
                        .toDouble()
                }
            } catch (_: Exception) { 0.0 }
        }
    }
}
