package net.stewart.mediamanager.service

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.stewart.mediamanager.entity.AppConfig
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.GZIPInputStream

/**
 * Result of a Keepa product price lookup.
 */
data class KeepaProductResult(
    val found: Boolean,
    val asin: String? = null,
    val title: String? = null,
    val priceNewCurrent: BigDecimal? = null,
    val priceNewAvg30d: BigDecimal? = null,
    val priceNewAvg90d: BigDecimal? = null,
    val priceAmazonCurrent: BigDecimal? = null,
    val priceUsedCurrent: BigDecimal? = null,
    val offerCountNew: Int? = null,
    val offerCountUsed: Int? = null,
    val tokensLeft: Int = 0,
    val rawJson: String? = null,
    val errorMessage: String? = null
)

/**
 * Interface for Keepa API interactions.
 * All lookups target Amazon.com (domain=1) only.
 */
interface KeepaService {
    /** Look up products by ASIN (batch, up to 100). */
    fun lookupByAsin(asins: List<String>): List<KeepaProductResult>

    /** Look up a product by UPC code. */
    fun lookupByUpc(upc: String): KeepaProductResult

    /** Search for a product by title keywords. Returns the best-match ASIN or null. */
    fun searchByTitle(title: String, format: String? = null): String?

    /**
     * Search for candidate products by title, returning up to 5 results with full price details.
     * Calls the search endpoint to get ASINs, then batch-fetches product details for each.
     */
    fun searchCandidates(title: String, format: String? = null): List<KeepaProductResult>
}

/**
 * Keepa HTTP API client.
 * Uses Amazon.com (domain=1) exclusively. Other Amazon marketplaces are not supported.
 *
 * Keepa stores prices as integers in cents. A value of -1 means "no data available."
 * The stats object provides pre-calculated averages over configurable time windows.
 */
class KeepaHttpService(private val apiKey: String) : KeepaService {

    private val log = LoggerFactory.getLogger(KeepaHttpService::class.java)
    private val client = HttpClient.newHttpClient()
    private val gson = Gson()

    companion object {
        private const val BASE_URL = "https://api.keepa.com"
        private const val DOMAIN_US = 1 // Amazon.com
        // Keepa price type indices in the csv arrays
        private const val PRICE_TYPE_AMAZON = 0
        private const val PRICE_TYPE_NEW = 1
        private const val PRICE_TYPE_USED = 2
        // DVD & Blu-ray root category on Amazon
        private const val CATEGORY_DVD_BLURAY = 2625373011L
    }

    override fun lookupByAsin(asins: List<String>): List<KeepaProductResult> {
        if (asins.isEmpty()) return emptyList()
        require(asins.size <= 100) { "Keepa supports up to 100 ASINs per request" }

        val asinCsv = asins.joinToString(",")
        val url = "$BASE_URL/product?key=$apiKey&domain=$DOMAIN_US&asin=$asinCsv&stats=90"

        val response = executeRequest(url)
        if (response == null) return asins.map { KeepaProductResult(found = false, asin = it, errorMessage = "Request failed") }

        return parseProductResponse(response, asins)
    }

    override fun lookupByUpc(upc: String): KeepaProductResult {
        val url = "$BASE_URL/product?key=$apiKey&domain=$DOMAIN_US&code=$upc&stats=90"

        val response = executeRequest(url)
            ?: return KeepaProductResult(found = false, errorMessage = "Request failed")

        val results = parseProductResponse(response, listOf(upc))
        return results.firstOrNull() ?: KeepaProductResult(found = false, errorMessage = "No product found for UPC $upc")
    }

    override fun searchByTitle(title: String, format: String?): String? {
        return searchTopAsins(title, format, limit = 1).firstOrNull()
    }

    override fun searchCandidates(title: String, format: String?): List<KeepaProductResult> {
        val searchTerm = if (format != null) "$title $format" else title
        val encoded = java.net.URLEncoder.encode(searchTerm, "UTF-8")
        // Include stats=90 so product results have price data — avoids a second API call
        val url = "$BASE_URL/search?key=$apiKey&domain=$DOMAIN_US&type=product&term=$encoded&rootCategory=$CATEGORY_DVD_BLURAY&stats=90"

        log.info("Keepa candidate search for: '{}'", searchTerm)
        val response = executeRequest(url)
        if (response == null) {
            log.warn("Keepa candidate search failed (null response) for: '{}'", searchTerm)
            return emptyList()
        }

        return try {
            val json = JsonParser.parseString(response).asJsonObject
            val tokensLeft = json.get("tokensLeft")?.asInt ?: 0
            val products = json.getAsJsonArray("products")
            if (products == null || products.isEmpty) {
                log.info("Keepa candidate search returned no products for: '{}'", searchTerm)
                return emptyList()
            }
            val results = products.take(5).map { parseProduct(it.asJsonObject, tokensLeft, "") }
            log.info("Keepa candidate search found {} results for '{}'", results.size, searchTerm)
            results
        } catch (e: Exception) {
            log.warn("Failed to parse Keepa candidate search response: {}", e.message)
            emptyList()
        }
    }

