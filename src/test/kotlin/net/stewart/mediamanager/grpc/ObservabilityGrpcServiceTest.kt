package net.stewart.mediamanager.grpc

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import net.stewart.logging.BinnacleExporter
import org.junit.After
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObservabilityGrpcServiceTest : GrpcTestBase() {

    private val captured = CopyOnWriteArrayList<BinnacleExporter.CapturedRecord>()

    @After
    fun clearCapture() {
        BinnacleExporter.captureForTests = null
        captured.clear()
    }

    private fun installCapture() {
        BinnacleExporter.captureForTests = { captured.add(it) }
    }

    @Test
    fun `client-streamed records are forwarded per service name`(): Unit = runBlocking {
        installCapture()
        val user = createAdminUser()
        val channel = authenticatedChannel(user)
        val stub = ObservabilityServiceGrpcKt.ObservabilityServiceCoroutineStub(channel)

        val ack = stub.streamLogs(flow {
            emit(logRecord {
                serviceName = "mediamanager-ios"
                serviceVersion = "2.1.0"
                severity = LogSeverity.LOG_SEVERITY_INFO
                loggerName = "playback"
                message = "started playback"
            })
            emit(logRecord {
                serviceName = "mediamanager-android-tv"
                serviceVersion = "1.4.0"
                severity = LogSeverity.LOG_SEVERITY_WARN
                loggerName = "network"
                message = "retrying request"
                attributes["retry_count"] = "3"
            })
        })

        assertEquals(2, ack.recordsForwarded)
        assertEquals(0, ack.recordsRejected)
        assertEquals(2, captured.size)

        val ios = captured.single { it.serviceName == "mediamanager-ios" }
        assertEquals("2.1.0", ios.serviceVersion)
        assertEquals("playback", ios.loggerName)
        assertEquals("started playback", ios.message)

        val tv = captured.single { it.serviceName == "mediamanager-android-tv" }
        assertEquals("1.4.0", tv.serviceVersion)
        assertEquals("retrying request", tv.message)
        assertEquals("3", tv.attributes["retry_count"])

        channel.shutdownNow()
    }

    @Test
    fun `records without service_name are rejected and do not sever the stream`(): Unit = runBlocking {
        installCapture()
        val user = createAdminUser()
        val channel = authenticatedChannel(user)
        val stub = ObservabilityServiceGrpcKt.ObservabilityServiceCoroutineStub(channel)

        val ack = stub.streamLogs(flow {
            emit(logRecord {
                // service_name intentionally blank — should be rejected.
                severity = LogSeverity.LOG_SEVERITY_INFO
                message = "missing identity"
            })
            emit(logRecord {
                serviceName = "mediamanager-ios"
                severity = LogSeverity.LOG_SEVERITY_INFO
                message = "valid record"
            })
            emit(logRecord {
                // Also blank.
                severity = LogSeverity.LOG_SEVERITY_INFO
                message = "also missing"
            })
        })

        assertEquals(1, ack.recordsForwarded)
        assertEquals(2, ack.recordsRejected)
        assertEquals(1, captured.size)
        assertTrue(captured.all { it.serviceName == "mediamanager-ios" },
            "Only the valid record should have reached the sink")

        channel.shutdownNow()
    }

    @Test
    fun `exception fields and attributes are forwarded`(): Unit = runBlocking {
        installCapture()
        val user = createAdminUser()
        val channel = authenticatedChannel(user)
        val stub = ObservabilityServiceGrpcKt.ObservabilityServiceCoroutineStub(channel)

        stub.streamLogs(flow {
            emit(logRecord {
                serviceName = "mediamanager-ios"
                severity = LogSeverity.LOG_SEVERITY_ERROR
                loggerName = "playback"
                message = "playback failed"
                exceptionType = "AVPlayerError"
                exceptionMessage = "decoder not found"
                exceptionStacktrace = "stack..."
                attributes["device_model"] = "iPhone15,3"
                attributes["os_version"] = "18.4"
            })
        })

        assertEquals(1, captured.size)
        val record = captured[0]
        assertEquals("AVPlayerError", record.exceptionType)
        assertEquals("decoder not found", record.exceptionMessage)
        assertEquals("stack...", record.exceptionStackTrace)
        assertEquals("iPhone15,3", record.attributes["device_model"])
        assertEquals("18.4", record.attributes["os_version"])

        channel.shutdownNow()
    }
}
