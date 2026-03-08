package net.stewart.mediamanager.service

import net.stewart.mediamanager.entity.AmazonOrder
import net.stewart.mediamanager.entity.MediaItem
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmazonImportServiceTest {

    // --- CSV Line Parsing ---

    @Test
    fun `parseCsvLine handles simple fields`() {
        val fields = AmazonImportService.parseCsvLine("a,b,c")
        assertEquals(listOf("a", "b", "c"), fields)
    }

    @Test
    fun `parseCsvLine handles quoted fields with commas`() {
        val line = "one,\"two, three\",four"
        val fields = AmazonImportService.parseCsvLine(line)
        assertEquals(listOf("one", "two, three", "four"), fields)
    }

    @Test
    fun `parseCsvLine handles escaped quotes`() {
        val line = "one,\"say \"\"hello\"\"\",three"
        val fields = AmazonImportService.parseCsvLine(line)
        assertEquals("say \"hello\"", fields[1])
    }

    @Test
    fun `parseCsvLine handles empty fields`() {
        val fields = AmazonImportService.parseCsvLine("a,,c,")
        assertEquals(listOf("a", "", "c", ""), fields)
    }

    // --- Date Parsing ---

    @Test
    fun `parseDate parses ISO-8601 format`() {
        val dt = AmazonImportService.parseDate("2022-07-24T13:18:08Z")
        assertNotNull(dt)
        assertEquals(2022, dt.year)
        assertEquals(7, dt.monthValue)
        assertEquals(24, dt.dayOfMonth)
        assertEquals(13, dt.hour)
    }

    @Test
    fun `parseDate parses ISO-8601 with milliseconds`() {
        val dt = AmazonImportService.parseDate("2023-01-15T09:30:00.000Z")
        assertNotNull(dt)
        assertEquals(2023, dt.year)
        assertEquals(1, dt.monthValue)
        assertEquals(15, dt.dayOfMonth)
    }

    @Test
    fun `parseDate falls back to MM-dd-yyyy format`() {
        val dt = AmazonImportService.parseDate("03/15/2023")
        assertNotNull(dt)
        assertEquals(2023, dt.year)
        assertEquals(3, dt.monthValue)
        assertEquals(15, dt.dayOfMonth)
    }

    @Test
    fun `parseDate returns null for invalid dates`() {
        assertNull(AmazonImportService.parseDate("not-a-date"))
        assertNull(AmazonImportService.parseDate(""))
    }

    // --- Price Parsing ---

    @Test
    fun `parsePrice strips dollar sign`() {
        val price = AmazonImportService.parsePrice("\$24.99")
        assertEquals(BigDecimal("24.99"), price)
    }

    @Test
    fun `parsePrice handles commas in large prices`() {
        val price = AmazonImportService.parsePrice("\$1,234.56")
        assertEquals(BigDecimal("1234.56"), price)
    }

    @Test
    fun `parsePrice handles plain decimals`() {
        val price = AmazonImportService.parsePrice("21.98")
        assertEquals(BigDecimal("21.98"), price)
    }

    @Test
    fun `parsePrice handles single-quote discount notation`() {
        val price = AmazonImportService.parsePrice("'-15.97'")
        assertNotNull(price)
        assertEquals(BigDecimal("-15.97"), price)
    }

    @Test
    fun `parsePrice returns null for invalid prices`() {
        assertNull(AmazonImportService.parsePrice("N/A"))
        assertNull(AmazonImportService.parsePrice(""))
    }

    @Test
    fun `zero price is parsed as zero not null`() {
        val price = AmazonImportService.parsePrice("0.00")
        assertEquals(BigDecimal("0.00"), price)
    }

    // --- CSV File Parsing ---

    @Test
    fun `parseCsv parses actual Amazon column names with ISO dates`() {
        val csv = buildString {
            appendLine("Order ID,Order Date,Product Name,ASIN/ISBN,Unit Price,Unit Price Tax,Quantity,Order Status,Product Condition,Currency,Shipment Date,Website")
            appendLine("111-222-333,2023-01-15T10:30:00Z,The Dark Knight [Blu-ray],B00ABC,19.99,1.60,1,Closed,New,USD,2023-01-17T08:00:00Z,Amazon.com")
            appendLine("111-222-334,2023-02-20T14:00:00Z,Inception [DVD],B00DEF,14.99,1.20,1,Closed,Used,USD,2023-02-22T09:00:00Z,Amazon.com")
        }
        val rows = AmazonImportService.parseCsv(csv.byteInputStream())
        assertEquals(2, rows.size)

        assertEquals("111-222-333", rows[0].orderId)
        assertEquals(LocalDateTime.of(2023, 1, 15, 10, 30, 0), rows[0].orderDate)
        assertEquals(BigDecimal("19.99"), rows[0].unitPrice)
        assertEquals(BigDecimal("1.60"), rows[0].unitPriceTax)
        assertEquals("B00ABC", rows[0].asin)
        assertEquals("The Dark Knight [Blu-ray]", rows[0].productName)
        assertEquals(1, rows[0].quantity)
        assertEquals("Closed", rows[0].orderStatus)
        assertEquals("New", rows[0].productCondition)
        assertEquals("USD", rows[0].currency)
        assertNotNull(rows[0].shipDate)
        assertEquals("Amazon.com", rows[0].website)
    }

    @Test
    fun `parseCsv includes cancelled orders for storage`() {
        val csv = buildString {
            appendLine("Order ID,Order Date,Product Name,ASIN/ISBN,Unit Price,Quantity,Order Status,Currency")
            appendLine("111-222-333,2023-01-15T10:30:00Z,The Dark Knight [Blu-ray],B00ABC,19.99,1,Closed,USD")
            appendLine("111-222-334,2023-02-20T14:00:00Z,Cancelled Movie [DVD],B00DEF,14.99,1,Cancelled,USD")
            appendLine("111-222-335,2023-03-10T08:00:00Z,Another Movie [Blu-ray],B00GHI,9.99,1,Closed,USD")
        }
        val rows = AmazonImportService.parseCsv(csv.byteInputStream())
        // All rows stored now — cancelled filtering happens in search
        assertEquals(3, rows.size)
    }

    @Test
    fun `parseCsv handles quoted product names with commas`() {
        val csv = buildString {
            appendLine("Order ID,Order Date,Product Name,ASIN/ISBN,Unit Price,Quantity,Order Status,Currency")
            appendLine("111-222-333,2023-01-15T10:30:00Z,\"Batman v Superman: Dawn of Justice, Ultimate Edition [Blu-ray]\",B00ABC,29.99,1,Closed,USD")
        }
        val rows = AmazonImportService.parseCsv(csv.byteInputStream())
        assertEquals(1, rows.size)
        assertEquals("Batman v Superman: Dawn of Justice, Ultimate Edition [Blu-ray]", rows[0].productName)
    }

    @Test
    fun `parseCsv tolerates alternate column names`() {
        val csv = buildString {
            appendLine("Website Order ID,Order Date,Item Total,ASIN,Product Name,Quantity,Order Status,Currency")
            appendLine("111-222-333,01/15/2023,19.99,B00ABC,Inception [Blu-ray],1,Shipped,USD")
        }
        val rows = AmazonImportService.parseCsv(csv.byteInputStream())
        assertEquals(1, rows.size)
        assertEquals("111-222-333", rows[0].orderId)
        assertEquals("Inception [Blu-ray]", rows[0].productName)
    }

    @Test
    fun `parseCsv handles BOM prefix`() {
        val csv = "\uFEFFOrder ID,Order Date,Product Name,Quantity,Order Status\n" +
            "111-222-333,2023-01-15T10:30:00Z,Test Product,1,Closed\n"
        val rows = AmazonImportService.parseCsv(csv.byteInputStream())
        assertEquals(1, rows.size)
        assertEquals("111-222-333", rows[0].orderId)
    }

    @Test
    fun `parseCsv handles discount columns`() {
        val csv = buildString {
            appendLine("Order ID,Order Date,Product Name,ASIN/ISBN,Unit Price,Total Discounts,Quantity,Order Status,Currency")
            appendLine("111-222-333,2023-01-15T10:30:00Z,Movie [Blu-ray],B00ABC,21.98,'-5.00',1,Closed,USD")
        }
        val rows = AmazonImportService.parseCsv(csv.byteInputStream())
        assertEquals(1, rows.size)
        assertEquals(BigDecimal("21.98"), rows[0].unitPrice)
        assertEquals(BigDecimal("-5.00"), rows[0].totalDiscounts)
    }

    // --- Zip Parsing ---

    @Test
    fun `parseZip finds CSV inside zip`() {
        val csvContent = buildString {
            appendLine("Order ID,Order Date,Product Name,Quantity,Order Status")
            appendLine("111-222-333,2023-01-15T10:30:00Z,Test Movie [Blu-ray],1,Closed")
        }

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("Your Amazon Orders/Order History.csv"))
            zip.write(csvContent.toByteArray())
            zip.closeEntry()
        }

        val rows = AmazonImportService.parseZip(baos.toByteArray().inputStream())
        assertEquals(1, rows.size)
        assertEquals("Test Movie [Blu-ray]", rows[0].productName)
    }

    @Test
    fun `parseZip returns empty for zip without CSV`() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("no csv here".toByteArray())
            zip.closeEntry()
        }

        val rows = AmazonImportService.parseZip(baos.toByteArray().inputStream())
        assertTrue(rows.isEmpty())
    }

    // --- Media Detection ---

    @Test
    fun `isLikelyMedia detects Blu-ray`() {
        assertTrue(AmazonImportService.isLikelyMedia("The Dark Knight [Blu-ray]"))
        assertTrue(AmazonImportService.isLikelyMedia("Movie Title (Blu-ray + DVD)"))
    }

    @Test
    fun `isLikelyMedia detects DVD`() {
        assertTrue(AmazonImportService.isLikelyMedia("Inception [DVD]"))
        assertTrue(AmazonImportService.isLikelyMedia("Some movie dvd edition"))
    }

    @Test
    fun `isLikelyMedia detects UHD and 4K`() {
        assertTrue(AmazonImportService.isLikelyMedia("Movie [4K UHD]"))
        assertTrue(AmazonImportService.isLikelyMedia("Title [UHD]"))
    }

    @Test
    fun `isLikelyMedia detects HD DVD`() {
        assertTrue(AmazonImportService.isLikelyMedia("Old Movie [HD DVD]"))
        assertTrue(AmazonImportService.isLikelyMedia("Old Movie [HD-DVD]"))
    }

    @Test
    fun `isLikelyMedia is case insensitive`() {
        assertTrue(AmazonImportService.isLikelyMedia("Movie [BLU-RAY]"))
        assertTrue(AmazonImportService.isLikelyMedia("Movie [blu-Ray]"))
    }

    @Test
    fun `isLikelyMedia rejects non-media items`() {
        assertFalse(AmazonImportService.isLikelyMedia("USB Cable 6ft"))
        assertFalse(AmazonImportService.isLikelyMedia("Kitchen Knife Set"))
        assertFalse(AmazonImportService.isLikelyMedia("Book: The Art of War"))
    }

    @Test
    fun `isLikelyMedia detects steelbook`() {
        assertTrue(AmazonImportService.isLikelyMedia("Gladiator SteelBook"))
    }

    // --- Suggestion Matching ---

    private fun makeOrder(id: Long, productName: String, price: BigDecimal? = BigDecimal("19.99")): AmazonOrder =
        AmazonOrder(
            id = id,
            user_id = 1,
            order_id = "111-$id",
            asin = "B00$id",
            product_name = productName,
            product_name_lower = productName.lowercase(),
            order_date = LocalDateTime.of(2023, 1, 15, 10, 0),
            unit_price = price
        )

    private fun makeItem(id: Long): MediaItem =
        MediaItem(id = id)

    @Test
    fun `matchSuggestions matches exact title`() {
        val orders = listOf(makeOrder(1, "The Dark Knight [Blu-ray]"))
        val items = listOf(makeItem(10))
        val titleMap = mapOf(10L to "The Dark Knight")

        val result = AmazonImportService.matchSuggestions(orders, items, titleMap)

        assertEquals(1, result.size)
        assertTrue(result.containsKey(10L))
        assertTrue(result[10L]!!.score >= 0.90)
    }

    @Test
    fun `matchSuggestions rejects low-similarity pairs`() {
        val orders = listOf(makeOrder(1, "USB Cable 6ft [Blu-ray]"))
        val items = listOf(makeItem(10))
        val titleMap = mapOf(10L to "The Dark Knight")

        val result = AmazonImportService.matchSuggestions(orders, items, titleMap)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `matchSuggestions assigns each order to at most one item`() {
        val orders = listOf(makeOrder(1, "Inception [Blu-ray]"))
        val items = listOf(makeItem(10), makeItem(11))
        val titleMap = mapOf(10L to "Inception", 11L to "Inception")

        val result = AmazonImportService.matchSuggestions(orders, items, titleMap)

        // Only one item should get the suggestion
        assertEquals(1, result.size)
    }

    @Test
    fun `matchSuggestions assigns each item to at most one order`() {
        val orders = listOf(
            makeOrder(1, "Inception [Blu-ray]"),
            makeOrder(2, "Inception [DVD]")
        )
        val items = listOf(makeItem(10))
        val titleMap = mapOf(10L to "Inception")

        val result = AmazonImportService.matchSuggestions(orders, items, titleMap)

        // Item should get exactly one suggestion (highest scoring)
        assertEquals(1, result.size)
        assertTrue(result.containsKey(10L))
    }

    @Test
    fun `matchSuggestions prefers higher score`() {
        val orders = listOf(
            makeOrder(1, "The Dark Knight Rises [Blu-ray]"),
            makeOrder(2, "The Dark Knight [Blu-ray]")
        )
        val items = listOf(makeItem(10), makeItem(11))
        val titleMap = mapOf(10L to "The Dark Knight", 11L to "The Dark Knight Rises")

        val result = AmazonImportService.matchSuggestions(orders, items, titleMap)

        // Both should match to their best titles
        assertEquals(2, result.size)
        // Item 10 ("The Dark Knight") should match order 2 ("The Dark Knight [Blu-ray]")
        assertEquals(2L, result[10L]!!.amazonOrder.id)
        // Item 11 ("The Dark Knight Rises") should match order 1
        assertEquals(1L, result[11L]!!.amazonOrder.id)
    }

    @Test
    fun `matchSuggestions skips items without titles in titleMap`() {
        val orders = listOf(makeOrder(1, "Inception [Blu-ray]"))
        val items = listOf(makeItem(10))
        val titleMap = emptyMap<Long, String>()

        val result = AmazonImportService.matchSuggestions(orders, items, titleMap)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `matchSuggestions handles multi-title items`() {
        val orders = listOf(makeOrder(1, "Gladiator [Blu-ray]"))
        val items = listOf(makeItem(10))
        // Multi-pack: item has two titles, one matches
        val titleMap = mapOf(10L to "Gladiator, Robin Hood")

        val result = AmazonImportService.matchSuggestions(orders, items, titleMap)

        assertEquals(1, result.size)
        assertTrue(result[10L]!!.score >= 0.50)
    }

    @Test
    fun `matchSuggestions cleans Amazon product name before matching`() {
        // The [Blu-ray] tag should be stripped by TitleCleanerService.clean()
        val orders = listOf(makeOrder(1, "Inception [Blu-ray] [DVD]"))
        val items = listOf(makeItem(10))
        val titleMap = mapOf(10L to "Inception")

        val result = AmazonImportService.matchSuggestions(orders, items, titleMap)

        assertEquals(1, result.size)
        assertEquals("Inception", result[10L]!!.cleanedName)
    }
}
