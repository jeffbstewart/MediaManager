package net.stewart.mediamanager.grpc

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import net.stewart.mediamanager.entity.MediaType as MediaTypeEntity
import net.stewart.mediamanager.entity.Playlist
import net.stewart.mediamanager.entity.PlaylistProgress
import net.stewart.mediamanager.entity.PlaylistTrack
import net.stewart.mediamanager.entity.Track
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Integration tests for [PlaylistGrpcService] — owner-gated CRUD,
 * track add/remove/reorder, hero, privacy, duplicate, and library
 * shuffle.
 */
class PlaylistGrpcServiceTest : GrpcTestBase() {

    @Before
    fun cleanPlaylistTables() {
        PlaylistProgress.deleteAll()
        PlaylistTrack.deleteAll()
        Playlist.deleteAll()
        Track.deleteAll()
    }

    private fun seedTrack(name: String, filePath: String? = "/some/track.flac"): Track {
        val album = createTitle(name = "Album-$name", mediaType = MediaTypeEntity.ALBUM.name)
        val track = Track(
            title_id = album.id!!,
            track_number = 1,
            disc_number = 1,
            name = name,
            duration_seconds = 180,
            file_path = filePath,
        ).apply { save() }
        return track
    }

    // ---------------------- listPlaylists ----------------------

