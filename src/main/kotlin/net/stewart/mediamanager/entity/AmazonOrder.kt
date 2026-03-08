package net.stewart.mediamanager.entity

import com.github.vokorm.KEntity
import com.gitlab.mvysny.jdbiorm.Dao
import com.gitlab.mvysny.jdbiorm.Table
import java.math.BigDecimal
import java.time.LocalDateTime

@Table("amazon_order")
data class AmazonOrder(
    override var id: Long? = null,
    var user_id: Long = 0,
    var order_id: String = "",
    var asin: String = "",
    var product_name: String = "",
    var product_name_lower: String = "",
    var order_date: LocalDateTime? = null,
    var ship_date: LocalDateTime? = null,
    var order_status: String? = null,
    var product_condition: String? = null,
    var unit_price: BigDecimal? = null,
    var unit_price_tax: BigDecimal? = null,
    var total_amount: BigDecimal? = null,
    var total_discounts: BigDecimal? = null,
    var quantity: Int = 1,
    var currency: String? = null,
    var website: String? = null,
    var linked_media_item_id: Long? = null,
    var linked_at: LocalDateTime? = null,
    var imported_at: LocalDateTime? = null
) : KEntity<Long> {
    companion object : Dao<AmazonOrder, Long>(AmazonOrder::class.java)
}
