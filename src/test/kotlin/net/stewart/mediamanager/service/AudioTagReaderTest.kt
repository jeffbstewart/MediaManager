package net.stewart.mediamanager.service

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioTagReaderTest {

    @Test
    fun `parses dBpoweramp-style FLAC tags including MB IDs`() {
        val json = """
            {
              "format": {
                "duration": "261.472",
                "tags": {
                  "TITLE": "The Changeling",
                  "ALBUM": "L.A. Woman",
                  "ALBUMARTIST": "The Doors",
                  "ARTIST": "The Doors",
                  "TRACKNUMBER": "1/11",
                  "DISCNUMBER": "1/1",
                  "DATE": "1971-04-19",
                  "MUSICBRAINZ_ALBUMID": "TEST-release-id",
                  "MUSICBRAINZ_RELEASEGROUPID": "TEST-rg-id",
                  "MUSICBRAINZ_TRACKID": "TEST-recording-id",
                  "MUSICBRAINZ_ARTISTID": "TEST-artist-id-1"
                }
              },
              "streams": []
            }
        """.trimIndent()
        val tags = AudioTagReader.parse(json)
        assertEquals("The Changeling", tags.title)
        assertEquals("L.A. Woman", tags.album)
        assertEquals("The Doors", tags.albumArtist)
        assertEquals("The Doors", tags.trackArtist)
        assertEquals(1, tags.trackNumber)
        assertEquals(1, tags.discNumber)
        assertEquals(1971, tags.year)
        assertEquals(261, tags.durationSeconds)
        assertEquals("TEST-release-id", tags.musicBrainzReleaseId)
        assertEquals("TEST-rg-id", tags.musicBrainzReleaseGroupId)
        assertEquals("TEST-recording-id", tags.musicBrainzRecordingId)
        assertEquals("TEST-artist-id-1", tags.musicBrainzArtistId)
    }

    @Test
    fun `MUSICBRAINZ_RELEASETRACKID is accepted when TRACKID absent`() {
        val json = """
            {
              "format": {
                "tags": {
                  "TITLE": "X",
                  "MUSICBRAINZ_RELEASETRACKID": "TEST-rel-track-id"
                }
              }
            }
        """.trimIndent()
        val tags = AudioTagReader.parse(json)
        assertEquals("TEST-rel-track-id", tags.musicBrainzRecordingId)
    }

    @Test
    fun `semicolon-separated artist ID keeps first value`() {
        val json = """
            {
              "format": {
                "tags": {
                  "MUSICBRAINZ_ARTISTID": "TEST-a1;TEST-a2;TEST-a3"
                }
              }
            }
        """.trimIndent()
        val tags = AudioTagReader.parse(json)
        assertEquals("TEST-a1", tags.musicBrainzArtistId)
    }

    @Test
    fun `stream-level tags merge when format tags are sparse`() {
        // Typical M4A shape: format.tags is empty; tags live on the first stream.
        val json = """
            {
              "format": { "duration": "198.0" },
              "streams": [
                { "tags": { "title": "Love Her Madly", "album": "L.A. Woman" } }
              ]
            }
        """.trimIndent()
        val tags = AudioTagReader.parse(json)
        assertEquals("Love Her Madly", tags.title)
        assertEquals("L.A. Woman", tags.album)
        assertEquals(198, tags.durationSeconds)
    }

    @Test
    fun `parseSlashPrefixInt handles track conventions`() {
        assertEquals(1, AudioTagReader.parseSlashPrefixInt("1/11"))
        assertEquals(7, AudioTagReader.parseSlashPrefixInt("7"))
        assertEquals(null, AudioTagReader.parseSlashPrefixInt(null))
        assertEquals(null, AudioTagReader.parseSlashPrefixInt(""))
        assertEquals(null, AudioTagReader.parseSlashPrefixInt("garbage"))
    }

    @Test
    fun `missing tags surface as nulls`() {
        val json = """{ "format": { "tags": {} }, "streams": [] }"""
        val tags = AudioTagReader.parse(json)
        assertNull(tags.title)
        assertNull(tags.album)
        assertNull(tags.musicBrainzReleaseId)
        assertNull(tags.trackNumber)
        assertNull(tags.year)
    }

    @Test
    fun `case-insensitive tag lookup finds either case`() {
        // FLAC uppercase vs ID3 / M4A lowercase — reader normalizes.
        val upper = AudioTagReader.parse("""{"format":{"tags":{"TITLE":"X"}}}""").title
        val lower = AudioTagReader.parse("""{"format":{"tags":{"title":"X"}}}""").title
        assertEquals("X", upper)
        assertEquals("X", lower)
    }
}
