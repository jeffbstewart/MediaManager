package net.stewart.mediamanager.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbServiceCreditsTest {

    private val service = TmdbService()

    @Test
    fun `parses successful credits response`() {
        val body = """
        {
            "id": 550,
            "cast": [
                {
                    "id": 819,
                    "name": "Edward Norton",
                    "character": "The Narrator",
                    "profile_path": "/8nytsqL59SFJTVYVrN72k6qkGgJ.jpg",
                    "order": 0
                },
                {
                    "id": 287,
                    "name": "Brad Pitt",
                    "character": "Tyler Durden",
                    "profile_path": "/cckcYc2v0yh1tc9QjRelptcOBko.jpg",
                    "order": 1
                },
                {
                    "id": 1283,
                    "name": "Helena Bonham Carter",
                    "character": "Marla Singer",
                    "profile_path": "/DDeITcCpnrcq2VUnhXkHjOGkPbk.jpg",
                    "order": 2
                }
            ]
        }
        """.trimIndent()

        val results = service.parseCreditsResponse(200, body)
        assertEquals(3, results.size)

        assertEquals(819, results[0].tmdbPersonId)
        assertEquals("Edward Norton", results[0].name)
        assertEquals("The Narrator", results[0].characterName)
        assertEquals("/8nytsqL59SFJTVYVrN72k6qkGgJ.jpg", results[0].profilePath)
        assertEquals(0, results[0].order)

        assertEquals(287, results[1].tmdbPersonId)
        assertEquals("Brad Pitt", results[1].name)
        assertEquals("Tyler Durden", results[1].characterName)
        assertEquals(1, results[1].order)

        assertEquals(1283, results[2].tmdbPersonId)
        assertEquals("Helena Bonham Carter", results[2].name)
        assertEquals(2, results[2].order)
    }

    @Test
    fun `empty cast array returns empty list`() {
        val body = """{"id": 550, "cast": []}"""
        val results = service.parseCreditsResponse(200, body)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `missing cast field returns empty list`() {
        val body = """{"id": 550}"""
        val results = service.parseCreditsResponse(200, body)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `non-200 status returns empty list`() {
        val results = service.parseCreditsResponse(429, "")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `401 status returns empty list`() {
        val results = service.parseCreditsResponse(401, """{"status_message":"Invalid API key"}""")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `missing required fields skips entry`() {
        val body = """
        {
            "id": 550,
            "cast": [
                {"id": 819, "name": "Edward Norton", "character": "Narrator", "order": 0},
                {"name": "No ID Actor", "character": "Someone", "order": 1},
                {"id": 287, "character": "No Name", "order": 2},
                {"id": 1283, "name": "Helena Bonham Carter", "order": 3}
            ]
        }
        """.trimIndent()

        val results = service.parseCreditsResponse(200, body)
        assertEquals(2, results.size)
        assertEquals("Edward Norton", results[0].name)
        assertEquals("Helena Bonham Carter", results[1].name)
    }

    @Test
    fun `null optional fields handled gracefully`() {
        val body = """
        {
            "id": 550,
            "cast": [{
                "id": 819,
                "name": "Edward Norton",
                "character": null,
                "profile_path": null,
                "order": 0
            }]
        }
        """.trimIndent()

        val results = service.parseCreditsResponse(200, body)
        assertEquals(1, results.size)
        assertNull(results[0].characterName)
        assertNull(results[0].profilePath)
    }

    @Test
    fun `limits to top 20 cast members`() {
        val castEntries = (1..30).joinToString(",") { i ->
            """{"id": $i, "name": "Actor $i", "character": "Role $i", "order": ${i - 1}}"""
        }
        val body = """{"id": 550, "cast": [$castEntries]}"""

        val results = service.parseCreditsResponse(200, body)
        assertEquals(20, results.size)
        assertEquals(1, results.first().tmdbPersonId)
        assertEquals(20, results.last().tmdbPersonId)
    }

    @Test
    fun `malformed json returns empty list`() {
        val results = service.parseCreditsResponse(200, "not json at all")
        assertTrue(results.isEmpty())
    }
}
