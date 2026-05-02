package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpStatus
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.Artist
import net.stewart.mediamanager.entity.ArtistMembership
import net.stewart.mediamanager.entity.ArtistType
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleArtist
import net.stewart.mediamanager.entity.Track
import net.stewart.mediamanager.service.FakeMusicBrainzService
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ArtistHttpServiceTest : ArmeriaTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("artist") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = ArtistHttpService(musicBrainz = FakeMusicBrainzService())

    @Before
    fun reset() {
        ArtistMembership.deleteAll()
        Track.deleteAll()
        TitleArtist.deleteAll()
        Title.deleteAll()
        Artist.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()
    }

    private fun seedArtist(name: String, type: ArtistType = ArtistType.GROUP): Artist =
        Artist(name = name, sort_name = name.lowercase(),
            artist_type = type.name).apply { save() }

    private fun seedAlbumWithArtist(albumName: String, artist: Artist,
                                    playable: Boolean = false): Title {
        val title = Title(name = albumName, media_type = MediaType.ALBUM.name,
            sort_name = albumName.lowercase()).apply { save() }
        TitleArtist(title_id = title.id!!, artist_id = artist.id!!,
            artist_order = 0).save()
        Track(title_id = title.id!!, track_number = 1, disc_number = 1,
            name = "T1",
            file_path = if (playable) "/nas/x.flac" else null).save()
        return title
    }

    @Test
    fun `list returns 401 unauthenticated`() {
        val resp = service.list(ctxFor("/api/v2/catalog/artists", user = null),
            sort = "albums", q = "", playableOnly = true)
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `list filters playable_only by default`() {
        val admin = getOrCreateUser("admin", level = 2)
        val playableArtist = seedArtist("Playable Band")
        val unplayableArtist = seedArtist("Unplayable Band")
        seedAlbumWithArtist("Album A", playableArtist, playable = true)
        seedAlbumWithArtist("Album B", unplayableArtist, playable = false)

        val resp = service.list(
            ctxFor("/api/v2/catalog/artists", user = admin),
            sort = "albums", q = "", playableOnly = true,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertEquals(1, body.get("total").asInt)
        assertEquals("Playable Band", body.getAsJsonArray("artists")[0]
            .asJsonObject.get("name").asString)
    }

    @Test
    fun `list with playable_only=false includes all artists`() {
        val admin = getOrCreateUser("admin", level = 2)
        val a = seedArtist("Band A")
        seedArtist("Band B")
        seedAlbumWithArtist("Album", a, playable = true)

        val resp = service.list(
            ctxFor("/api/v2/catalog/artists", user = admin),
            sort = "albums", q = "", playableOnly = false,
        )
        assertEquals(2, readJsonObject(resp).get("total").asInt)
    }

    @Test
    fun `list filters by query substring`() {
        val admin = getOrCreateUser("admin", level = 2)
        val matching = seedArtist("Pink Floyd")
        seedArtist("Other Band")
        seedAlbumWithArtist("Wall", matching, playable = true)

        val resp = service.list(
            ctxFor("/api/v2/catalog/artists?q=floyd", user = admin),
            sort = "name", q = "floyd", playableOnly = false,
        )
        val rows = readJsonObject(resp).getAsJsonArray("artists")
        assertEquals(1, rows.size())
        assertEquals("Pink Floyd", rows[0].asJsonObject.get("name").asString)
    }

    @Test
    fun `list sort=name orders by sort_name`() {
        val admin = getOrCreateUser("admin", level = 2)
        val zebra = seedArtist("Zebra")
        val alpha = seedArtist("Alpha")
        // Both need playable albums for default playable_only filter; we
        // pass playableOnly=false here anyway.
        val resp = service.list(
            ctxFor("/api/v2/catalog/artists?sort=name", user = admin),
            sort = "name", q = "", playableOnly = false,
        )
        val names = readJsonObject(resp).getAsJsonArray("artists")
            .map { it.asJsonObject.get("name").asString }
        assertEquals(listOf("Alpha", "Zebra"), names)
    }

    @Test
    fun `detail returns 404 when artistId is unknown`() {
        val resp = service.detail(
            ctxFor("/api/v2/catalog/artists/9999",
                user = getOrCreateUser("admin", level = 2)),
            artistId = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `detail returns the artist + owned albums`() {
        val admin = getOrCreateUser("admin", level = 2)
        val artist = seedArtist("Pink Floyd")
        seedAlbumWithArtist("Animals", artist, playable = true)
        seedAlbumWithArtist("The Wall", artist, playable = false)

        val resp = service.detail(
            ctxFor("/api/v2/catalog/artists/${artist.id}", user = admin),
            artistId = artist.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertEquals(artist.id, body.get("id").asLong)
        assertEquals("Pink Floyd", body.get("name").asString)
        assertTrue(body.has("owned_albums"))
    }
}
