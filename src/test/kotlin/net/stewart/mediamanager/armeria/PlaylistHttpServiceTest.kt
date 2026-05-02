package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.Playlist
import net.stewart.mediamanager.entity.PlaylistTrack
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.Track
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class PlaylistHttpServiceTest : ArmeriaTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("playlist") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    private val service = PlaylistHttpService()

    @Before
    fun reset() {
        PlaylistTrack.deleteAll()
        Playlist.deleteAll()
        Track.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()
    }

    private fun seedPlaylist(
        ownerId: Long,
        name: String = "P",
        isPrivate: Boolean = false,
        heroTrackId: Long? = null,
    ): Playlist {
        val now = java.time.LocalDateTime.now()
        return Playlist(
            name = name, owner_user_id = ownerId,
            is_private = isPrivate, hero_track_id = heroTrackId,
            created_at = now, updated_at = now,
        ).apply { save() }
    }

    private fun seedAlbumWithTrack(name: String = "Album"): Pair<Title, Track> {
        val title = Title(name = name, media_type = MediaType.ALBUM.name,
            sort_name = name.lowercase()).apply { save() }
        val track = Track(title_id = title.id!!, track_number = 1, disc_number = 1,
            name = "$name Track").apply { save() }
        return title to track
    }

    // ---------------------- listAll / listMine ----------------------

    @Test
    fun `listAll returns 401 unauthenticated`() {
        val resp = service.listAll(ctxFor("/api/v2/playlists", user = null))
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `listAll returns the empty array on a clean catalog`() {
        val resp = service.listAll(
            ctxFor("/api/v2/playlists", user = getOrCreateUser("admin", level = 2))
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertEquals(0, body.getAsJsonArray("playlists").size())
        assertTrue(body.has("smart_playlists"))
    }

    @Test
    fun `listAll returns the user's playlists with hero metadata`() {
        val admin = getOrCreateUser("admin", level = 2)
        seedPlaylist(admin.id!!, name = "My Mixtape")

        val resp = service.listAll(
            ctxFor("/api/v2/playlists", user = admin)
        )
        val rows = readJsonObject(resp).getAsJsonArray("playlists")
        assertEquals(1, rows.size())
        val first = rows[0].asJsonObject
        assertEquals("My Mixtape", first.get("name").asString)
        assertEquals(true, first.get("is_owner").asBoolean)
    }

    @Test
    fun `listMine returns only playlists owned by the calling user`() {
        val owner = getOrCreateUser("owner", level = 1)
        val other = getOrCreateUser("other", level = 1)
        seedPlaylist(owner.id!!, name = "Mine")
        seedPlaylist(other.id!!, name = "Theirs")

        val resp = service.listMine(ctxFor("/api/v2/playlists/mine", user = owner))
        val rows = readJsonObject(resp).getAsJsonArray("playlists")
        assertEquals(1, rows.size())
        assertEquals("Mine", rows[0].asJsonObject.get("name").asString)
    }

    @Test
    fun `listMine returns 401 unauthenticated`() {
        val resp = service.listMine(ctxFor("/api/v2/playlists/mine", user = null))
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    // ---------------------- getSmart ----------------------

    @Test
    fun `getSmart returns 404 for an unknown smart-key`() {
        val resp = service.getSmart(
            ctxFor("/api/v2/playlists/smart/zzz",
                user = getOrCreateUser("admin", level = 2)),
            key = "zzz",
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `getSmart returns 401 unauthenticated`() {
        val resp = service.getSmart(
            ctxFor("/api/v2/playlists/smart/recent", user = null),
            key = "recent",
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    // ---------------------- libraryShuffle ----------------------

    @Test
    fun `libraryShuffle returns 401 unauthenticated`() {
        val resp = service.libraryShuffle(
            ctxFor("/api/v2/playlists/library-shuffle", user = null)
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `libraryShuffle returns the tracks list`() {
        val admin = getOrCreateUser("admin", level = 2)
        seedAlbumWithTrack()
        val resp = service.libraryShuffle(
            ctxFor("/api/v2/playlists/library-shuffle", user = admin)
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertTrue(readJsonObject(resp).has("tracks"))
    }

    // ---------------------- get ----------------------

    @Test
    fun `get returns 401 unauthenticated`() {
        val resp = service.get(
            ctxFor("/api/v2/playlists/1", user = null), id = 1L
        )
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(resp))
    }

    @Test
    fun `get returns 404 when playlist is missing`() {
        val resp = service.get(
            ctxFor("/api/v2/playlists/9999",
                user = getOrCreateUser("admin", level = 2)),
            id = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `get returns 404 when playlist is private and viewer is not the owner`() {
        val owner = getOrCreateUser("owner", level = 1)
        val other = getOrCreateUser("other", level = 1)
        val playlist = seedPlaylist(owner.id!!, name = "Secret", isPrivate = true)

        val resp = service.get(
            ctxFor("/api/v2/playlists/${playlist.id}", user = other),
            id = playlist.id!!,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp),
            "private playlists indistinguishable from missing for non-owners")
    }

    @Test
    fun `get returns full detail with tracks for the owner`() {
        val owner = getOrCreateUser("owner", level = 1)
        val (title, track) = seedAlbumWithTrack()
        val playlist = seedPlaylist(owner.id!!, name = "P1")
        PlaylistTrack(playlist_id = playlist.id!!, track_id = track.id!!,
            position = 0, created_at = java.time.LocalDateTime.now()).save()

        val resp = service.get(
            ctxFor("/api/v2/playlists/${playlist.id}", user = owner),
            id = playlist.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertEquals("P1", body.get("name").asString)
        assertEquals(true, body.get("is_owner").asBoolean)
        val tracks = body.getAsJsonArray("tracks")
        assertEquals(1, tracks.size())
        assertEquals(track.id, tracks[0].asJsonObject.get("track_id").asLong)
        assertEquals(title.id, tracks[0].asJsonObject.get("title_id").asLong)
    }

    // ---------------------- create / rename / delete ----------------------

    @Test
    fun `create returns 400 when name is missing or blank`() {
        val resp = service.create(
            ctxFor("/api/v2/playlists",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"name": ""}""")
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `create persists the playlist with the calling user as owner`() {
        val admin = getOrCreateUser("admin", level = 2)
        val resp = service.create(
            ctxFor("/api/v2/playlists",
                method = HttpMethod.POST, user = admin,
                jsonBody = """{"name": "New List", "description": "desc"}""")
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val body = readJsonObject(resp)
        assertEquals("New List", body.get("name").asString)
        val saved = Playlist.findAll().single()
        assertEquals(admin.id, saved.owner_user_id)
    }

    @Test
    fun `rename returns 404 when playlist does not exist`() {
        val resp = service.rename(
            ctxFor("/api/v2/playlists/9999/rename",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{"name": "New"}"""),
            id = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `rename returns 403 when caller does not own the playlist`() {
        val owner = getOrCreateUser("owner", level = 1)
        val other = getOrCreateUser("other", level = 1)
        val playlist = seedPlaylist(owner.id!!)
        val resp = service.rename(
            ctxFor("/api/v2/playlists/${playlist.id}/rename",
                method = HttpMethod.POST, user = other,
                jsonBody = """{"name": "Stolen"}"""),
            id = playlist.id!!,
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `rename returns 400 when name is blank`() {
        val owner = getOrCreateUser("owner", level = 1)
        val playlist = seedPlaylist(owner.id!!)
        val resp = service.rename(
            ctxFor("/api/v2/playlists/${playlist.id}/rename",
                method = HttpMethod.POST, user = owner,
                jsonBody = """{"name": "  "}"""),
            id = playlist.id!!,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `rename persists the new name`() {
        val owner = getOrCreateUser("owner", level = 1)
        val playlist = seedPlaylist(owner.id!!, name = "Old")
        service.rename(
            ctxFor("/api/v2/playlists/${playlist.id}/rename",
                method = HttpMethod.POST, user = owner,
                jsonBody = """{"name": "New"}"""),
            id = playlist.id!!,
        )
        assertEquals("New", Playlist.findById(playlist.id!!)!!.name)
    }

    @Test
    fun `delete returns 403 for non-owners and 200 for owners`() {
        val owner = getOrCreateUser("owner", level = 1)
        val other = getOrCreateUser("other", level = 1)
        val playlist = seedPlaylist(owner.id!!)
        val nonOwnerResp = service.delete(
            ctxFor("/api/v2/playlists/${playlist.id}",
                method = HttpMethod.DELETE, user = other),
            id = playlist.id!!,
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(nonOwnerResp))
        assertNotNull(Playlist.findById(playlist.id!!), "must not be deleted yet")

        val ownerResp = service.delete(
            ctxFor("/api/v2/playlists/${playlist.id}",
                method = HttpMethod.DELETE, user = owner),
            id = playlist.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(ownerResp))
        assertEquals(null, Playlist.findById(playlist.id!!))
    }

    // ---------------------- addTracks / removeTrack / reorder ----------------------

    @Test
    fun `addTracks returns 400 when track_ids is missing`() {
        val owner = getOrCreateUser("owner", level = 1)
        val playlist = seedPlaylist(owner.id!!)
        val resp = service.addTracks(
            ctxFor("/api/v2/playlists/${playlist.id}/tracks",
                method = HttpMethod.POST, user = owner,
                jsonBody = """{}"""),
            id = playlist.id!!,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `addTracks appends the requested tracks to the playlist`() {
        val owner = getOrCreateUser("owner", level = 1)
        val (_, t1) = seedAlbumWithTrack("A")
        val (_, t2) = seedAlbumWithTrack("B")
        val playlist = seedPlaylist(owner.id!!)

        val resp = service.addTracks(
            ctxFor("/api/v2/playlists/${playlist.id}/tracks",
                method = HttpMethod.POST, user = owner,
                jsonBody = """{"track_ids": [${t1.id}, ${t2.id}]}"""),
            id = playlist.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals(2, readJsonObject(resp).get("added").asInt)
        assertEquals(2, PlaylistTrack.findAll()
            .count { it.playlist_id == playlist.id!! })
    }

    @Test
    fun `removeTrack returns 403 for non-owners`() {
        val owner = getOrCreateUser("owner", level = 1)
        val other = getOrCreateUser("other", level = 1)
        val playlist = seedPlaylist(owner.id!!)
        val resp = service.removeTrack(
            ctxFor("/api/v2/playlists/${playlist.id}/tracks/1",
                method = HttpMethod.DELETE, user = other),
            id = playlist.id!!, playlistTrackId = 1L,
        )
        assertEquals(HttpStatus.FORBIDDEN, statusOf(resp))
    }

    @Test
    fun `reorder returns 400 when playlist_track_ids is missing`() {
        val owner = getOrCreateUser("owner", level = 1)
        val playlist = seedPlaylist(owner.id!!)
        val resp = service.reorder(
            ctxFor("/api/v2/playlists/${playlist.id}/reorder",
                method = HttpMethod.POST, user = owner, jsonBody = """{}"""),
            id = playlist.id!!,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    // ---------------------- privacy + setHero ----------------------

    @Test
    fun `setPrivacy returns 400 when is_private is missing or wrong type`() {
        val owner = getOrCreateUser("owner", level = 1)
        val playlist = seedPlaylist(owner.id!!)
        val resp = service.setPrivacy(
            ctxFor("/api/v2/playlists/${playlist.id}/privacy",
                method = HttpMethod.POST, user = owner,
                jsonBody = """{"is_private": "yes"}"""),
            id = playlist.id!!,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `setPrivacy flips the flag`() {
        val owner = getOrCreateUser("owner", level = 1)
        val playlist = seedPlaylist(owner.id!!)
        service.setPrivacy(
            ctxFor("/api/v2/playlists/${playlist.id}/privacy",
                method = HttpMethod.POST, user = owner,
                jsonBody = """{"is_private": true}"""),
            id = playlist.id!!,
        )
        assertEquals(true, Playlist.findById(playlist.id!!)!!.is_private)
    }

    @Test
    fun `setHero accepts a null track_id to clear the hero override`() {
        val owner = getOrCreateUser("owner", level = 1)
        val (_, track) = seedAlbumWithTrack()
        val playlist = seedPlaylist(owner.id!!, heroTrackId = track.id)

        val resp = service.setHero(
            ctxFor("/api/v2/playlists/${playlist.id}/hero",
                method = HttpMethod.POST, user = owner,
                jsonBody = """{"track_id": null}"""),
            id = playlist.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals(null, Playlist.findById(playlist.id!!)!!.hero_track_id)
    }

    // ---------------------- duplicate ----------------------

    @Test
    fun `duplicate returns 404 for an unknown source playlist`() {
        val resp = service.duplicate(
            ctxFor("/api/v2/playlists/9999/duplicate",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{}"""),
            id = 9999L,
        )
        assertEquals(HttpStatus.NOT_FOUND, statusOf(resp))
    }

    @Test
    fun `duplicate forks an existing playlist into one owned by the caller`() {
        val originalOwner = getOrCreateUser("owner", level = 1)
        val forker = getOrCreateUser("forker", level = 1)
        val source = seedPlaylist(originalOwner.id!!, name = "Source")
        val resp = service.duplicate(
            ctxFor("/api/v2/playlists/${source.id}/duplicate",
                method = HttpMethod.POST, user = forker,
                jsonBody = """{"name": "My Fork"}"""),
            id = source.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        val saved = Playlist.findAll()
            .single { it.name == "My Fork" && it.owner_user_id == forker.id }
        assertEquals(forker.id, saved.owner_user_id)
    }

    // ---------------------- progress + track-completed ----------------------

    @Test
    fun `reportProgress returns 400 when playlist_track_id is missing`() {
        val owner = getOrCreateUser("owner", level = 1)
        val playlist = seedPlaylist(owner.id!!)
        val resp = service.reportProgress(
            ctxFor("/api/v2/playlists/${playlist.id}/progress",
                method = HttpMethod.POST, user = owner,
                jsonBody = """{"position_seconds": 10}"""),
            id = playlist.id!!,
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `reportProgress accepts a valid request and returns ok`() {
        val owner = getOrCreateUser("owner", level = 1)
        val (_, track) = seedAlbumWithTrack()
        val playlist = seedPlaylist(owner.id!!)
        val pt = PlaylistTrack(playlist_id = playlist.id!!,
            track_id = track.id!!, position = 0,
            created_at = java.time.LocalDateTime.now()).apply { save() }
        val resp = service.reportProgress(
            ctxFor("/api/v2/playlists/${playlist.id}/progress",
                method = HttpMethod.POST, user = owner,
                jsonBody = """{"playlist_track_id": ${pt.id},
                                "position_seconds": 30}"""),
            id = playlist.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
        assertEquals(true, readJsonObject(resp).get("ok").asBoolean)
    }

    @Test
    fun `clearProgress returns ok=true even when no resume cursor exists`() {
        val owner = getOrCreateUser("owner", level = 1)
        val playlist = seedPlaylist(owner.id!!)
        val resp = service.clearProgress(
            ctxFor("/api/v2/playlists/${playlist.id}/progress",
                method = HttpMethod.DELETE, user = owner),
            id = playlist.id!!,
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
    }

    @Test
    fun `trackCompleted returns 400 when track_id is missing`() {
        val resp = service.trackCompleted(
            ctxFor("/api/v2/playlists/track-completed",
                method = HttpMethod.POST,
                user = getOrCreateUser("admin", level = 2),
                jsonBody = """{}""")
        )
        assertEquals(HttpStatus.BAD_REQUEST, statusOf(resp))
    }

    @Test
    fun `trackCompleted returns ok for a valid track id`() {
        val admin = getOrCreateUser("admin", level = 2)
        val (_, track) = seedAlbumWithTrack()
        val resp = service.trackCompleted(
            ctxFor("/api/v2/playlists/track-completed",
                method = HttpMethod.POST, user = admin,
                jsonBody = """{"track_id": ${track.id}}""")
        )
        assertEquals(HttpStatus.OK, statusOf(resp))
    }
}
