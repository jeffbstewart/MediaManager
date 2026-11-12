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

    /**
     * Create a camera. All fields that reach the go2rtc config are validated
     * by [CameraSourceValidator]; failures throw [IllegalArgumentException].
     */
    fun create(
        name: String,
        rtspUrl: String,
        snapshotUrl: String,
        streamName: String,
        enabled: Boolean,
        clock: Clock = SystemClock
    ): Camera {
        require(name.isNotBlank()) { "Name is required" }
        val validRtsp = CameraSourceValidator.requireValidRtspUrl(rtspUrl)
        val validSnapshot = CameraSourceValidator.requireValidSnapshotUrl(snapshotUrl)
        val validStream = CameraSourceValidator.requireValidStreamName(
            streamName.trim().ifBlank { generateStreamName(name) })

        val maxOrder = Camera.findAll().maxOfOrNull { it.display_order } ?: -1
        val camera = Camera(
            name = name.trim(),
            rtsp_url = validRtsp,
            snapshot_url = validSnapshot,
            go2rtc_name = validStream,
            enabled = enabled,
            display_order = maxOrder + 1,
            created_at = clock.now()
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

        camera.name = name.trim()
        camera.rtsp_url = resolveRtspUrl(rtspUrl, camera)
        camera.snapshot_url = resolveSnapshotUrl(snapshotUrl, camera)
        camera.go2rtc_name = CameraSourceValidator.requireValidStreamName(
            streamName.trim().ifBlank { camera.go2rtc_name.ifBlank { generateStreamName(name) } })
        camera.enabled = enabled
        camera.save()
        Go2rtcAgent.instance?.reconfigure()
        log.info("Camera updated: '{}' (id={})", camera.name, camera.id)
        return camera
    }

    /**
     * Partial update used by the REST endpoint: only non-null arguments are
     * applied. The stream name is left unchanged. Validation matches [update].
     */
    fun patch(
        id: Long,
        name: String? = null,
        rtspUrl: String? = null,
        snapshotUrl: String? = null,
        enabled: Boolean? = null
    ): Camera {
        val camera = Camera.findById(id) ?: throw IllegalArgumentException("Camera not found: $id")
        if (name != null) {
            require(name.isNotBlank()) { "Name is required" }
            camera.name = name.trim()
        }
        if (rtspUrl != null) camera.rtsp_url = resolveRtspUrl(rtspUrl, camera)
        if (snapshotUrl != null) camera.snapshot_url = resolveSnapshotUrl(snapshotUrl, camera)
        if (enabled != null) camera.enabled = enabled
        camera.save()
        Go2rtcAgent.instance?.reconfigure()
        log.info("Camera updated: '{}' (id={})", camera.name, camera.id)
        return camera
    }

    /**
     * Restore redacted credentials (`***:***`) from the stored URL when the
     * host is unchanged, then validate the result.
     */
    private fun resolveRtspUrl(rtspUrl: String, camera: Camera): String {
        require(rtspUrl.isNotBlank()) { "RTSP URL is required" }
        val resolved = UriCredentialRedactor.restoreCredentials(rtspUrl.trim(), camera.rtsp_url)
        require(!resolved.contains("***:***")) {
            "Cannot restore credentials — host/port changed. Enter full URL with credentials."
        }
        return CameraSourceValidator.requireValidRtspUrl(resolved)
    }

    private fun resolveSnapshotUrl(snapshotUrl: String, camera: Camera): String {
        if (snapshotUrl.isBlank()) return ""
        val resolved = UriCredentialRedactor.restoreCredentials(snapshotUrl.trim(), camera.snapshot_url)
        require(!resolved.contains("***:***")) {
            "Cannot restore snapshot credentials — host/port changed. Enter full URL with credentials."
        }
        return CameraSourceValidator.requireValidSnapshotUrl(resolved)
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
