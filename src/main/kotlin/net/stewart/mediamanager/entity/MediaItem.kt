package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@Table("media_item")
data class MediaItem(
    override var id: Long? = null,
    var upc: String? = null,
    var media_format: String = MediaFormat.DVD.name,
    var item_condition: String = ItemCondition.GOOD.name,
    var title_count: Int = 1,
    var notes: String? = null,
    var expansion_status: String = ExpansionStatus.SINGLE.name,
    var upc_lookup_json: String? = null,
    var product_name: String? = null,
    var entry_source: String = EntrySource.UPC_SCAN.name,
    var purchase_place: String? = null,
    var purchase_date: LocalDate? = null,
    var purchase_price: BigDecimal? = null,
    var amazon_order_id: String? = null,
    var replacement_value: BigDecimal? = null,
    var replacement_value_updated_at: LocalDateTime? = null,
    var override_asin: String? = null,
    var created_at: LocalDateTime? = null,
    var updated_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<MediaItem, Long>(MediaItem::class.java)
}
