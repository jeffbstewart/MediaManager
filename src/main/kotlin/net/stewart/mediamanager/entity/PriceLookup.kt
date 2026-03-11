package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.math.BigDecimal
import java.time.LocalDateTime

@Table("price_lookup")
data class PriceLookup(
    override var id: Long? = null,
    var media_item_id: Long = 0,
    var lookup_key_type: String = "",
    var lookup_key: String = "",
    var price_new_current: BigDecimal? = null,
    var price_new_avg_30d: BigDecimal? = null,
    var price_new_avg_90d: BigDecimal? = null,
    var price_amazon_current: BigDecimal? = null,
    var price_used_current: BigDecimal? = null,
    var offer_count_new: Int? = null,
    var offer_count_used: Int? = null,
    var keepa_asin: String? = null,
    var selected_price: BigDecimal? = null,
    var looked_up_at: LocalDateTime? = null,
    var raw_json: String? = null
) : KEntity<Long> {
    companion object : Dao<PriceLookup, Long>(PriceLookup::class.java)
}