    @Test
    fun `listPlaylists returns empty when nothing seeded`() = runBlocking {
        val viewer = createViewerUser(username = "pl-list-empty")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(authed)
            val resp = stub.listPlaylists(listPlaylistsRequest {
                scope = PlaylistScope.PLAYLIST_SCOPE_MINE
            })
            assertEquals(0, resp.playlistsCount)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- createPlaylist ----------------------

    @Test
    fun `createPlaylist trims the name and persists owner_user_id`() = runBlocking {
        val owner = createViewerUser(username = "pl-create")
        val authed = authenticatedChannel(owner)
        try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(authed)
            val resp = stub.createPlaylist(createPlaylistRequest {
                name = "  Road Trip  "
                description = "Driving music"
            })
            assertTrue(resp.id > 0)
            assertEquals("Road Trip", resp.name)
            val saved = Playlist.findById(resp.id)!!
            assertEquals(owner.id, saved.owner_user_id)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `createPlaylist rejects blank or whitespace-only names`() = runBlocking {
        val owner = createViewerUser(username = "pl-create-blank")
        val authed = authenticatedChannel(owner)
        try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.createPlaylist(createPlaylistRequest { name = "   " })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- renamePlaylist ----------------------

    @Test
    fun `renamePlaylist mutates name and description for the owner`() = runBlocking {
        val owner = createViewerUser(username = "pl-rename")
        val authed = authenticatedChannel(owner)
        try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(authed)
            val pl = stub.createPlaylist(createPlaylistRequest { name = "Old Name" })
            stub.renamePlaylist(renamePlaylistRequest {
                id = pl.id
                name = "New Name"
                description = "Updated"
            })
            val refreshed = Playlist.findById(pl.id)!!
            assertEquals("New Name", refreshed.name)
            assertEquals("Updated", refreshed.description)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `renamePlaylist refuses non-owners with PERMISSION_DENIED`() = runBlocking {
        val owner = createViewerUser(username = "pl-rename-owner")
        val intruder = createViewerUser(username = "pl-rename-intruder")
        val ownerChannel = authenticatedChannel(owner)
        val pl = try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(ownerChannel)
            stub.createPlaylist(createPlaylistRequest { name = "Mine" })
        } finally {
            ownerChannel.shutdownNow()
        }

        val intruderChannel = authenticatedChannel(intruder)
        try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(intruderChannel)
            val ex = assertFailsWith<StatusException> {
                stub.renamePlaylist(renamePlaylistRequest {
                    id = pl.id
                    name = "Hijacked"
                })
            }
            assertEquals(Status.Code.PERMISSION_DENIED, ex.status.code)
        } finally {
            intruderChannel.shutdownNow()
        }
    }

    @Test
    fun `renamePlaylist returns NOT_FOUND for unknown id`() = runBlocking {
        val owner = createViewerUser(username = "pl-rename-404")
        val authed = authenticatedChannel(owner)
        try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.renamePlaylist(renamePlaylistRequest {
                    id = 999_999
                    name = "X"
                })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `renamePlaylist with blank name returns INVALID_ARGUMENT`() = runBlocking {
        val owner = createViewerUser(username = "pl-rename-blank")
        val authed = authenticatedChannel(owner)
        try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(authed)
            val pl = stub.createPlaylist(createPlaylistRequest { name = "ToRename" })
            val ex = assertFailsWith<StatusException> {
                stub.renamePlaylist(renamePlaylistRequest {
                    id = pl.id
                    name = ""
                })
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- deletePlaylist ----------------------

    @Test
    fun `deletePlaylist removes the row for the owner`() = runBlocking {
        val owner = createViewerUser(username = "pl-delete")
        val authed = authenticatedChannel(owner)
        try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(authed)
            val pl = stub.createPlaylist(createPlaylistRequest { name = "Doomed" })
            assertTrue(Playlist.findById(pl.id) != null)
            stub.deletePlaylist(deletePlaylistRequest { id = pl.id })
            assertTrue(Playlist.findById(pl.id) == null)
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `deletePlaylist refuses non-owner with PERMISSION_DENIED`() = runBlocking {
        val owner = createViewerUser(username = "pl-del-owner")
        val intruder = createViewerUser(username = "pl-del-intruder")
        val ownerChannel = authenticatedChannel(owner)
        val pl = try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(ownerChannel)
            stub.createPlaylist(createPlaylistRequest { name = "Mine2" })
        } finally {
            ownerChannel.shutdownNow()
        }

        val intruderChannel = authenticatedChannel(intruder)
        try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(intruderChannel)
            val ex = assertFailsWith<StatusException> {
                stub.deletePlaylist(deletePlaylistRequest { id = pl.id })
            }
            assertEquals(Status.Code.PERMISSION_DENIED, ex.status.code)
            assertTrue(Playlist.findById(pl.id) != null,
                "intruder's call must not delete the row")
        } finally {
            intruderChannel.shutdownNow()
        }
    }

    // ---------------------- addTracks / removeTrack / reorder ----------------------

    @Test
    fun `addTracksToPlaylist appends tracks and returns the new playlist_track ids`() = runBlocking {
        val owner = createViewerUser(username = "pl-add")
        val t1 = seedTrack("Track A")
        val t2 = seedTrack("Track B")

        val authed = authenticatedChannel(owner)
        try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(authed)
            val pl = stub.createPlaylist(createPlaylistRequest { name = "Trackful" })
            val resp = stub.addTracksToPlaylist(addTracksToPlaylistRequest {
                id = pl.id
                trackIds.addAll(listOf(t1.id!!, t2.id!!))
            })
            assertEquals(2, resp.added)
            assertEquals(2, resp.playlistTrackIdsCount)
            // Persisted in order.
            val rows = PlaylistTrack.findAll().filter { it.playlist_id == pl.id }
                .sortedBy { it.position }
            assertEquals(listOf(t1.id, t2.id), rows.map { it.track_id })
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `removeTrackFromPlaylist drops the row by playlist_track_id`() = runBlocking {
        val owner = createViewerUser(username = "pl-rm-track")
        val t1 = seedTrack("A")
        val authed = authenticatedChannel(owner)
        try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(authed)
            val pl = stub.createPlaylist(createPlaylistRequest { name = "RmTest" })
            val added = stub.addTracksToPlaylist(addTracksToPlaylistRequest {
                id = pl.id
                trackIds.add(t1.id!!)
            })
            assertEquals(1, PlaylistTrack.findAll().count { it.playlist_id == pl.id })

            stub.removeTrackFromPlaylist(removeTrackFromPlaylistRequest {
                id = pl.id
                playlistTrackId = added.playlistTrackIdsList.single()
            })
            assertEquals(0, PlaylistTrack.findAll().count { it.playlist_id == pl.id })
        } finally {
            authed.shutdownNow()
        }
    }

    @Test
    fun `reorderPlaylist updates the position field according to the supplied ordering`() = runBlocking {
        val owner = createViewerUser(username = "pl-reorder")
        val t1 = seedTrack("A")
        val t2 = seedTrack("B")
        val t3 = seedTrack("C")

        val authed = authenticatedChannel(owner)
        try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(authed)
            val pl = stub.createPlaylist(createPlaylistRequest { name = "Reorderable" })
            val added = stub.addTracksToPlaylist(addTracksToPlaylistRequest {
                id = pl.id
                trackIds.addAll(listOf(t1.id!!, t2.id!!, t3.id!!))
            })
            // Reverse the order.
            val newOrder = added.playlistTrackIdsList.reversed()
            stub.reorderPlaylist(reorderPlaylistRequest {
                id = pl.id
                playlistTrackIdsInOrder.addAll(newOrder)
            })
            val rows = PlaylistTrack.findAll().filter { it.playlist_id == pl.id }
                .sortedBy { it.position }
            // New leading row is the originally-last one.
            assertEquals(t3.id, rows.first().track_id)
            assertEquals(t1.id, rows.last().track_id)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- setPlaylistHero ----------------------

    @Test
    fun `setPlaylistHero with track_id 0 clears the override`() = runBlocking {
        val owner = createViewerUser(username = "pl-hero")
        val t = seedTrack("HeroTrack")

        val authed = authenticatedChannel(owner)
        try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(authed)
            val pl = stub.createPlaylist(createPlaylistRequest { name = "WithHero" })
            stub.addTracksToPlaylist(addTracksToPlaylistRequest {
                id = pl.id
                trackIds.add(t.id!!)
            })
            stub.setPlaylistHero(setPlaylistHeroRequest { id = pl.id; trackId = t.id!! })
            assertEquals(t.id, Playlist.findById(pl.id)!!.hero_track_id)

            // Clear: track_id absent.
            stub.setPlaylistHero(setPlaylistHeroRequest { id = pl.id })
            assertEquals(null, Playlist.findById(pl.id)!!.hero_track_id)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- setPlaylistPrivacy ----------------------

    @Test
    fun `setPlaylistPrivacy makes the playlist private and hides it from non-owners`() = runBlocking {
        val owner = createViewerUser(username = "pl-priv-owner")
        val viewer = createViewerUser(username = "pl-priv-viewer")
        val ownerChannel = authenticatedChannel(owner)
        val pl = try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(ownerChannel)
            val created = stub.createPlaylist(createPlaylistRequest { name = "Secret" })
            stub.setPlaylistPrivacy(setPlaylistPrivacyRequest {
                id = created.id; isPrivate = true
            })
            created
        } finally {
            ownerChannel.shutdownNow()
        }

        // Viewer's listVisibleTo must NOT include the private playlist.
        val viewerChannel = authenticatedChannel(viewer)
        try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(viewerChannel)
            val resp = stub.listPlaylists(listPlaylistsRequest {
                scope = PlaylistScope.PLAYLIST_SCOPE_ALL
            })
            assertTrue(resp.playlistsList.none { it.id == pl.id },
                "private playlist must be filtered from non-owner's list")
        } finally {
            viewerChannel.shutdownNow()
        }
    }

    @Test
    fun `getPlaylist returns NOT_FOUND for unknown id and for private from non-owner`() = runBlocking {
        val owner = createViewerUser(username = "pl-get-owner")
        val intruder = createViewerUser(username = "pl-get-intruder")

        // Unknown id -> NOT_FOUND.
        val viewerChannel = authenticatedChannel(intruder)
        try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(viewerChannel)
            val ex = assertFailsWith<StatusException> {
                stub.getPlaylist(getPlaylistRequest { id = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            viewerChannel.shutdownNow()
        }

        // Private playlist from non-owner -> NOT_FOUND (no leak).
        val ownerChannel = authenticatedChannel(owner)
        val privId = try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(ownerChannel)
            val created = stub.createPlaylist(createPlaylistRequest { name = "Private" })
            stub.setPlaylistPrivacy(setPlaylistPrivacyRequest {
                id = created.id; isPrivate = true
            })
            created.id
        } finally {
            ownerChannel.shutdownNow()
        }

        val viewer2Channel = authenticatedChannel(intruder)
        try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(viewer2Channel)
            val ex = assertFailsWith<StatusException> {
                stub.getPlaylist(getPlaylistRequest { id = privId })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code,
                "private playlist must not leak existence to non-owners")
        } finally {
            viewer2Channel.shutdownNow()
        }
    }

    // ---------------------- duplicatePlaylist ----------------------

    @Test
    fun `duplicatePlaylist forks the source for the caller`() = runBlocking {
        val owner = createViewerUser(username = "pl-dup-owner")
        val t1 = seedTrack("DupA")
        val t2 = seedTrack("DupB")

        val ownerChannel = authenticatedChannel(owner)
        val sourceId = try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(ownerChannel)
            val src = stub.createPlaylist(createPlaylistRequest { name = "Source" })
            stub.addTracksToPlaylist(addTracksToPlaylistRequest {
                id = src.id; trackIds.addAll(listOf(t1.id!!, t2.id!!))
            })
            src.id
        } finally {
            ownerChannel.shutdownNow()
        }

        // Anyone can duplicate (a public playlist) into their own copy.
        val viewer = createViewerUser(username = "pl-dup-viewer")
        val viewerChannel = authenticatedChannel(viewer)
        try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(viewerChannel)
            val fork = stub.duplicatePlaylist(duplicatePlaylistRequest {
                this.sourceId = sourceId
                newName = "Fork"
            })
            assertTrue(fork.id != sourceId, "fork has a new id")
            assertEquals("Fork", fork.name)
            assertEquals(viewer.id, Playlist.findById(fork.id)!!.owner_user_id,
                "fork is owned by the duplicator")
            // Fork tracks copied.
            assertEquals(2, PlaylistTrack.findAll().count { it.playlist_id == fork.id })
        } finally {
            viewerChannel.shutdownNow()
        }
    }

    @Test
    fun `duplicatePlaylist returns NOT_FOUND when the source does not exist`() = runBlocking {
        val viewer = createViewerUser(username = "pl-dup-404")
        val authed = authenticatedChannel(viewer)
        try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(authed)
            val ex = assertFailsWith<StatusException> {
                stub.duplicatePlaylist(duplicatePlaylistRequest { sourceId = 999_999 })
            }
            assertEquals(Status.Code.NOT_FOUND, ex.status.code)
        } finally {
            authed.shutdownNow()
        }
    }

    // ---------------------- libraryShuffle ----------------------

    @Test
    fun `libraryShuffle returns up to limit playable tracks`() = runBlocking {
        val viewer = createViewerUser(username = "pl-shuffle")
        // 3 tracks with file_path -> playable; one without -> excluded.
        seedTrack("Sh1")
        seedTrack("Sh2")
        seedTrack("Sh3")
        seedTrack("NoFile", filePath = null)

        val authed = authenticatedChannel(viewer)
        try {
            val stub = PlaylistServiceGrpcKt.PlaylistServiceCoroutineStub(authed)
            val resp = stub.libraryShuffle(libraryShuffleRequest { limit = 2 })
            assertEquals(2, resp.tracksCount, "limit honored")
        } finally {
            authed.shutdownNow()
        }
    }
}
