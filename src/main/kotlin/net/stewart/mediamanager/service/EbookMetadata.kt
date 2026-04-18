package net.stewart.mediamanager.service

import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Extracts [EbookMetadata] from an on-disk .epub / .pdf file.
 *
 * EPUBs are a zip containing `META-INF/container.xml` → an OPF manifest
 * with `dc:title` / `dc:creator` / `dc:identifier` entries. We parse
 * these directly with `java.util.zip` + the built-in XML parser so no
 * extra Gradle dependency is needed.
 *
 * PDFs don't universally have ISBNs and extracting their metadata cleanly
 * needs a PDF library. For M4 we return an empty metadata record for PDFs
 * — they fall into the unmatched queue for admin resolution. PDF support
 * can pull in PDFBox when it becomes a priority.
 */
data class EbookMetadata(
    val title: String?,
    val author: String?,
    val isbn: String?
) {
    companion object { val EMPTY = EbookMetadata(null, null, null) }
}

object EbookMetadataExtractor {

    private val log = LoggerFactory.getLogger(EbookMetadataExtractor::class.java)

    fun extract(file: File): EbookMetadata {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".epub") -> runCatching { extractEpub(file) }
                .onFailure { log.warn("EPUB parse failed for {}: {}", file, it.message) }
                .getOrElse { EbookMetadata.EMPTY }
            name.endsWith(".pdf") -> EbookMetadata.EMPTY
            else -> EbookMetadata.EMPTY
        }
    }

    internal fun extractEpub(file: File): EbookMetadata {
        ZipFile(file).use { zip ->
            // Step 1: META-INF/container.xml names the OPF file.
            val container = zip.getEntry("META-INF/container.xml")
                ?: return EbookMetadata.EMPTY
            val opfPath = zip.getInputStream(container).use { parseContainerForOpfPath(it) }
                ?: return EbookMetadata.EMPTY

            // Step 2: the OPF holds the Dublin Core metadata.
            val opfEntry = zip.getEntry(opfPath) ?: return EbookMetadata.EMPTY
            val parsed = zip.getInputStream(opfEntry).use { parseOpf(it) }
            return parsed
        }
    }

    /** Extracts the `full-path` attribute from the EPUB container.xml. */
    internal fun parseContainerForOpfPath(stream: InputStream): String? {
        val doc = newDocumentBuilder().parse(stream)
        val rootfiles = doc.getElementsByTagName("rootfile")
        if (rootfiles.length == 0) return null
        val el = rootfiles.item(0) as? org.w3c.dom.Element ?: return null
        return el.getAttribute("full-path").takeIf { it.isNotBlank() }
    }

    /** Extracts dc:title / dc:creator / dc:identifier (ISBN) from an OPF stream. */
    internal fun parseOpf(stream: InputStream): EbookMetadata {
        val doc = newDocumentBuilder().parse(stream)

        val title = firstElementText(doc, "dc:title") ?: firstElementText(doc, "title")
        val creator = firstElementText(doc, "dc:creator") ?: firstElementText(doc, "creator")
        val isbn = extractIsbn(doc)

        return EbookMetadata(title, creator, isbn)
    }

    /**
     * The OPF can carry several `dc:identifier` rows (UUID, ISBN, DOI, etc.).
     * Prefer one tagged with `opf:scheme="ISBN"`; otherwise accept any value
     * that matches an ISBN shape after stripping punctuation.
     */
    internal fun extractIsbn(doc: org.w3c.dom.Document): String? {
        val ids = doc.getElementsByTagName("dc:identifier")
        val fallback = doc.getElementsByTagName("identifier")

        fun pickFrom(nodes: org.w3c.dom.NodeList): String? {
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? org.w3c.dom.Element ?: continue
                val scheme = el.getAttribute("opf:scheme")
                val text = el.textContent?.trim().orEmpty()
                val normalized = normalizeIsbn(text)
                if (normalized != null &&
                    (scheme.equals("ISBN", ignoreCase = true) || text.contains("isbn", ignoreCase = true))
                ) {
                    return normalized
                }
            }
            // Second pass: any shape-matching 10/13-digit string.
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? org.w3c.dom.Element ?: continue
                val text = el.textContent?.trim().orEmpty()
                val normalized = normalizeIsbn(text)
                if (normalized != null) return normalized
            }
            return null
        }

        return pickFrom(ids) ?: pickFrom(fallback)
    }

    /** Strips `urn:isbn:` / `isbn:` prefixes + hyphens; returns null if not a 10/13-digit ISBN. */
    internal fun normalizeIsbn(raw: String): String? {
        val stripped = raw
            .replace(Regex("(?i)^urn:isbn:"), "")
            .replace(Regex("(?i)^isbn[:\\s-]*"), "")
            .replace(Regex("[\\s-]+"), "")
        if (stripped.matches(Regex("^\\d{10}(\\d{3})?$"))) return stripped
        if (stripped.matches(Regex("^\\d{9}[0-9Xx]$"))) return stripped.uppercase()
        return null
    }

    private fun firstElementText(doc: org.w3c.dom.Document, tag: String): String? {
        val nodes = doc.getElementsByTagName(tag)
        if (nodes.length == 0) return null
        val text = nodes.item(0).textContent?.trim()
        return text?.ifBlank { null }
    }

    private fun newDocumentBuilder(): javax.xml.parsers.DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // XXE hardening — we never want external entity resolution here.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder()
    }
}
