package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpStatus
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TmdbCollection
import net.stewart.mediamanager.entity.TmdbCollectionPart
import net.stewart.mediamanager.entity.UserTitleFlag
import net.stewart.mediamanager.entity.WishListItem
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class CollectionHttpServiceTest : ArmeriaTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("collection") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = CollectionHttpService()

    @Before
    fun reset() {
        WishListItem.deleteAll()
        UserTitleFlag.deleteAll()
        TmdbCollectionPart.deleteAll()
        Title.deleteAll()
        TmdbCollection.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()
    }

    @Test
    fun `listCollections returns 401 unauthenticated`() {
        val resp = service.listCollections(
            ctxFor("/api/v2/catalog/collections", user = null))
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `listCollections returns only collections with at least one owned title`() {
        val admin = getOrCreateUser("admin", level = 2)
        val ownedColl = TmdbCollection(tmdb_collection_id = 100, name = "Saga Owned",
            poster_path = "/p.jpg").apply { save() }
        val unownedColl = TmdbCollection(tmdb_collection_id = 200, name = "Empty Saga",
            poster_path = "/q.jpg").apply { save() }
        Title(name = "Movie 1", media_type = MediaType.MOVIE.name,
            sort_name = "movie 1", tmdb_collection_id = 100,
            poster_path = "/m.jpg").apply { save() }

        val resp = service.listCollections(
            ctxFor("/api/v2/catalog/collections", user = admin))
        val body = readJsonObject(resp)
        assertEquals(1, body.get("total").asInt)
        assertEquals("Saga Owned",
            body.getAsJsonArray("collections")[0].asJsonObject.get("name").asString)
    }

    @Test
    fun `collectionDetail returns 404 when collection is missing`() {
        val resp = service.collectionDetail(
            ctxFor("/api/v2/catalog/collections/9999",
                user = getOrCreateUser("admin", level = 2)),
            collectionId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `collectionDetail returns parts with owned + unowned distinction`() {
        val admin = getOrCreateUser("admin", level = 2)
        val coll = TmdbCollection(tmdb_collection_id = 50, name = "Trilogy",
            poster_path = "/p.jpg").apply { save() }
        // Owned part
        Title(name = "Movie One", media_type = MediaType.MOVIE.name,
            sort_name = "movie one", tmdb_id = 1,
            tmdb_collection_id = 50,
            poster_path = "/m1.jpg").apply { save() }
        TmdbCollectionPart(collection_id = coll.id!!, tmdb_movie_id = 1,
            title = "Movie One", position = 0).save()
        // Unowned part
        TmdbCollectionPart(collection_id = coll.id!!, tmdb_movie_id = 2,
            title = "Movie Two", position = 1).save()

        val resp = service.collectionDetail(
            ctxFor("/api/v2/catalog/collections/${coll.id}", user = admin),
            collectionId = coll.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertEquals(1, body.get("owned_count").asInt)
        assertEquals(2, body.get("total_parts").asInt)
        val parts = body.getAsJsonArray("parts")
        assertEquals(2, parts.size())
        // Position 0 is owned, position 1 is unowned.
        assertEquals(true, parts[0].asJsonObject.get("owned").asBoolean)
        assertEquals(false, parts[1].asJsonObject.get("owned").asBoolean)
    }
}
