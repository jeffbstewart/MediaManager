package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.OwnershipPhoto
import org.flywaydb.core.Flyway
import java.time.LocalDateTime
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [OwnershipPhotoStorage] — focused on the pure path / slug
 * helpers and the DB-driven sequence allocation. Filesystem-bound paths
 * (writeFile, getFile, moveToSlug) are exercised indirectly elsewhere;
 * here we lock down the disk-path SHAPE so a future refactor can't
 * silently change the on-disk layout.
 */
class OwnershipPhotoStorageTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:photostoragetest;DB_CLOSE_DELAY=-1"
                username = "sa"; password = ""
            })
            JdbiOrm.setDataSource(dataSource)
            Flyway.configure().dataSource(dataSource).load().migrate()
        }

        @AfterClass @JvmStatic
        fun teardownDatabase() {
            JdbiOrm.destroy()
            dataSource.close()
        }
    }

    @Before
    fun reset() {
        OwnershipPhoto.deleteAll()
    }

    // ---------------------- slugify ----------------------

    @Test
    fun `slugify lowercases and strips non-alphanumeric`() {
        assertEquals("thematrix", OwnershipPhotoStorage.slugify("The Matrix"))
        assertEquals("startrekii", OwnershipPhotoStorage.slugify("Star Trek II:"))
        assertEquals("oceans11", OwnershipPhotoStorage.slugify("Ocean's 11"))
    }

    @Test
    fun `slugify truncates to 15 characters`() {
        // "The Lord of the Rings: The Return of the King" -> "thelordoftherin" (15 chars).
        val slug = OwnershipPhotoStorage.slugify("The Lord of the Rings: The Return of the King")
        assertNotNull(slug)
        assertEquals(15, slug.length)
        assertEquals("thelordoftherin", slug)
    }

    @Test
    fun `slugify returns null when nothing alphanumeric remains`() {
        assertNull(OwnershipPhotoStorage.slugify(""))
        assertNull(OwnershipPhotoStorage.slugify("   "))
        assertNull(OwnershipPhotoStorage.slugify("***!!!"))
    }

    // ---------------------- legacyPath ----------------------

    @Test
    fun `legacyPath uses first two pairs of UUID hex chars as shards`() {
        // Synthetic test UUID — already in lifecycle/presubmit-allowlist.txt.
        val uuid = "abcdefab-abcd-abcd-abcd-abcdefabcdef"
        assertEquals("ab/cd/$uuid.jpg",
            OwnershipPhotoStorage.legacyPath(uuid, "image/jpeg"))
        assertEquals("ab/cd/$uuid.png",
            OwnershipPhotoStorage.legacyPath(uuid, "image/png"))
    }

    @Test
    fun `legacyPath maps content types to extensions`() {
        val uuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        assertEquals("aa/aa/$uuid.png",
            OwnershipPhotoStorage.legacyPath(uuid, "image/png"))
        assertEquals("aa/aa/$uuid.webp",
            OwnershipPhotoStorage.legacyPath(uuid, "image/webp"))
        assertEquals("aa/aa/$uuid.heic",
            OwnershipPhotoStorage.legacyPath(uuid, "image/heic"))
        assertEquals("aa/aa/$uuid.heif",
            OwnershipPhotoStorage.legacyPath(uuid, "image/heif"))
        // Unknown / default — falls back to jpg.
        assertEquals("aa/aa/$uuid.jpg",
            OwnershipPhotoStorage.legacyPath(uuid, "application/octet-stream"))
    }

    // ---------------------- extractSeq ----------------------

    @Test
    fun `extractSeq pulls the trailing integer from slug-based and bare paths`() {
        // Slug-based path: storageKey_slug_seq.ext.
        assertEquals(2, OwnershipPhotoStorage.extractSeq("m/a/786936215595_matrix_2.jpg"))
        assertEquals(7, OwnershipPhotoStorage.extractSeq("t/h/786936215595_thelordoftherin_7.jpg"))
        // Bare path: storageKey_seq.ext.
        assertEquals(3, OwnershipPhotoStorage.extractSeq("5/9/786936215595_3.jpg"))
        // Non-numeric trailing token defaults to 1.
        assertEquals(1, OwnershipPhotoStorage.extractSeq("ab/cd/notanumber.jpg"))
    }

    // ---------------------- extractStorageKey ----------------------

    @Test
    fun `extractStorageKey returns the leading storageKey from any layout`() {
        assertEquals("786936215595",
            OwnershipPhotoStorage.extractStorageKey("5/9/786936215595_3.jpg"))
        assertEquals("786936215595",
            OwnershipPhotoStorage.extractStorageKey("m/a/786936215595_matrix_2.jpg"))
        // Legacy UUID path — first underscore-split segment is the UUID.
        // Re-uses an allowlisted synthetic UUID from the test fixture pool.
        assertEquals("abcdefab-abcd-abcd-abcd-abcdefabcdef",
            OwnershipPhotoStorage.extractStorageKey("ab/cd/abcdefab-abcd-abcd-abcd-abcdefabcdef.jpg"))
    }

    // ---------------------- computePath ----------------------

    @Test
    fun `computePath with title produces slug-sharded slug-named filename`() {
        val uniqueId = UpcUniqueId("786936215595")
        // First photo for this UPC — seq starts at 1.
        val path = OwnershipPhotoStorage.computePath(uniqueId, "The Matrix", "image/jpeg")
        assertEquals("t/h/786936215595_thematrix_1.jpg", path)
    }

    @Test
    fun `computePath without a title shards on the uniqueId chars`() {
        // For UPC "786936215595", shard1=upc[7]='1' and shard2=upc[8]='5'.
        val uniqueId = UpcUniqueId("786936215595")
        val path = OwnershipPhotoStorage.computePath(uniqueId, null, "image/jpeg")
        assertEquals("1/5/786936215595_1.jpg", path)
    }

    @Test
    fun `computePath with a single-char slug uses underscore as the second shard`() {
        val uniqueId = UpcUniqueId("786936215595")
        // "X" -> slugify -> "x" (1 char). shard1 = 'x', shard2 = '_'.
        val path = OwnershipPhotoStorage.computePath(uniqueId, "X", "image/jpeg")
        assertEquals("x/_/786936215595_x_1.jpg", path)
    }

    @Test
    fun `computePath increments seq based on existing photos for the same storageKey`() {
        val uniqueId = UpcUniqueId("786936215595")
        // Seed two existing photos for this UPC at seq 1 and seq 2.
        OwnershipPhoto(id = "uuid-1", media_item_id = null, upc = "786936215595",
            content_type = "image/jpeg", orientation = 1,
            captured_at = LocalDateTime.now(),
            disk_path = "t/h/786936215595_thematrix_1.jpg").create()
        OwnershipPhoto(id = "uuid-2", media_item_id = null, upc = "786936215595",
            content_type = "image/jpeg", orientation = 1,
            captured_at = LocalDateTime.now(),
            disk_path = "t/h/786936215595_thematrix_2.jpg").create()

        // Next computePath should return seq=3.
        val path = OwnershipPhotoStorage.computePath(uniqueId, "The Matrix", "image/jpeg")
        assertTrue(path.endsWith("_3.jpg"), "expected seq=3 path, got: $path")
    }

    @Test
    fun `computePath uses null title as a fallback to UPC sharding even when other photos exist`() {
        val uniqueId = UpcUniqueId("786936215595")
        // Existing photo on a different storageKey shouldn't move our seq.
        OwnershipPhoto(id = "uuid-other", media_item_id = null, upc = "999999999999",
            content_type = "image/jpeg", orientation = 1,
            captured_at = LocalDateTime.now(),
            disk_path = "9/9/999999999999_5.jpg").create()
        val path = OwnershipPhotoStorage.computePath(uniqueId, null, "image/jpeg")
        assertEquals("1/5/786936215595_1.jpg", path,
            "different storageKeys keep independent seq counters")
    }

    // ---------------------- resolveAbsolute ----------------------

    @Test
    fun `resolveAbsolute roots the relative path under the storage base dir`() {
        val abs = OwnershipPhotoStorage.resolveAbsolute("a/b/foo.jpg")
        // Don't assert the exact absolute prefix (cwd-dependent); just the suffix.
        assertTrue(abs.toString().replace('\\', '/').endsWith("data/ownership-photos/a/b/foo.jpg"),
            "unexpected absolute path: $abs")
    }
}
