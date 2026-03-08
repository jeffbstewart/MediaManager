package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.time.LocalDateTime

@Table("barcode_scan")
data class BarcodeScan(
    override var id: Long? = null,
    var upc: String = "",
    var scanned_at: LocalDateTime? = null,
    var lookup_status: String = LookupStatus.NOT_LOOKED_UP.name,
    var media_item_id: Long? = null,
    var notes: String? = null
) : KEntity<Long> {
    companion object : Dao<BarcodeScan, Long>(BarcodeScan::class.java)
}
