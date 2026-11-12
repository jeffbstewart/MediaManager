package net.stewart.mediamanager.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.MediaItem
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Backfills product_name on media_item records where it's null.
 * Parses the original product name from upc_lookup_json (the raw UPCitemdb response).
 * Covers expanded multi-packs where the original placeholder title was unlinked.
 */
class PopulateProductNameUpdater : SchemaUpdater {

    private val log = LoggerFactory.getLogger(PopulateProductNameUpdater::class.java)
    private val mapper = ObjectMapper()

    override val name = "populate_product_name"
    override val version = 1

    override fun run() {
        val items = MediaItem.findAll().filter { it.product_name == null && it.upc_lookup_json != null }
        if (items.isEmpty()) {
            log.info("No media items need product_name backfill")
            return
        }

        log.info("Backfilling product_name for {} media items", items.size)
        var updated = 0

        for (item in items) {
            val productName = extractProductName(item.upc_lookup_json!!)
            if (productName != null) {
                item.product_name = productName
                item.updated_at = LocalDateTime.now()
                item.save()
                updated++
            }
        }

        log.info("Backfilled product_name for {} of {} media items", updated, items.size)
    }

    private fun extractProductName(json: String): String? {
        return try {
            val root = mapper.readTree(json)
            // Real UPCitemdb format: {"items":[{"title":"..."}]}
            val items = root.get("items")
            if (items != null && items.isArray && !items.isEmpty) {
                val title = items[0].get("title")
                if (title != null && !title.isNull) return title.asText().ifBlank { null }
            }
            // Mock format: {"title":"..."}
            val title = root.get("title")
            if (title != null && !title.isNull) title.asText().ifBlank { null } else null
        } catch (e: Exception) {
            log.warn("Failed to parse upc_lookup_json: {}", e.message)
            null
        }
    }
}
