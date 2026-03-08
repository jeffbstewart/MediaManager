package net.stewart.mediamanager.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class UpcLookupResult(
    val found: Boolean,
    val productName: String? = null,
    val brand: String? = null,
    val description: String? = null,
    val mediaFormat: String? = null,
    val releaseYear: Int? = null,
    val rawJson: String? = null,
    val apiError: Boolean = false,
    val rateLimited: Boolean = false,
    val errorMessage: String? = null
)

interface UpcLookupService {
    fun lookup(upc: String): UpcLookupResult
}

class UpcItemDbLookupService : UpcLookupService {
    private val log = LoggerFactory.getLogger(UpcItemDbLookupService::class.java)
    private val mapper = ObjectMapper()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    companion object {
        private const val TRIAL_ENDPOINT = "https://api.upcitemdb.com/prod/trial/lookup"
    }

    override fun lookup(upc: String): UpcLookupResult {
        if (!upc.matches(Regex("^\\d{8,14}$"))) {
            log.info("UPC format invalid, skipping API call: {}", upc)
            return UpcLookupResult(found = false)
        }

        val url = "$TRIAL_ENDPOINT?upc=${URLEncoder.encode(upc, Charsets.UTF_8)}"
        log.info("UPCitemdb lookup for UPC: {}", upc)

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()

        val response: HttpResponse<String>
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            log.error("HTTP request failed for UPC {}: {}", upc, e.message)
            return UpcLookupResult(found = false, apiError = true,
                errorMessage = "HTTP request failed: ${e.message}")
        }

        val body = response.body()
        log.debug("UPCitemdb response {}: {}", response.statusCode(), body)

        return parseResponse(response.statusCode(), body)
    }

    internal fun parseResponse(statusCode: Int, body: String): UpcLookupResult {
        return when (statusCode) {
            200 -> parseSuccessBody(body)
            400 -> UpcLookupResult(found = false) // "Not a valid UPC code" — treat as definitive not-found
            404 -> UpcLookupResult(found = false) // valid response, UPC not in database
            429 -> {
                log.warn("UPCitemdb rate limited (429)")
                UpcLookupResult(found = false, apiError = true, rateLimited = true,
                    errorMessage = "Rate limited (429)")
            }
            else -> {
                log.warn("UPCitemdb returned HTTP {}: {}", statusCode, body)
                UpcLookupResult(found = false, apiError = true,
                    errorMessage = "HTTP $statusCode")
            }
        }
    }

    private fun parseSuccessBody(body: String): UpcLookupResult {
        try {
            val root = mapper.readTree(body)
            val items = root.get("items")
            if (items == null || !items.isArray || items.isEmpty) {
                return UpcLookupResult(found = false)
            }

            val item = items[0]
            val title = item.textOrNull("title")
            val brand = item.textOrNull("brand")
            val description = item.textOrNull("description")
            val category = item.textOrNull("category")

            val mediaFormat = detectMediaFormat(title, category, description)

            return UpcLookupResult(
                found = true,
                productName = title,
                brand = brand,
                description = description,
                mediaFormat = mediaFormat,
                rawJson = body
            )
        } catch (e: Exception) {
            log.error("Failed to parse UPCitemdb response: {}", e.message)
            return UpcLookupResult(found = false, apiError = true,
                errorMessage = "JSON parse error: ${e.message}")
        }
    }

    internal fun detectMediaFormat(vararg fields: String?): String? {
        val combined = fields.filterNotNull().joinToString(" ").lowercase()
        return when {
            "4k uhd" in combined || "ultra hd" in combined || "uhd" in combined -> "UHD_BLURAY"
            "hd dvd" in combined || "hd-dvd" in combined -> "HD_DVD"
            "blu-ray" in combined || "bluray" in combined || "blu ray" in combined -> "BLURAY"
            "dvd" in combined -> "DVD"
            else -> null
        }
    }

    private fun JsonNode.textOrNull(field: String): String? {
        val node = this.get(field) ?: return null
        if (node.isNull) return null
        val text = node.asText()
        return text.ifBlank { null }
    }
}

class MockUpcLookupService(
    private val clock: Clock = SystemClock
) : UpcLookupService {

    private data class MockProduct(
        val name: String,
        val brand: String,
        val format: String,
        val year: Int
    )

    private val products = mapOf(
        '0' to MockProduct("The Shawshank Redemption", "Warner Bros", "BLURAY", 1994),
        '1' to MockProduct("The Dark Knight", "Warner Bros", "UHD_BLURAY", 2008),
        '2' to MockProduct("Inception", "Warner Bros", "BLURAY", 2010),
        '3' to MockProduct("Pulp Fiction / Reservoir Dogs Double Feature", "Miramax", "DVD", 1994),
        '4' to MockProduct("The Matrix", "Warner Bros", "UHD_BLURAY", 1999),
        '5' to MockProduct("Breaking Bad Season 1 [Blu-ray]", "Sony", "BLURAY", 2008),
        '6' to MockProduct("Goodfellas", "Warner Bros", "BLURAY", 1990),
        '7' to MockProduct("The Godfather", "Paramount", "UHD_BLURAY", 1972),
        '8' to MockProduct("Schindler's List", "Universal", "BLURAY", 1993),
        '9' to MockProduct("Jurassic Park", "Universal", "UHD_BLURAY", 1993)
    )

    override fun lookup(upc: String): UpcLookupResult {
        // Simulate API latency
        clock.sleep((200L..800L).random().milliseconds)

        val lastDigit = upc.lastOrNull() ?: return UpcLookupResult(found = false)

        // 1 in 10 returns NOT_FOUND (UPCs ending in the digit matching the second-to-last digit)
        val secondToLast = upc.getOrNull(upc.length - 2)
        if (lastDigit == secondToLast) {
            return UpcLookupResult(found = false)
        }

        val product = products[lastDigit] ?: return UpcLookupResult(found = false)

        val json = """{"upc":"$upc","title":"${product.name}","brand":"${product.brand}","format":"${product.format}","year":${product.year},"source":"mock"}"""

        return UpcLookupResult(
            found = true,
            productName = product.name,
            brand = product.brand,
            description = "${product.name} (${product.year}) - ${product.brand}",
            mediaFormat = product.format,
            releaseYear = product.year,
            rawJson = json
        )
    }
}
