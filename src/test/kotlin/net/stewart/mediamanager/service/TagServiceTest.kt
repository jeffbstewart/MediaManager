package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleTag
import org.flywaydb.core.Flyway
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.*

class TagServiceTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:tagtest;DB_CLOSE_DELAY=-1"
                username = "sa"
                password = ""
            })
            JdbiOrm.setDataSource(dataSource)
            Flyway.configure()
                .dataSource(dataSource)
                .load()
                .migrate()
        }

        @AfterClass @JvmStatic
        fun teardownDatabase() {
            JdbiOrm.destroy()
            dataSource.close()
        }
    }

    @Before
    fun cleanup() {
        TitleTag.deleteAll()
        Tag.deleteAll()
        Title.deleteAll()
    }

    @After
    fun cleanupAfter() {
        TitleTag.deleteAll()
        Tag.deleteAll()
        Title.deleteAll()
    }

    private fun createTitle(name: String): Title {
        val t = Title(name = name, sort_name = name, enrichment_status = "PENDING")
        t.save()
        return t
    }

    @Test
    fun `createTag persists and returns tag`() {
        val tag = TagService.createTag("Comfort Movies", "#EF4444")
        assertNotNull(tag.id)
        assertEquals("Comfort Movies", tag.name)
        assertEquals("#EF4444", tag.bg_color)
    }

    @Test
    fun `updateTag changes name and color`() {
        val tag = TagService.createTag("Old Name", "#EF4444")
        val updated = TagService.updateTag(tag.id!!, "New Name", "#3B82F6")
        assertNotNull(updated)
        assertEquals("New Name", updated.name)
        assertEquals("#3B82F6", updated.bg_color)
    }

    @Test
    fun `deleteTag removes tag and cascades title_tag`() {
        val tag = TagService.createTag("Temp", "#EF4444")
        val title = createTitle("Movie A")
        TagService.addTagToTitle(title.id!!, tag.id!!)

        assertEquals(1, TitleTag.count())
        TagService.deleteTag(tag.id!!)
        assertNull(Tag.findById(tag.id!!))
        assertEquals(0, TitleTag.count())
    }

    @Test
    fun `addTagToTitle is idempotent`() {
        val tag = TagService.createTag("Action", "#EF4444")
        val title = createTitle("Movie B")

        TagService.addTagToTitle(title.id!!, tag.id!!)
        TagService.addTagToTitle(title.id!!, tag.id!!)

        assertEquals(1, TitleTag.count())
    }

    @Test
    fun `getTagsForTitle returns tags for a title`() {
        val tag1 = TagService.createTag("Comedy", "#EF4444")
        val tag2 = TagService.createTag("Drama", "#3B82F6")
        val title = createTitle("Movie C")

        TagService.addTagToTitle(title.id!!, tag1.id!!)
        TagService.addTagToTitle(title.id!!, tag2.id!!)

        val tags = TagService.getTagsForTitle(title.id!!)
        assertEquals(2, tags.size)
        assertTrue(tags.any { it.name == "Comedy" })
        assertTrue(tags.any { it.name == "Drama" })
    }

    @Test
    fun `removeTagFromTitle removes association`() {
        val tag = TagService.createTag("Horror", "#EF4444")
        val title = createTitle("Movie D")

        TagService.addTagToTitle(title.id!!, tag.id!!)
        assertEquals(1, TagService.getTagsForTitle(title.id!!).size)

        TagService.removeTagFromTitle(title.id!!, tag.id!!)
        assertEquals(0, TagService.getTagsForTitle(title.id!!).size)
    }

    @Test
    fun `getTitleIdsForTags returns OR union`() {
        val tag1 = TagService.createTag("A", "#EF4444")
        val tag2 = TagService.createTag("B", "#3B82F6")
        val title1 = createTitle("Movie 1")
        val title2 = createTitle("Movie 2")
        val title3 = createTitle("Movie 3")

        TagService.addTagToTitle(title1.id!!, tag1.id!!)
        TagService.addTagToTitle(title2.id!!, tag2.id!!)
        TagService.addTagToTitle(title3.id!!, tag1.id!!)
        TagService.addTagToTitle(title3.id!!, tag2.id!!)

        val result = TagService.getTitleIdsForTags(setOf(tag1.id!!, tag2.id!!))
        assertEquals(setOf(title1.id, title2.id, title3.id), result)
    }

    @Test
    fun `getTitleIdsForTags with single tag`() {
        val tag1 = TagService.createTag("C", "#EF4444")
        val title1 = createTitle("Movie X")
        val title2 = createTitle("Movie Y")

        TagService.addTagToTitle(title1.id!!, tag1.id!!)

        val result = TagService.getTitleIdsForTags(setOf(tag1.id!!))
        assertEquals(setOf(title1.id), result)
    }

    @Test
    fun `getTagTitleCounts returns correct counts`() {
        val tag1 = TagService.createTag("D", "#EF4444")
        val tag2 = TagService.createTag("E", "#3B82F6")
        val title1 = createTitle("Movie P")
        val title2 = createTitle("Movie Q")

        TagService.addTagToTitle(title1.id!!, tag1.id!!)
        TagService.addTagToTitle(title2.id!!, tag1.id!!)
        TagService.addTagToTitle(title1.id!!, tag2.id!!)

        val counts = TagService.getTagTitleCounts()
        assertEquals(2, counts[tag1.id])
        assertEquals(1, counts[tag2.id])
    }

    @Test
    fun `isNameUnique validates uniqueness`() {
        val tag = TagService.createTag("Unique", "#EF4444")

        assertFalse(TagService.isNameUnique("Unique"))
        assertFalse(TagService.isNameUnique("unique")) // case insensitive
        assertTrue(TagService.isNameUnique("Different"))
        assertTrue(TagService.isNameUnique("Unique", excludeTagId = tag.id)) // self-exclude
    }
}
