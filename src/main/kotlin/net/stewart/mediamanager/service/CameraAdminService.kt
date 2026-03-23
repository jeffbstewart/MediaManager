package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.Camera
import org.slf4j.LoggerFactory

/**
 * Shared camera CRUD and reorder logic, used by both the Vaadin web UI
 * (CameraSettingsView) and the gRPC AdminService.
 */
object CameraAdminService {

    private val log = LoggerFactory.getLogger(CameraAdminService::class.java)

    fun listAll(): List<Camera> {
        return Camera.findAll().sortedBy { it.display_order }
    }

    fun create(name: String, rtspUrl: String, snapshotUrl: String, streamName: String, enabled: Boolean): Camera {
        require(name.isNotBlank()) { "Name is required" }
        require(rtspUrl.isNotBlank()) { "RTSP URL is required" }
        require(rtspUrl.startsWith("rtsp://", ignoreCase = true)) { "RTSP URL must start with rtsp://" }

        val maxOrder = Camera.findAll().maxOfOrNull { it.display_order } ?: -1
        val camera = Camera(
            name = name.trim(),
            rtsp_url = rtspUrl.trim(),
            snapshot_url = snapshotUrl.trim(),
            go2rtc_name = streamName.trim().ifBlank { generateStreamName(name) },
            enabled = enabled,
            display_order = maxOrder + 1
        )
        camera.save()
        Go2rtcAgent.instance?.reconfigure()
        log.info("Camera created: '{}' (id={})", camera.name, camera.id)
        return camera
    }

    /**
     * Update a camera. URLs containing `***:***` are treated as redacted —
     * original credentials are preserved if the host/port match.
     */
    fun update(id: Long, name: String, rtspUrl: String, snapshotUrl: String, streamName: String, enabled: Boolean): Camera {
        val camera = Camera.findById(id) ?: throw IllegalArgumentException("Camera not found: $id")
        require(name.isNotBlank()) { "Name is required" }
        require(rtspUrl.isNotBlank()) { "RTSP URL is required" }

        // Restore credentials from the original URL if the edited URL has the redacted placeholder
        val resolvedRtsp = UriCredentialRedactor.restoreCredentials(rtspUrl.trim(), camera.rtsp_url)
        require(!resolvedRtsp.contains("***:***")) {
            "Cannot restore credentials — host/port changed. Enter full URL with credentials."
        }

        val resolvedSnapshot = if (snapshotUrl.isNotBlank()) {
            UriCredentialRedactor.restoreCredentials(snapshotUrl.trim(), camera.snapshot_url)
        } else ""
        require(!resolvedSnapshot.contains("***:***")) {
            "Cannot restore snapshot credentials — host/port changed. Enter full URL with credentials."
        }

        camera.name = name.trim()
        camera.rtsp_url = resolvedRtsp
        camera.snapshot_url = resolvedSnapshot
        camera.go2rtc_name = streamName.trim()
        camera.enabled = enabled
        camera.save()
        Go2rtcAgent.instance?.reconfigure()
        log.info("Camera updated: '{}' (id={})", camera.name, camera.id)
        return camera
    }

    fun delete(id: Long) {
        val camera = Camera.findById(id) ?: throw IllegalArgumentException("Camera not found: $id")
        val name = camera.name
        camera.delete()
        Go2rtcAgent.instance?.reconfigure()
        log.info("Camera deleted: '{}' (id={})", name, id)
    }

    /**
     * Reorder cameras. The [cameraIds] list defines the new display order:
     * position 0 gets display_order=0, position 1 gets display_order=1, etc.
     */
    fun reorder(cameraIds: List<Long>) {
        val camerasById = Camera.findAll().associateBy { it.id }
        for ((index, cameraId) in cameraIds.withIndex()) {
            val camera = camerasById[cameraId] ?: continue
            if (camera.display_order != index) {
                camera.display_order = index
                camera.save()
            }
        }
        log.info("Cameras reordered: {}", cameraIds)
    }

    fun generateStreamName(name: String): String {
        return name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    }
}
