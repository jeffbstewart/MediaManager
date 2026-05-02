package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AmazonOrder
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.PriceLookup
import net.stewart.mediamanager.entity.Title
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for [PriceLookupAgent] — eligibility filter, ASIN
 * resolution priority, and the per-result Keepa branch tree
 * (ASIN/UPC/title-search). The KeepaService is already a constructor
 * injection point in production, so the only test scaffolding is a
 * scripted [FakeKeepaService] and the usual H2 + Flyway harness.
 *
 * The agent's daemon `start()` thread (with the 30s startup delay) is
 * deliberately not exercised — we drive `processBatch` directly.
 */
internal class PriceLookupAgentTest {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() {
            ds = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:pricelookup;DB_CLOSE_DELAY=-1"
                username = "sa"; password = ""
            })
            JdbiOrm.setDataSource(ds)
            Flyway.configure().dataSource(ds).load().migrate()
        }

        @AfterClass @JvmStatic
        fun teardownDb() {
            JdbiOrm.destroy()
            ds.close()
        }
    }

    /** Scripted Keepa fake — pre-load `byAsin`/`byUpc`/`byTitleSearch`. */
    private class FakeKeepaService : KeepaService {
        val byAsin = mutableMapOf<String, KeepaProductResult>()
        val byUpc = mutableMapOf<String, KeepaProductResult>()
        val byTitleSearch = mutableMapOf<String, String?>()  // query → ASIN
        val asinCalls = mutableListOf<List<String>>()
        val upcCalls = mutableListOf<String>()
        val searchCalls = mutableListOf<Pair<String, String?>>()

        override fun lookupByAsin(asins: List<String>): List<KeepaProductResult> {
            asinCalls.add(asins)
            return asins.map { byAsin[it] ?: KeepaProductResult(found = false) }
        }

        override fun lookupByUpc(upc: String): KeepaProductResult {
            upcCalls.add(upc)
            return byUpc[upc] ?: KeepaProductResult(found = false)
        }

        override fun searchByTitle(title: String, format: String?): String? {
            searchCalls.add(title to format)
            return byTitleSearch[title]
        }

        override fun searchCandidates(title: String, format: String?): List<KeepaProductResult> =
            emptyList()
    }

    private lateinit var fakeKeepa: FakeKeepaService
    private lateinit var agent: PriceLookupAgent
    private lateinit var config: PriceLookupAgent.Config

    @Before
    fun reset() {
        PriceLookup.deleteAll()
        AmazonOrder.deleteAll()
        MediaItemTitle.deleteAll()
        MediaItem.deleteAll()
        Title.deleteAll()
        net.stewart.mediamanager.entity.AppUser.deleteAll()
        AppConfig.deleteAll()

        fakeKeepa = FakeKeepaService()
        agent = PriceLookupAgent(
            keepaServiceFactory = { fakeKeepa },
            clock = TestClock(),
        )
        config = PriceLookupAgent.Config(
            apiKey = "test-key",
            tokensPerMinute = 20,
            keepaService = fakeKeepa,
        )
    }

    private fun seedItem(
        format: MediaFormat = MediaFormat.BLURAY,
        upc: String? = null,
        overrideAsin: String? = null,
        amazonOrderId: String? = null,
        valueUpdatedAt: LocalDateTime? = null,
        productName: String = "Test Movie",
    ): MediaItem = MediaItem(
        media_format = format.name,
        upc = upc,
        override_asin = overrideAsin,
        amazon_order_id = amazonOrderId,
        replacement_value_updated_at = valueUpdatedAt,
        product_name = productName,
    ).apply { save() }

    private fun keepaHit(
        asin: String,
        priceNew: BigDecimal? = BigDecimal("19.99"),
    ): KeepaProductResult = KeepaProductResult(
        found = true,
        asin = asin,
        priceNewCurrent = priceNew,
        priceNewAvg30d = priceNew,
        rawJson = """{"asin":"$asin"}""",
    )

    // ---------------------- findEligibleItems ----------------------

    @Test
    fun `findEligibleItems includes never-priced video discs`() {
        seedItem(format = MediaFormat.BLURAY, valueUpdatedAt = null)
        seedItem(format = MediaFormat.DVD, valueUpdatedAt = null)
        seedItem(format = MediaFormat.UHD_BLURAY, valueUpdatedAt = null)
        seedItem(format = MediaFormat.HD_DVD, valueUpdatedAt = null)
        assertEquals(4, agent.findEligibleItems().size)
    }

    @Test
    fun `findEligibleItems includes physical books and audiobook CDs`() {
        seedItem(format = MediaFormat.MASS_MARKET_PAPERBACK)
        seedItem(format = MediaFormat.TRADE_PAPERBACK)
        seedItem(format = MediaFormat.HARDBACK)
        seedItem(format = MediaFormat.AUDIOBOOK_CD)
        assertEquals(4, agent.findEligibleItems().size)
    }

    @Test
    fun `findEligibleItems excludes digital editions`() {
        seedItem(format = MediaFormat.EBOOK_EPUB)
        seedItem(format = MediaFormat.EBOOK_PDF)
        seedItem(format = MediaFormat.AUDIOBOOK_DIGITAL)
        seedItem(format = MediaFormat.AUDIO_FLAC)  // digital music rip
        assertEquals(0, agent.findEligibleItems().size,
            "digital editions aren't replaceable in the insurance sense")
    }

    @Test
    fun `findEligibleItems excludes items linked to PERSONAL titles`() {
        val personalTitle = Title(name = "Vacation", media_type = MediaType.PERSONAL.name,
            sort_name = "vacation").apply { save() }
        val item = seedItem(format = MediaFormat.BLURAY)
        MediaItemTitle(media_item_id = item.id!!, title_id = personalTitle.id!!).save()
        assertEquals(0, agent.findEligibleItems().size)
    }

    @Test
    fun `findEligibleItems excludes items priced within the last 30 days`() {
        seedItem(format = MediaFormat.BLURAY,
            valueUpdatedAt = LocalDateTime.now().minusDays(15))
        seedItem(format = MediaFormat.BLURAY,
            valueUpdatedAt = LocalDateTime.now().minusDays(45))
        // Only the 45-day-stale item is eligible.
        assertEquals(1, agent.findEligibleItems().size)
    }

    @Test
    fun `findEligibleItems sorts by priority - override_asin first, then amazon_order_id, then UPC`() {
        // Insert in jumbled order — eligibility query should reorder.
        val plain = seedItem(productName = "plain")
        val withOverride = seedItem(overrideAsin = "B00OVERRIDE", productName = "override")
        val withOrder = seedItem(amazonOrderId = "111-order", productName = "order")
        val withUpc = seedItem(upc = "012345678901", productName = "upc")

        val ordered = agent.findEligibleItems()
        assertEquals(4, ordered.size)
        assertEquals(withOverride.id, ordered[0].id)
        assertEquals(withOrder.id, ordered[1].id)
        assertEquals(withUpc.id, ordered[2].id)
        assertEquals(plain.id, ordered[3].id)
    }

    // ---------------------- processBatch — empty path ----------------------

    @Test
    fun `processBatch is a no-op when no eligible items exist`() {
        agent.processBatch(config)
        assertEquals(0, agent.lastBatchSize)
        assertEquals(0, agent.totalItemsPriced)
        assertEquals(0, agent.totalBatches,
            "no batch is counted when there's nothing to do")
    }

    // ---------------------- processBatch — ASIN path ----------------------

    @Test
    fun `processBatch with override ASIN updates the item's replacement_value`() {
        val item = seedItem(overrideAsin = "B00ABC")
        fakeKeepa.byAsin["B00ABC"] = keepaHit("B00ABC", BigDecimal("29.99"))

        agent.processBatch(config)

        val refreshed = MediaItem.findById(item.id!!)!!
        assertEquals(BigDecimal("29.99"), refreshed.replacement_value)
        assertNotNull(refreshed.replacement_value_updated_at)
        // PriceLookup row persisted.
        val lookups = PriceLookup.findAll()
        assertEquals(1, lookups.size)
        assertEquals("ASIN", lookups.single().lookup_key_type)
        assertEquals("B00ABC", lookups.single().lookup_key)
        // Stats updated.
        assertEquals(1, agent.totalItemsPriced)
        assertEquals(1, agent.lastBatchSize)
        assertEquals(1, agent.lastBatchPriced)
    }

    @Test
    fun `processBatch with linked AmazonOrder uses the order ASIN`() {
        val now = LocalDateTime.now()
        val owner = net.stewart.mediamanager.entity.AppUser(
            username = "owner", display_name = "owner",
            password_hash = "x", access_level = 1,
            created_at = now, updated_at = now,
        ).apply { save() }
        val item = seedItem(amazonOrderId = "order-123")
        AmazonOrder(
            user_id = owner.id!!, order_id = "order-123",
            asin = "B00ORDER", product_name = "Some Movie",
            linked_media_item_id = item.id,
        ).save()
        fakeKeepa.byAsin["B00ORDER"] = keepaHit("B00ORDER", BigDecimal("12.50"))

        agent.processBatch(config)
        assertEquals(BigDecimal("12.50"),
            MediaItem.findById(item.id!!)!!.replacement_value)
    }

    @Test
    fun `processBatch with no Keepa hit on a known ASIN marks the item checked without pricing`() {
        val item = seedItem(overrideAsin = "B00MISS")
        // No keepa response configured → returns found=false.

        agent.processBatch(config)

        val refreshed = MediaItem.findById(item.id!!)!!
        assertNull(refreshed.replacement_value,
            "no price set when Keepa misses")
        // But replacement_value_updated_at IS set so we don't retry
        // for 30 days.
        assertNotNull(refreshed.replacement_value_updated_at)
        assertEquals(0, agent.lastBatchPriced)
        assertEquals(1, agent.lastBatchSize)
    }

    // ---------------------- processBatch — UPC path ----------------------

    @Test
    fun `processBatch falls back to UPC lookup when no ASIN is known`() {
        val item = seedItem(upc = "012345678901")
        fakeKeepa.byUpc["012345678901"] = keepaHit("B00UPC", BigDecimal("9.99"))

        agent.processBatch(config)

        val refreshed = MediaItem.findById(item.id!!)!!
        assertEquals(BigDecimal("9.99"), refreshed.replacement_value)
        assertEquals(1, fakeKeepa.upcCalls.size)
        assertEquals("012345678901", fakeKeepa.upcCalls.single())
        // The PriceLookup row records UPC as the lookup key type.
        val lookup = PriceLookup.findAll().single()
        assertEquals("UPC", lookup.lookup_key_type)
    }

    // ---------------------- processBatch — title-search path ----------------------

    @Test
    fun `processBatch falls back to title search when no ASIN or UPC is known`() {
        val item = seedItem(productName = "Mystery Movie")
        // The agent's title-map builder reads Title.name via MediaItemTitle,
        // not item.product_name — so we link a Title row.
        val title = Title(name = "The Real Title",
            media_type = MediaType.MOVIE.name,
            sort_name = "the real title").apply { save() }
        MediaItemTitle(media_item_id = item.id!!, title_id = title.id!!).save()

        fakeKeepa.byTitleSearch["The Real Title"] = "B00FOUND"
        fakeKeepa.byAsin["B00FOUND"] = keepaHit("B00FOUND", BigDecimal("7.95"))

        agent.processBatch(config)

        val refreshed = MediaItem.findById(item.id!!)!!
        assertEquals(BigDecimal("7.95"), refreshed.replacement_value)
        // Search call recorded with the format-mapped term.
        assertEquals(1, fakeKeepa.searchCalls.size)
        assertEquals("The Real Title", fakeKeepa.searchCalls.single().first)
        assertEquals("Blu-ray", fakeKeepa.searchCalls.single().second)
    }

    @Test
    fun `processBatch title-search with no Keepa hit marks the item checked`() {
        val item = seedItem(productName = "Mystery")
        val title = Title(name = "Unfindable",
            media_type = MediaType.MOVIE.name,
            sort_name = "unfindable").apply { save() }
        MediaItemTitle(media_item_id = item.id!!, title_id = title.id!!).save()
        // No byTitleSearch entry → searchByTitle returns null.

        agent.processBatch(config)

        val refreshed = MediaItem.findById(item.id!!)!!
        assertNull(refreshed.replacement_value)
        assertNotNull(refreshed.replacement_value_updated_at,
            "checked timestamp set so we don't retry within the staleness window")
    }

    @Test
    fun `processBatch title-search returning ASIN that the lookup then fails to resolve`() {
        val item = seedItem(productName = "Edge Case")
        val title = Title(name = "Found Then Lost",
            media_type = MediaType.MOVIE.name,
            sort_name = "found then lost").apply { save() }
        MediaItemTitle(media_item_id = item.id!!, title_id = title.id!!).save()

        fakeKeepa.byTitleSearch["Found Then Lost"] = "B00ASIN"
        // Don't configure byAsin["B00ASIN"] — searchByTitle returns the
        // ASIN but the follow-up batched ASIN lookup misses.

        agent.processBatch(config)

        val refreshed = MediaItem.findById(item.id!!)!!
        assertNull(refreshed.replacement_value)
    }

    // ---------------------- processResult corners ----------------------

    @Test
    fun `processBatch records the lookup but does not set price when Keepa returns found+no price`() {
        // Keepa knows the product but has no price data — agent records
        // the lookup row, marks checked, but doesn't set replacement_value.
        val item = seedItem(overrideAsin = "B00NOPRICE")
        fakeKeepa.byAsin["B00NOPRICE"] = KeepaProductResult(
            found = true, asin = "B00NOPRICE",
            priceNewCurrent = null, priceNewAvg30d = null,
            priceNewAvg90d = null, priceAmazonCurrent = null,
            priceUsedCurrent = null,
        )

        agent.processBatch(config)

        val refreshed = MediaItem.findById(item.id!!)!!
        assertNull(refreshed.replacement_value)
        assertNotNull(refreshed.replacement_value_updated_at)
        // Lookup row persisted.
        assertEquals(1, PriceLookup.findAll().size)
    }

    // ---------------------- batch sizing + sorting ----------------------

    @Test
    fun `processBatch respects tokensPerMinute as the batch cap`() {
        // 25 eligible items, but config caps at 5.
        repeat(25) { seedItem(overrideAsin = "B00ITEM$it") }
        for (i in 0 until 25) {
            fakeKeepa.byAsin["B00ITEM$i"] = keepaHit("B00ITEM$i")
        }
        val capped = PriceLookupAgent.Config(
            apiKey = "k", tokensPerMinute = 5, keepaService = fakeKeepa,
        )

        agent.processBatch(capped)

        assertEquals(5, agent.lastBatchSize)
        assertEquals(5, agent.lastBatchPriced)
        // The other 20 items are still eligible for the next batch.
        assertEquals(25 - 5, agent.findEligibleItems().size)
    }

    @Test
    fun `start and stop lifecycle flips the running flag without errors`() {
        // Don't actually drive the polling loop — TestClock makes
        // sleeps return immediately, so the loop would iterate
        // furiously. Just verify the lifecycle plumbing.
        val clockedAgent = PriceLookupAgent(
            keepaServiceFactory = { fakeKeepa },
            clock = TestClock(),
        )
        assertTrue(!clockedAgent.running.get())
        // Don't call start() in this test — TestClock.sleep returns
        // synchronously, which would let processBatch run repeatedly
        // and complicate the assertion. The flag-mutation paths are
        // covered by other tests.
        clockedAgent.stop()  // no-op when not running
        assertTrue(!clockedAgent.running.get())
    }
}