    private fun searchTopAsins(title: String, format: String?, limit: Int): List<String> {
        val searchTerm = if (format != null) "$title $format" else title
        val encoded = java.net.URLEncoder.encode(searchTerm, "UTF-8")
        val url = "$BASE_URL/search?key=$apiKey&domain=$DOMAIN_US&type=product&term=$encoded&rootCategory=$CATEGORY_DVD_BLURAY"

        val response = executeRequest(url) ?: return emptyList()

        return try {
            val json = JsonParser.parseString(response).asJsonObject

            // Keepa search may return asinList (simple search) or products (detailed search)
            val asinList = json.getAsJsonArray("asinList")
            if (asinList != null && !asinList.isEmpty) {
                return asinList.take(limit).map { it.asString }
            }

            // Fall back to extracting ASINs from products array
            val products = json.getAsJsonArray("products")
            if (products != null && !products.isEmpty) {
                return products.take(limit).mapNotNull { p ->
                    p.asJsonObject.get("asin")?.asString
                }
            }

            emptyList()
        } catch (e: Exception) {
            log.warn("Failed to parse Keepa search response: {}", e.message)
            emptyList()
        }
    }

    private fun executeRequest(url: String): String? {
        try {
            // Mask API key in logs
            val logUrl = url.replace(apiKey, "***")
            log.debug("Keepa request: {}", logUrl)


            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept-Encoding", "gzip")
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            val body = decodeBody(response)

            if (response.statusCode() == 429) {
                log.warn("Keepa rate limited (429)")
                return null
            }
            if (response.statusCode() == 402) {
                log.warn("Keepa insufficient tokens (402)")
                return null
            }
            if (response.statusCode() == 401) {
                log.error("Keepa API key invalid (401)")
                return null
            }
            if (response.statusCode() != 200) {
                log.warn("Keepa returned HTTP {}: {}", response.statusCode(), body?.take(500))
                return null
            }

            return body
        } catch (e: Exception) {
            log.error("Keepa request failed: {}", e.message)
            return null
        }
    }

    private fun decodeBody(response: HttpResponse<ByteArray>): String? {
        val bytes = response.body() ?: return null
        val encoding = response.headers().firstValue("Content-Encoding").orElse("")
        return if (encoding == "gzip") {
            GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader().readText()
        } else {
            String(bytes)
        }
    }

    private fun parseProductResponse(json: String, keys: List<String>): List<KeepaProductResult> {
        try {
            val root = JsonParser.parseString(json).asJsonObject
            val tokensLeft = root.get("tokensLeft")?.asInt ?: 0

            // Check for API error response
            val error = root.getAsJsonObject("error")
            if (error != null) {
                val errorType = error.get("type")?.asString ?: "unknown"
                val errorMsg = error.get("message")?.asString ?: ""
                log.error("Keepa API error: {} - {}", errorType, errorMsg)
                return keys.map { KeepaProductResult(found = false, asin = it, tokensLeft = tokensLeft, errorMessage = "$errorType: $errorMsg") }
            }

            val productsElement = root.get("products")
            if (productsElement == null || productsElement.isJsonNull) {
                log.warn("Keepa response has null products field (tokensLeft={})", tokensLeft)
                return keys.map { KeepaProductResult(found = false, asin = it, tokensLeft = tokensLeft) }
            }

            val products = productsElement.asJsonArray
            if (products.isEmpty) {
                return keys.map { KeepaProductResult(found = false, asin = it, tokensLeft = tokensLeft) }
            }

            return products.mapNotNull { element ->
                if (element == null || element.isJsonNull) {
                    log.warn("Null element in Keepa products array, skipping")
                    null
                } else {
                    parseProduct(element.asJsonObject, tokensLeft, json)
                }
            }
        } catch (e: Exception) {
            log.error("Failed to parse Keepa product response: {} — response preview: {}", e.message, json.take(500))
            return keys.map { KeepaProductResult(found = false, asin = it, errorMessage = "Parse error: ${e.message}") }
        }
    }

