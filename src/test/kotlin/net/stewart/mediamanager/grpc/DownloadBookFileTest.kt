package net.stewart.mediamanager.grpc

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaItem
import net.stewart.mediamanager.entity.MediaItemTitle
import net.stewart.mediamanager.entity.MediaType as MMMediaType
import net.stewart.mediamanager.entity.Title
import org.junit.After
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end coverage of the M6 book-download RPC pair. Writes a ~2 MiB
 * synthetic EPUB file, points a [MediaItem] at it, calls
 * `DownloadService.DownloadBookFile` through the in-process gRPC harness,
 * and confirms the reassembled bytes are byte-identical to the source.
 *
 * Also exercises `GetBookManifest` for completeness — that's the other
 * half of the contract iOS will use.
 */
class DownloadBookFileTest : GrpcTestBase() {

    private val tempFiles = mutableListOf<Path>()

    @After
    fun cleanupFiles() {
        tempFiles.forEach { runCatching { Files.deleteIfExists(it) } }
        tempFiles.clear()
    }

    private fun writeTempEbook(sizeBytes: Int): Path {
        val bytes = ByteArray(sizeBytes).also { Random(42).nextBytes(it) }
        val f = Files.createTempFile("test-", ".epub")
        Files.write(f, bytes)
        tempFiles.add(f)
        return f
    }

    private fun stageBook(filePath: Path, titleName: String = "Foundation"): Long {
        val now = LocalDateTime.now()
        val title = Title(
            name = titleName,
            media_type = MMMediaType.BOOK.name,
            sort_name = titleName.lowercase(),
            created_at = now,
            updated_at = now
        )
        title.save()

        val item = MediaItem(
            media_format = MediaFormat.EBOOK_EPUB.name,
            title_count = 1,
            file_path = filePath.toAbsolutePath().toString(),
            product_name = titleName,
            created_at = now,
            updated_at = now
        )
        item.save()

        MediaItemTitle(media_item_id = item.id!!, title_id = title.id!!).save()
        return item.id!!
    }

    @Test
    fun `GetBookManifest returns size, title, and format`() {
        val user = createAdminUser()
        val ch = authenticatedChannel(user)
        val file = writeTempEbook(4096)
        val itemId = stageBook(file)

        val stub = DownloadServiceGrpcKt.DownloadServiceCoroutineStub(ch)
        val manifest = runBlocking {
            stub.getBookManifest(bookManifestRequest { mediaItemId = itemId })
        }

        assertEquals(itemId, manifest.mediaItemId)
        assertEquals("Foundation", manifest.titleName)
        assertEquals(4096L, manifest.fileSizeBytes)
        assertEquals(MediaFormat.EBOOK_EPUB.name, manifest.mediaFormat)
        assertEquals("Foundation.epub", manifest.suggestedFilename)

        ch.shutdownNow()
    }

    @Test
    fun `DownloadBookFile streams chunks that reassemble byte-identical`() {
        // Spans 2+ chunk boundaries at the 1 MiB chunk size so we actually
        // exercise the multi-emit path.
        val size = (2 * 1024 * 1024) + 123
        val file = writeTempEbook(size)
        val expected = Files.readAllBytes(file)

        val user = createAdminUser()
        val ch = authenticatedChannel(user)
        val itemId = stageBook(file)

        val stub = DownloadServiceGrpcKt.DownloadServiceCoroutineStub(ch)
        val chunks = runBlocking {
            stub.downloadBookFile(downloadBookFileRequest { mediaItemId = itemId }).toList()
        }

        assertTrue(chunks.isNotEmpty())
        // Total size + is_last semantics must match across every chunk.
        chunks.forEach { assertEquals(size.toLong(), it.totalSize) }
        assertEquals(1, chunks.count { it.isLast }, "exactly one terminal chunk")
        assertEquals(chunks.last(), chunks.first { it.isLast })

        // Offsets are monotonically increasing and cover the file.
        var position = 0L
        for (c in chunks) {
            assertEquals(position, c.offset)
            position += c.data.size()
        }
        assertEquals(size.toLong(), position)

        val reassembled = ByteArray(size)
        var cursor = 0
        for (c in chunks) {
            c.data.copyTo(reassembled, cursor)
            cursor += c.data.size()
        }
        assertContentEquals(expected, reassembled)

        ch.shutdownNow()
    }

    @Test
    fun `DownloadBookFile supports resume via offset`() {
        val size = (1 * 1024 * 1024) + 500
        val file = writeTempEbook(size)
        val expected = Files.readAllBytes(file)

        val user = createAdminUser()
        val ch = authenticatedChannel(user)
        val itemId = stageBook(file)

        // Resume partway through the second chunk.
        val resumeAt = 1_048_600L
        val stub = DownloadServiceGrpcKt.DownloadServiceCoroutineStub(ch)
        val chunks = runBlocking {
            stub.downloadBookFile(downloadBookFileRequest {
                mediaItemId = itemId
                offset = resumeAt
            }).toList()
        }

        assertEquals(resumeAt, chunks.first().offset, "first chunk starts at resumeAt")
        val tail = ByteArray((size - resumeAt).toInt())
        var cursor = 0
        for (c in chunks) {
            c.data.copyTo(tail, cursor)
            cursor += c.data.size()
        }
        assertContentEquals(expected.copyOfRange(resumeAt.toInt(), size), tail)

        ch.shutdownNow()
    }
}
