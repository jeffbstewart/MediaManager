package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("forbrowser_probe")
data class ForBrowserProbe(
    override var id: Long? = null,
    var transcode_id: Long = 0,
    var relative_path: String = "",
    var duration_secs: Double? = null,
    var stream_count: Int = 0,
    var file_size_bytes: Long? = null,
    var encoder: String? = null,
    var raw_output: String? = null,
    var probed_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<ForBrowserProbe, Long>(ForBrowserProbe::class.java)
}

@Table("forbrowser_probe_stream")
data class ForBrowserProbeStream(
    override var id: Long? = null,
    var probe_id: Long = 0,
    var stream_index: Int = 0,
    var stream_type: String = "",
    var codec: String? = null,
    var width: Int? = null,
    var height: Int? = null,
    var sar_num: Int? = null,
    var sar_den: Int? = null,
    var fps: Double? = null,
    var channels: Int? = null,
    var channel_layout: String? = null,
    var sample_rate: Int? = null,
    var bitrate_kbps: Int? = null,
    var raw_line: String? = null
) : KEntity<Long> {
    companion object : Dao<ForBrowserProbeStream, Long>(ForBrowserProbeStream::class.java)
}