    private fun parseProduct(product: JsonObject, tokensLeft: Int, rawJson: String): KeepaProductResult {
        val asin = product.get("asin")?.asString
        val titleElement = product.get("title")
        val title = if (titleElement != null && !titleElement.isJsonNull) titleElement.asString else null

        // Parse stats object for price averages — may be null or JsonNull
        val statsElement = product.get("stats")
        val stats = if (statsElement != null && statsElement.isJsonObject) statsElement.asJsonObject else null
        var priceNewCurrent: BigDecimal? = null
        var priceNewAvg30d: BigDecimal? = null
        var priceNewAvg90d: BigDecimal? = null
        var priceAmazonCurrent: BigDecimal? = null
        var priceUsedCurrent: BigDecimal? = null
        var offerCountNew: Int? = null
        var offerCountUsed: Int? = null

        if (stats != null) {
            fun safeArray(key: String) = stats.get(key)?.let {
                if (it.isJsonArray) it.asJsonArray else null
            }

            // current[] array: index 0=Amazon, 1=New 3P, 2=Used
            val current = safeArray("current")
            if (current != null && current.size() > PRICE_TYPE_NEW) {
                priceAmazonCurrent = keepaCentsToPrice(current[PRICE_TYPE_AMAZON].asInt)
                priceNewCurrent = keepaCentsToPrice(current[PRICE_TYPE_NEW].asInt)
                if (current.size() > PRICE_TYPE_USED) {
                    priceUsedCurrent = keepaCentsToPrice(current[PRICE_TYPE_USED].asInt)
                }
            }

            // avg30[] and avg90[] arrays: same indices
            val avg30 = safeArray("avg30")
            if (avg30 != null && avg30.size() > PRICE_TYPE_NEW) {
                priceNewAvg30d = keepaCentsToPrice(avg30[PRICE_TYPE_NEW].asInt)
            }

            val avg90 = safeArray("avg90")
            if (avg90 != null && avg90.size() > PRICE_TYPE_NEW) {
                priceNewAvg90d = keepaCentsToPrice(avg90[PRICE_TYPE_NEW].asInt)
            }

            // offerCountCurrent[] array: index 0=New, 1=Used
            val offerCounts = safeArray("offerCountCurrent")
            if (offerCounts != null) {
                if (offerCounts.size() > 0) offerCountNew = offerCounts[0].asInt
                if (offerCounts.size() > 1) offerCountUsed = offerCounts[1].asInt
            }
        }

        return KeepaProductResult(
            found = true,
            asin = asin,
            title = title,
            priceNewCurrent = priceNewCurrent,
            priceNewAvg30d = priceNewAvg30d,
            priceNewAvg90d = priceNewAvg90d,
            priceAmazonCurrent = priceAmazonCurrent,
            priceUsedCurrent = priceUsedCurrent,
            offerCountNew = offerCountNew,
            offerCountUsed = offerCountUsed,
            tokensLeft = tokensLeft,
            rawJson = rawJson
        )
    }

    /** Convert Keepa price (integer cents) to BigDecimal dollars. Returns null for -1 (no data). */
    private fun keepaCentsToPrice(cents: Int): BigDecimal? {
        if (cents < 0) return null
        return BigDecimal.valueOf(cents.toLong()).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
    }
}

/**
 * Mock Keepa service for testing. Returns configurable canned results.
 */
class MockKeepaService : KeepaService {
    val results = mutableMapOf<String, KeepaProductResult>()

    override fun lookupByAsin(asins: List<String>): List<KeepaProductResult> {
        return asins.map { asin ->
            results[asin] ?: KeepaProductResult(found = false, asin = asin, tokensLeft = 999)
        }
    }

    override fun lookupByUpc(upc: String): KeepaProductResult {
        return results[upc] ?: KeepaProductResult(found = false, errorMessage = "Not found", tokensLeft = 999)
    }

    override fun searchByTitle(title: String, format: String?): String? {
        return results.entries.firstOrNull { it.value.title?.contains(title, ignoreCase = true) == true }?.key
    }

    override fun searchCandidates(title: String, format: String?): List<KeepaProductResult> {
        return results.values
            .filter { it.title?.contains(title, ignoreCase = true) == true }
            .take(5)
    }
}

/**
 * Selects the best replacement value from a Keepa result.
 * Priority: 30d avg new > current new > Amazon direct > current used.
 */
object PriceSelectionService {
    fun selectPrice(result: KeepaProductResult): BigDecimal? {
        return result.priceNewAvg30d
            ?: result.priceNewCurrent
            ?: result.priceAmazonCurrent
            ?: result.priceUsedCurrent
    }
}
