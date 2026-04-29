package net.stewart.mediamanager.service

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicBrainzServiceTest {

    private val svc = MusicBrainzHttpService()

    @Test
    fun `pickFirstReleaseMbid returns first release id`() {
        val body = """
            {
              "releases": [
                { "id": "TEST-release-id-0001", "title": "Foo" },
                { "id": "TEST-release-id-0002", "title": "Bar" }
              ]
            }
        """.trimIndent()
        assertEquals("TEST-release-id-0001", svc.pickFirstReleaseMbid(body))
    }

    @Test
    fun `pickFirstReleaseMbid returns null on empty releases array`() {
        assertNull(svc.pickFirstReleaseMbid("""{"releases":[]}"""))
    }

    @Test
    fun `parseRelease extracts release, release-group, title, artist, tracks`() {
        val body = """
            {
              "id": "TEST-release-id-0001",
              "title": "L.A. Woman",
              "date": "1971-04-19",
              "barcode": "0603497905225",
              "release-group": { "id": "TEST-release-group-la-woman", "title": "L.A. Woman" },
              "artist-credit": [
                { "name": "The Doors",
                  "artist": {
                    "id": "TEST-artist-the-doors",
                    "name": "The Doors",
                    "sort-name": "Doors, The",
                    "type": "Group"
                  }
                }
              ],
              "label-info": [ { "label": { "name": "Elektra" } } ],
              "media": [
                {
                  "position": 1,
                  "tracks": [
                    {
                      "id": "track-uuid-1",
                      "position": 1,
                      "title": "The Changeling",
                      "length": 261000,
                      "recording": { "id": "recording-uuid-1", "title": "The Changeling", "length": 261000 }
                    },
                    {
                      "id": "track-uuid-2",
                      "position": 2,
                      "title": "Love Her Madly",
                      "length": 198000,
                      "recording": { "id": "recording-uuid-2", "title": "Love Her Madly", "length": 198000 }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = svc.parseRelease(body)
        assertTrue(result is MusicBrainzResult.Success)
        val release = result.release

        assertEquals("TEST-release-id-0001", release.musicBrainzReleaseId)
        assertEquals("TEST-release-group-la-woman", release.musicBrainzReleaseGroupId)
        assertEquals("L.A. Woman", release.title)
        assertEquals(1971, release.releaseYear)
        assertEquals("Elektra", release.label)
        assertEquals("0603497905225", release.barcode)
        assertEquals(459, release.totalDurationSeconds)  // 261 + 198

        assertEquals(1, release.albumArtistCredits.size)
        val albumArtist = release.albumArtistCredits.first()
        assertEquals("The Doors", albumArtist.name)
        assertEquals("Doors, The", albumArtist.sortName)
        assertEquals("Group", albumArtist.type)
        assertEquals("TEST-artist-the-doors", albumArtist.musicBrainzArtistId)

        assertEquals(2, release.tracks.size)
        val track1 = release.tracks[0]
        assertEquals(1, track1.trackNumber)
        assertEquals(1, track1.discNumber)
        assertEquals("The Changeling", track1.name)
        assertEquals(261, track1.durationSeconds)
        assertEquals("recording-uuid-1", track1.musicBrainzRecordingId)
        // Per-track credit matches album-level — don't carry a redundant row.
        assertTrue(track1.trackArtistCredits.isEmpty(),
            "single-artist album should not populate track_artist credits")
    }

    @Test
    fun `parseRelease captures per-track artist when it differs from album-level`() {
        // Compilation shape: title-level is "Various Artists", each track's
        // actual performer is carried at the track level.
        val body = """
            {
              "id": "TEST-compilation-release-id",
              "title": "Best of 1984",
              "date": "1984",
              "release-group": { "id": "TEST-compilation-rg-id", "title": "Best of 1984" },
              "artist-credit": [
                { "name": "Various Artists",
                  "artist": {
                    "id": "89ad4ac3-39f7-470e-963a-56509c546377",
                    "name": "Various Artists",
                    "sort-name": "Various Artists",
                    "type": "Other"
                  }
                }
              ],
              "media": [
                {
                  "position": 1,
                  "tracks": [
                    {
                      "position": 1,
                      "title": "When Doves Cry",
                      "length": 349000,
                      "recording": { "id": "recording-uuid-prince", "title": "When Doves Cry", "length": 349000 },
                      "artist-credit": [
                        { "name": "Prince",
                          "artist": {
                            "id": "prince-artist-uuid-000000000000",
                            "name": "Prince",
                            "sort-name": "Prince",
                            "type": "Person"
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = svc.parseRelease(body)
        assertTrue(result is MusicBrainzResult.Success)
        val release = result.release

        assertEquals(1, release.albumArtistCredits.size)
        assertEquals("Various Artists", release.albumArtistCredits.first().name)

        assertEquals(1, release.tracks.size)
        val track = release.tracks.first()
        assertEquals(1, track.trackArtistCredits.size,
            "compilation track must carry its per-track performer")
        assertEquals("Prince", track.trackArtistCredits.first().name)
    }

    @Test
    fun `parseRelease handles multi-disc releases`() {
        val body = """
            {
              "id": "c001-0000-0000-0000-000000000003",
              "title": "The Beatles (White Album)",
              "date": "1968",
              "release-group": { "id": "c002-0000-0000-0000-000000000004", "title": "The Beatles" },
              "artist-credit": [
                { "name": "The Beatles",
                  "artist": { "id": "beatles-uuid-0000-0000-0000-000000000005",
                              "name": "The Beatles", "sort-name": "Beatles, The", "type": "Group" }
                }
              ],
              "media": [
                {
                  "position": 1,
                  "tracks": [
                    { "position": 1, "title": "Back in the U.S.S.R.", "length": 163000,
                      "recording": { "id": "r1", "title": "Back in the U.S.S.R.", "length": 163000 } }
                  ]
                },
                {
                  "position": 2,
                  "tracks": [
                    { "position": 1, "title": "Birthday", "length": 162000,
                      "recording": { "id": "r2", "title": "Birthday", "length": 162000 } }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = svc.parseRelease(body)
        assertTrue(result is MusicBrainzResult.Success)
        val release = result.release
        assertEquals(2, release.tracks.size)
        assertEquals(1, release.tracks[0].discNumber)
        assertEquals(1, release.tracks[0].trackNumber)
        assertEquals(2, release.tracks[1].discNumber)
        assertEquals(1, release.tracks[1].trackNumber)
    }

    @Test
    fun `parseRelease accepts a partial release with no tracks`() {
        val body = """
            {
              "id": "abc-0000-0000-0000-000000000006",
              "title": "Untitled",
              "release-group": { "id": "def-0000-0000-0000-000000000007" },
              "artist-credit": []
            }
        """.trimIndent()
        val result = svc.parseRelease(body)
        assertTrue(result is MusicBrainzResult.Success)
        val release = result.release
        assertTrue(release.tracks.isEmpty())
        assertNull(release.totalDurationSeconds)
        assertTrue(release.albumArtistCredits.isEmpty())
    }

    @Test
    fun `parseMbDate handles YYYY, YYYY-MM, YYYY-MM-DD and nulls`() {
        assertEquals(java.time.LocalDate.of(1971, 1, 1), parseMbDate("1971"))
        assertEquals(java.time.LocalDate.of(1971, 4, 1), parseMbDate("1971-04"))
        assertEquals(java.time.LocalDate.of(1971, 4, 19), parseMbDate("1971-04-19"))
        assertNull(parseMbDate(null))
        assertNull(parseMbDate(""))
        assertNull(parseMbDate("garbage"))
    }
}
