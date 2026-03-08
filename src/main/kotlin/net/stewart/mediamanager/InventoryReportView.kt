package net.stewart.mediamanager

import com.github.mvysny.karibudsl.v10.*
import com.github.vokorm.findAll
import com.lowagie.text.*
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import com.vaadin.flow.server.StreamResource
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.Title
import java.awt.Color
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Route(value = "report", layout = MainLayout::class)
@PageTitle("Insurance Inventory Report")
class InventoryReportView : KComposite() {

    private val root = ui {
        verticalLayout {
            isPadding = true
            isSpacing = true
            defaultHorizontalComponentAlignment = FlexComponent.Alignment.CENTER

            h3("Insurance Inventory Report")

            add(Span("Generate a report of all media purchases for insurance documentation.").apply {
                style.set("color", "var(--lumo-secondary-text-color)")
            })

            val timestamp = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

            val pdfResource = StreamResource("inventory-report-$timestamp.pdf") {
                ByteArrayInputStream(generatePdf())
            }
            pdfResource.setContentType("application/pdf")

            val csvResource = StreamResource("inventory-report-$timestamp.csv") {
                ByteArrayInputStream(generateCsv())
            }
            csvResource.setContentType("text/csv")

            val pdfButton = Button("Download PDF", VaadinIcon.DOWNLOAD.create()).apply {
                addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            }
            val pdfAnchor = Anchor(pdfResource, "").apply {
                element.setAttribute("download", true)
                add(pdfButton)
            }

            val csvButton = Button("Download CSV", VaadinIcon.TABLE.create()).apply {
                addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            }
            val csvAnchor = Anchor(csvResource, "").apply {
                element.setAttribute("download", true)
                add(csvButton)
            }

            add(HorizontalLayout(pdfAnchor, csvAnchor).apply {
                isSpacing = true
            })
        }
    }

    private data class ReportData(
        val allItems: List<MediaItem>,
        val linksByItem: Map<Long, List<MediaItemTitle>>,
        val allTitles: Map<Long?, Title>
    ) {
        fun titleNames(item: MediaItem): String =
            linksByItem[item.id]
                ?.mapNotNull { allTitles[it.title_id]?.name }
                ?.joinToString(", ")
                ?: "(unlinked)"
    }

    private fun loadData(): ReportData {
        val allItems = MediaItem.findAll()
        val allLinks = MediaItemTitle.findAll()
        val allTitles = Title.findAll().associateBy { it.id }
        val linksByItem = allLinks.groupBy { it.media_item_id }
        return ReportData(allItems, linksByItem, allTitles)
    }

    private fun sortKey(titleName: String): String {
        val lower = titleName.lowercase().trim()
        return if (lower.startsWith("the ")) lower.removePrefix("the ").trim() else lower
    }

    // ---- CSV ----

