package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.*
import org.flywaydb.core.Flyway
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.*

class UserTitleFlagServiceTest {

    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDatabase() {
            dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:flagtest;DB_CLOSE_DELAY=-1"
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

    private var user1Id: Long = 0
    private var user2Id: Long = 0

    @Before
    fun cleanup() {
        UserTitleFlag.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
        // Create test users for FK constraints
        val u1 = AppUser(username = "testuser1", display_name = "Test User 1", password_hash = "x", access_level = 2)
        u1.save()
        user1Id = u1.id!!
        val u2 = AppUser(username = "testuser2", display_name = "Test User 2", password_hash = "x", access_level = 1)
        u2.save()
        user2Id = u2.id!!
    }

    @After
    fun cleanupAfter() {
        UserTitleFlag.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
    }

    private fun createTitle(name: String): Title {
        val t = Title(name = name, sort_name = name, enrichment_status = "PENDING")
        t.save()
        return t
    }

    @Test
    fun `UserTitleFlag persists and queries`() {
        val title = createTitle("Movie A")
        val flag = UserTitleFlag(
            user_id = user1Id,
            title_id = title.id!!,
            flag = UserFlagType.STARRED.name
        )
        flag.save()
        assertNotNull(flag.id)

        val found = UserTitleFlag.findAll().filter {
            it.user_id == user1Id && it.title_id == title.id && it.flag == UserFlagType.STARRED.name
        }
        assertEquals(1, found.size)
    }

    @Test
    fun `different flags on same title are independent`() {
        val title = createTitle("Movie B")

        UserTitleFlag(user_id = user1Id, title_id = title.id!!, flag = UserFlagType.STARRED.name).save()
        UserTitleFlag(user_id = user1Id, title_id = title.id!!, flag = UserFlagType.HIDDEN.name).save()

        val flags = UserTitleFlag.findAll().filter { it.user_id == user1Id && it.title_id == title.id!! }
        assertEquals(2, flags.size)
        assertTrue(flags.any { it.flag == UserFlagType.STARRED.name })
        assertTrue(flags.any { it.flag == UserFlagType.HIDDEN.name })
    }

    @Test
    fun `different users can flag same title independently`() {
        val title = createTitle("Movie C")

        UserTitleFlag(user_id = user1Id, title_id = title.id!!, flag = UserFlagType.STARRED.name).save()
        UserTitleFlag(user_id = user2Id, title_id = title.id!!, flag = UserFlagType.STARRED.name).save()

        val user1Flags = UserTitleFlag.findAll().filter { it.user_id == user1Id }
        val user2Flags = UserTitleFlag.findAll().filter { it.user_id == user2Id }

        assertEquals(1, user1Flags.size)
        assertEquals(1, user2Flags.size)
    }

    @Test
    fun `query starred title ids for a user`() {
        val title1 = createTitle("Movie D")
        val title2 = createTitle("Movie E")
        val title3 = createTitle("Movie F")

        UserTitleFlag(user_id = user1Id, title_id = title1.id!!, flag = UserFlagType.STARRED.name).save()
        UserTitleFlag(user_id = user1Id, title_id = title3.id!!, flag = UserFlagType.STARRED.name).save()
        UserTitleFlag(user_id = user1Id, title_id = title2.id!!, flag = UserFlagType.HIDDEN.name).save()

        val starred = UserTitleFlag.findAll()
            .filter { it.user_id == user1Id && it.flag == UserFlagType.STARRED.name }
            .map { it.title_id }
            .toSet()

        assertEquals(setOf(title1.id, title3.id), starred)
    }

    @Test
    fun `query hidden title ids for a user`() {
        val title1 = createTitle("Movie G")
        val title2 = createTitle("Movie H")

        UserTitleFlag(user_id = user1Id, title_id = title1.id!!, flag = UserFlagType.HIDDEN.name).save()
        UserTitleFlag(user_id = user1Id, title_id = title2.id!!, flag = UserFlagType.STARRED.name).save()

        val hidden = UserTitleFlag.findAll()
            .filter { it.user_id == user1Id && it.flag == UserFlagType.HIDDEN.name }
            .map { it.title_id }
            .toSet()

        assertEquals(setOf(title1.id), hidden)
    }
}