    private fun generateCsv(): ByteArray {
        val data = loadData()
        val sorted = data.allItems.sortedBy { sortKey(data.titleNames(it)) }
        val sb = StringBuilder()

        sb.appendLine("Title(s),Format,UPC,Purchase Date,Purchase Place,Order #,Purchase Price")

        for (item in sorted) {
            sb.append(csvField(data.titleNames(item)))
            sb.append(',')
            sb.append(csvField(item.media_format.replace("_", " ")))
            sb.append(',')
            sb.append(csvField(item.upc ?: ""))
            sb.append(',')
            sb.append(csvField(item.purchase_date?.format(DateTimeFormatter.ofPattern("MM/dd/yyyy")) ?: ""))
            sb.append(',')
            sb.append(csvField(item.purchase_place ?: ""))
            sb.append(',')
            sb.append(csvField(item.amazon_order_id ?: ""))
            sb.append(',')
            sb.append(csvField(item.purchase_price?.setScale(2)?.toString() ?: ""))
            sb.appendLine()
        }

        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun csvField(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    // ---- PDF ----

    private fun generatePdf(): ByteArray {
        val data = loadData()
        val valued = data.allItems.filter { it.purchase_price != null }
        val unvalued = data.allItems.filter { it.purchase_price == null }

        val out = ByteArrayOutputStream()
        val document = Document(PageSize.LETTER, 36f, 36f, 36f, 36f)
        PdfWriter.getInstance(document, out)
        document.open()

        val titleFont = Font(Font.HELVETICA, 18f, Font.BOLD)
        val sectionFont = Font(Font.HELVETICA, 14f, Font.BOLD)
        val sellerFont = Font(Font.HELVETICA, 11f, Font.BOLD)
        val normalFont = Font(Font.HELVETICA, 9f, Font.NORMAL)
        val boldFont = Font(Font.HELVETICA, 9f, Font.BOLD)
        val headerFont = Font(Font.HELVETICA, 9f, Font.BOLD, Color.WHITE)
        val subtotalFont = Font(Font.HELVETICA, 10f, Font.BOLD)
        val totalFont = Font(Font.HELVETICA, 12f, Font.BOLD)

        // Report title
        val titlePara = Paragraph("Insurance Inventory Report", titleFont)
        titlePara.alignment = Element.ALIGN_CENTER
        document.add(titlePara)

        val datePara = Paragraph(
            "Generated: ${LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))}",
            normalFont
        )
        datePara.alignment = Element.ALIGN_CENTER
        datePara.spacingAfter = 20f
        document.add(datePara)

        // Section 1: Purchases with Valuations — grouped by seller
        val sec1 = Paragraph("Purchases with Valuations", sectionFont)
        sec1.spacingAfter = 8f
        document.add(sec1)

        if (valued.isEmpty()) {
            document.add(Paragraph("No purchases with valuations.", normalFont))
        } else {
            val bySeller = valued.groupBy { it.purchase_place ?: "Unknown" }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)

            var grandTotal = BigDecimal.ZERO

            for ((seller, items) in bySeller) {
                val sorted = items.sortedBy { sortKey(data.titleNames(it)) }

                val sellerPara = Paragraph(seller, sellerFont)
                sellerPara.spacingBefore = 10f
                sellerPara.spacingAfter = 4f
                document.add(sellerPara)

                val hasOrders = sorted.any { it.amazon_order_id != null }
                val table = createValuedTable(sorted, data, headerFont, normalFont, boldFont, hasOrders)
                document.add(table)

                val subtotal = sorted.mapNotNull { it.purchase_price }.fold(BigDecimal.ZERO, BigDecimal::add)
                grandTotal += subtotal
                val subtotalPara = Paragraph("Subtotal: \$${subtotal.setScale(2)}", subtotalFont)
                subtotalPara.alignment = Element.ALIGN_RIGHT
                subtotalPara.spacingBefore = 4f
                document.add(subtotalPara)
            }

            val totalPara = Paragraph("Total Value: \$${grandTotal.setScale(2)}", totalFont)
            totalPara.alignment = Element.ALIGN_RIGHT
            totalPara.spacingBefore = 12f
            totalPara.spacingAfter = 20f
            document.add(totalPara)
        }

        // Section 2: Purchases without Valuations
        val sec2 = Paragraph("Purchases without Valuations", sectionFont)
        sec2.spacingBefore = 16f
        sec2.spacingAfter = 8f
        document.add(sec2)

        if (unvalued.isEmpty()) {
            document.add(Paragraph("All purchases have valuations.", normalFont))
        } else {
            val sorted = unvalued.sortedBy { sortKey(data.titleNames(it)) }
            val table = createUnvaluedTable(sorted, data, headerFont, normalFont, boldFont)
            document.add(table)
        }

        document.close()
        return out.toByteArray()
    }

    private fun createValuedTable(
        items: List<MediaItem>,
        data: ReportData,
        headerFont: Font,
        normalFont: Font,
        boldFont: Font,
        includeOrderNumber: Boolean
    ): PdfPTable {
        val colCount = if (includeOrderNumber) 6 else 5
        val table = PdfPTable(colCount)
        table.widthPercentage = 100f

        if (includeOrderNumber) {
            table.setWidths(floatArrayOf(3f, 1f, 1.2f, 1.5f, 1.5f, 1f))
        } else {
            table.setWidths(floatArrayOf(3.5f, 1f, 1.2f, 1.5f, 1f))
        }

        val headerBg = Color(51, 51, 51)
        fun addHeader(text: String) {
            val cell = PdfPCell(Phrase(text, headerFont))
            cell.backgroundColor = headerBg
            cell.setPadding(5f)
            table.addCell(cell)
        }

        addHeader("Title(s)")
        addHeader("Format")
        addHeader("UPC")
        addHeader("Purchase Date")
        if (includeOrderNumber) addHeader("Order #")
        addHeader("Purchase Price")

        val altBg = Color(245, 245, 245)
        items.forEachIndexed { index, item ->
            val bg = if (index % 2 == 0) null else altBg

            fun addCell(text: String, font: Font = normalFont, align: Int = Element.ALIGN_LEFT) {
                val cell = PdfPCell(Phrase(text, font))
                cell.setPadding(4f)
                cell.horizontalAlignment = align
                if (bg != null) cell.backgroundColor = bg
                table.addCell(cell)
            }

            addCell(data.titleNames(item), boldFont)
            addCell(item.media_format.replace("_", " "))
            addCell(item.upc ?: "")
            addCell(item.purchase_date?.format(DateTimeFormatter.ofPattern("MM/dd/yyyy")) ?: "")
            if (includeOrderNumber) addCell(item.amazon_order_id ?: "")
            addCell("\$${item.purchase_price!!.setScale(2)}", normalFont, Element.ALIGN_RIGHT)
        }

        return table
    }

    private fun createUnvaluedTable(
        items: List<MediaItem>,
        data: ReportData,
        headerFont: Font,
        normalFont: Font,
        boldFont: Font
    ): PdfPTable {
        val table = PdfPTable(4)
        table.widthPercentage = 100f
        table.setWidths(floatArrayOf(4f, 1f, 1.5f, 1.5f))

        val headerBg = Color(51, 51, 51)
        fun addHeader(text: String) {
            val cell = PdfPCell(Phrase(text, headerFont))
            cell.backgroundColor = headerBg
            cell.setPadding(5f)
            table.addCell(cell)
        }

        addHeader("Title(s)")
        addHeader("Format")
        addHeader("UPC")
        addHeader("Purchase Place")

        val altBg = Color(245, 245, 245)
        items.forEachIndexed { index, item ->
            val bg = if (index % 2 == 0) null else altBg

            fun addCell(text: String, font: Font = normalFont, align: Int = Element.ALIGN_LEFT) {
                val cell = PdfPCell(Phrase(text, font))
                cell.setPadding(4f)
                cell.horizontalAlignment = align
                if (bg != null) cell.backgroundColor = bg
                table.addCell(cell)
            }

            addCell(data.titleNames(item), boldFont)
            addCell(item.media_format.replace("_", " "))
            addCell(item.upc ?: "")
            addCell(item.purchase_place ?: "")
        }

        return table
    }
}
