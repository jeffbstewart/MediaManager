package net.stewart.mediamanager.service

import com.gitlab.mvysny.jdbiorm.JdbiOrm
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.stewart.mediamanager.entity.AppConfig
import net.stewart.mediamanager.entity.AppUser
import net.stewart.mediamanager.entity.CastMember
import net.stewart.mediamanager.entity.EnrichmentStatus
import net.stewart.mediamanager.entity.Episode
import net.stewart.mediamanager.entity.Genre
import net.stewart.mediamanager.entity.MediaFormat
import net.stewart.mediamanager.entity.MediaType
import net.stewart.mediamanager.entity.PlaybackProgress
import net.stewart.mediamanager.entity.Tag
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.TitleGenre
import net.stewart.mediamanager.entity.TitleTag
import net.stewart.mediamanager.entity.TmdbCollection
import net.stewart.mediamanager.entity.TmdbCollectionPart
import net.stewart.mediamanager.entity.Transcode
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.entity.WishStatus
import net.stewart.mediamanager.entity.WishType
import org.flywaydb.core.Flyway
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shared scaffolding for the three Roku service tests. Each subclass
 * gets its own DB schema; the harness handles real-file seeding for
 * the playability check (RokuSearchService.isPlayable hits java.io.File
 * directly for MP4/M4V — easier to drop real bytes on disk than to
 * inject a filesystem there).
 */
internal abstract class RokuServiceTestBase {

    companion object {
        private val DB_COUNTER = AtomicInteger(0)

        @JvmStatic
        protected fun setupSchema(prefix: String): HikariDataSource {
            val ds = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:${prefix}-${DB_COUNTER.incrementAndGet()};DB_CLOSE_DELAY=-1"
                username = "sa"; password = ""
            })
            JdbiOrm.setDataSource(ds)
            Flyway.configure().dataSource(ds).load().migrate()
            return ds
        }

        @JvmStatic
        protected fun teardownSchema(ds: HikariDataSource) {
            JdbiOrm.destroy()
            ds.close()
        }
    }

    /** Real on-disk temp dir backing this test's NAS layout. */
    protected lateinit var nasRoot: File

    /** Per-test cleanup of every entity these services touch. */
    protected fun cleanTables() {
        WishListItem.deleteAll()
        PlaybackProgress.deleteAll()
        TmdbCollectionPart.deleteAll()
        TmdbCollection.deleteAll()
        TitleTag.deleteAll()
        TitleGenre.deleteAll()
        CastMember.deleteAll()
        Tag.deleteAll()
        Genre.deleteAll()
        Transcode.deleteAll()
        Episode.deleteAll()
        Title.deleteAll()
        AppUser.deleteAll()
        AppConfig.deleteAll()
        SearchIndexService.clear()
    }

    /** Seed nas_root_path and create the temp dir. */
    protected fun configureNasRoot() {
        nasRoot = Files.createTempDirectory("roku-test-").toFile().apply {
            deleteOnExit()
        }
        AppConfig(config_key = "nas_root_path",
            config_val = nasRoot.absolutePath).save()
    }

    /** Drop a zero-byte file on disk so File(path).exists() succeeds. */
    protected fun seedFile(rel: String): File {
        val target = File(nasRoot, rel)
        target.parentFile.mkdirs()
        target.writeBytes(ByteArray(0))
        return target
    }

    protected fun seedUser(username: String = "viewer", level: Int = 1): AppUser {
        val now = LocalDateTime.now()
        return AppUser(
            username = username, display_name = username,
            password_hash = "x", access_level = level,
            created_at = now, updated_at = now,
        ).apply { save() }
    }

    /** Seed a movie Title that's enriched, not hidden, and has a poster path. */
    protected fun seedMovie(
        name: String,
        year: Int? = 2020,
        rating: String? = "PG-13",
        popularity: Double? = 50.0,
        tmdbId: Int? = null,
        tmdbCollectionId: Int? = null,
    ): Title = Title(
        name = name,
        media_type = MediaType.MOVIE.name,
        sort_name = name.lowercase(),
        enrichment_status = EnrichmentStatus.ENRICHED.name,
        release_year = year,
        content_rating = rating,
        popularity = popularity,
        tmdb_id = tmdbId,
        tmdb_collection_id = tmdbCollectionId,
        poster_path = "/p.jpg",
        backdrop_path = "/b.jpg",
        updated_at = LocalDateTime.now(),
    ).apply { save() }

    protected fun seedTv(
        name: String,
        year: Int? = 2020,
        rating: String? = "TV-14",
        popularity: Double? = 50.0,
    ): Title = Title(
        name = name,
        media_type = MediaType.TV.name,
        sort_name = name.lowercase(),
        enrichment_status = EnrichmentStatus.ENRICHED.name,
        release_year = year,
        content_rating = rating,
        popularity = popularity,
        poster_path = "/p.jpg",
        updated_at = LocalDateTime.now(),
    ).apply { save() }

    /** Seed a transcode + the on-disk file it points at, returning the row. */
    protected fun seedTranscode(
        title: Title,
        relPath: String,
        episodeId: Long? = null,
        format: MediaFormat = MediaFormat.BLURAY,
    ): Transcode {
        val file = seedFile(relPath)
        return Transcode(
            title_id = title.id!!,
            episode_id = episodeId,
            file_path = file.absolutePath,
            media_format = format.name,
        ).apply { save() }
    }
}

// =============================================================================
// RokuSearchService
// =============================================================================

internal class RokuSearchServiceTest : RokuServiceTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("rokusearch") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    @Before
    fun reset() {
        cleanTables()
        configureNasRoot()
    }

    private val baseUrl = "https://example.test"
    private val apiKey = "key-${java.util.UUID.randomUUID()}"

    // ---------------------- search ----------------------

    @Test
    fun `search returns the empty shape for a blank query`() {
        val resp = RokuSearchService.search("",
            baseUrl, apiKey, seedUser())
        assertEquals(0, resp.results.size)
        assertTrue(resp.counts.isEmpty())
    }

    @Test
    fun `search finds a movie by name and returns the playable transcode`() {
        val user = seedUser()
        val movie = seedMovie("The Matrix", year = 1999)
        seedTranscode(movie, "Movies/The Matrix.mp4")
        SearchIndexService.rebuild()

        val resp = RokuSearchService.search("matrix",
            baseUrl, apiKey, user)
        assertEquals(1, resp.results.size)
        val hit = resp.results.single()
        assertEquals("movie", hit.resultType)
        assertEquals("The Matrix", hit.name)
        assertEquals(1999, hit.year)
        assertEquals(movie.id, hit.titleId)
        assertNotNull(hit.transcodeId)
        // baseUrl/apiKey threaded into URL fields
        assertNotNull(hit.posterUrl)
        assertTrue(hit.posterUrl!!.startsWith(baseUrl))
        assertTrue(apiKey in hit.posterUrl!!)
    }

    @Test
    fun `search drops titles whose only transcode points at a missing file`() {
        val user = seedUser()
        val movie = seedMovie("Ghost")
        // file_path points at a path that doesn't exist on disk.
        Transcode(
            title_id = movie.id!!,
            file_path = File(nasRoot, "Movies/Missing.mp4").absolutePath,
            media_format = MediaFormat.BLURAY.name,
        ).save()
        SearchIndexService.rebuild()

        val resp = RokuSearchService.search("ghost",
            baseUrl, apiKey, user)
        // Title isn't playable → search drops it.
        assertEquals(0, resp.results.size)
    }

    @Test
    fun `search drops titles with a content rating above the user's ceiling`() {
        val viewer = seedUser().apply {
            rating_ceiling = 4  // PG-13 ceiling
            save()
        }
        val movie = seedMovie("Blood Movie", rating = "R")
        seedTranscode(movie, "Movies/blood.mp4")
        SearchIndexService.rebuild()

        val resp = RokuSearchService.search("blood",
            baseUrl, apiKey, viewer)
        assertEquals(0, resp.results.size)
    }

    @Test
    fun `search includes a tag hit when query matches the tag name`() {
        val user = seedUser()
        val movie = seedMovie("Some Movie")
        seedTranscode(movie, "Movies/some.mp4")
        val tag = Tag(name = "Mind-Bending", bg_color = "#000",
            source_type = "MANUAL").apply { save() }
        TitleTag(title_id = movie.id!!, tag_id = tag.id!!).save()
        SearchIndexService.rebuild()

        val resp = RokuSearchService.search("mind",
            baseUrl, apiKey, user)
        val tagHit = resp.results.singleOrNull { it.resultType == "tag" }
        assertNotNull(tagHit)
        assertEquals("Mind-Bending", tagHit.name)
        assertEquals(1, tagHit.titleCount)
    }

    @Test
    fun `search includes a genre hit when query matches the genre name`() {
        val user = seedUser()
        val movie = seedMovie("Some Movie")
        seedTranscode(movie, "Movies/some.mp4")
        val genre = Genre(name = "Sci-Fi").apply { save() }
        TitleGenre(title_id = movie.id!!, genre_id = genre.id!!).save()
        SearchIndexService.rebuild()

        val resp = RokuSearchService.search("sci",
            baseUrl, apiKey, user)
        val genreHit = resp.results.singleOrNull { it.resultType == "genre" }
        assertNotNull(genreHit)
        assertEquals("Sci-Fi", genreHit.name)
    }

    @Test
    fun `search includes a collection hit when query matches the collection name`() {
        val user = seedUser()
        val movie = seedMovie("Saga Pt 1", tmdbCollectionId = 100)
        seedTranscode(movie, "Movies/saga.mp4")
        TmdbCollection(tmdb_collection_id = 100, name = "Epic Saga",
            poster_path = "/c.jpg").save()
        SearchIndexService.rebuild()

        val resp = RokuSearchService.search("epic saga",
            baseUrl, apiKey, user)
        val collHit = resp.results.singleOrNull { it.resultType == "collection" }
        assertNotNull(collHit)
        assertEquals("Epic Saga", collHit.name)
        assertEquals(100, collHit.tmdbCollectionId)
    }

    @Test
    fun `search includes an actor hit when query matches a CastMember name`() {
        val user = seedUser()
        val movie = seedMovie("Star Vehicle")
        seedTranscode(movie, "Movies/star.mp4")
        CastMember(
            title_id = movie.id!!,
            tmdb_person_id = 42,
            name = "Famous Actor",
            character_name = "Hero",
            cast_order = 0,
            popularity = 99.0,
            profile_path = "/h.jpg",
        ).save()
        SearchIndexService.rebuild()

        val resp = RokuSearchService.search("famous",
            baseUrl, apiKey, user)
        val actorHit = resp.results.singleOrNull { it.resultType == "actor" }
        assertNotNull(actorHit)
        assertEquals("Famous Actor", actorHit.name)
        assertEquals(42, actorHit.tmdbPersonId)
        assertEquals(1, actorHit.titleCount)
    }

    // ---------------------- getCollectionDetail ----------------------

    @Test
    fun `getCollectionDetail returns null for an unknown collection`() {
        val resp = RokuSearchService.getCollectionDetail(9999,
            baseUrl, apiKey, seedUser())
        assertNull(resp)
    }

    @Test
    fun `getCollectionDetail returns parts with owned-vs-unowned distinction`() {
        val user = seedUser()
        val owned = seedMovie("Pt 1", tmdbId = 1, tmdbCollectionId = 50)
        seedTranscode(owned, "Movies/pt1.mp4")
        val coll = TmdbCollection(tmdb_collection_id = 50, name = "Trilogy",
            poster_path = "/c.jpg").apply { save() }
        TmdbCollectionPart(collection_id = coll.id!!, tmdb_movie_id = 1,
            title = "Pt 1", position = 0).save()
        TmdbCollectionPart(collection_id = coll.id!!, tmdb_movie_id = 2,
            title = "Pt 2", position = 1,
            poster_path = "/p2.jpg").save()

        val detail = RokuSearchService.getCollectionDetail(50,
            baseUrl, apiKey, user)
        assertNotNull(detail)
        assertEquals("Trilogy", detail.name)
        assertEquals(2, detail.items.size)
        assertEquals(true, detail.items[0].owned)
        assertEquals(false, detail.items[1].owned)
    }

    // ---------------------- getTagDetail / getGenreDetail ----------------------

    @Test
    fun `getTagDetail returns null for an unknown tag`() {
        assertNull(RokuSearchService.getTagDetail(9999,
            baseUrl, apiKey, seedUser()))
    }

    @Test
    fun `getTagDetail returns the playable titles for a tag`() {
        val user = seedUser()
        val movie = seedMovie("Tagged Movie")
        seedTranscode(movie, "Movies/tagged.mp4")
        val tag = Tag(name = "FavGenre", bg_color = "#abc",
            source_type = "MANUAL").apply { save() }
        TitleTag(title_id = movie.id!!, tag_id = tag.id!!).save()

        val detail = RokuSearchService.getTagDetail(tag.id!!,
            baseUrl, apiKey, user)
        assertNotNull(detail)
        assertEquals("FavGenre", detail.name)
        assertEquals(1, detail.items.size)
    }

    @Test
    fun `getGenreDetail returns null for an unknown genre`() {
        assertNull(RokuSearchService.getGenreDetail(9999,
            baseUrl, apiKey, seedUser()))
    }

    @Test
    fun `getGenreDetail returns playable titles for the genre`() {
        val user = seedUser()
        val movie = seedMovie("Action Pic")
        seedTranscode(movie, "Movies/action.mp4")
        val genre = Genre(name = "Action").apply { save() }
        TitleGenre(title_id = movie.id!!, genre_id = genre.id!!).save()

        val detail = RokuSearchService.getGenreDetail(genre.id!!,
            baseUrl, apiKey, user)
        assertNotNull(detail)
        assertEquals("Action", detail.name)
        assertEquals(1, detail.items.size)
    }

    // ---------------------- getActorDetail ----------------------

    @Test
    fun `getActorDetail returns null when no CastMember has the tmdb_person_id`() {
        assertNull(RokuSearchService.getActorDetail(9999,
            baseUrl, apiKey, seedUser()))
    }

    @Test
    fun `getActorDetail returns the actor's playable titles`() {
        val user = seedUser()
        val movie = seedMovie("First Movie", tmdbId = 100)
        seedTranscode(movie, "Movies/m1.mp4")
        val movie2 = seedMovie("Second Movie", tmdbId = 101, popularity = 80.0)
        seedTranscode(movie2, "Movies/m2.mp4")
        CastMember(
            title_id = movie.id!!, tmdb_person_id = 7,
            name = "Star", character_name = "Hero",
            cast_order = 0, popularity = 90.0,
        ).save()
        CastMember(
            title_id = movie2.id!!, tmdb_person_id = 7,
            name = "Star", character_name = "Villain",
            cast_order = 0, popularity = 90.0,
        ).save()

        val detail = RokuSearchService.getActorDetail(7,
            baseUrl, apiKey, user)
        assertNotNull(detail)
        assertEquals("Star", detail.name)
        assertEquals(2, detail.items.size)
    }

    // ---------------------- addWish ----------------------

    @Test
    fun `addWish creates a new active wish`() {
        val user = seedUser()
        val res = RokuSearchService.addWish(
            tmdbId = 12345, mediaType = MediaType.MOVIE.name,
            title = "Anticipated", posterPath = "/a.jpg",
            releaseYear = 2025, user = user,
        )
        assertEquals(true, res.success)
        val saved = WishListItem.findAll().single()
        assertEquals(12345, saved.tmdb_id)
        assertEquals("Anticipated", saved.tmdb_title)
        assertEquals(WishStatus.ACTIVE.name, saved.status)
    }

    @Test
    fun `addWish refuses a duplicate active wish for the same TMDB id`() {
        val user = seedUser()
        WishListItem(
            user_id = user.id!!,
            wish_type = WishType.MEDIA.name,
            status = WishStatus.ACTIVE.name,
            tmdb_id = 12345,
            tmdb_media_type = MediaType.MOVIE.name,
        ).save()

        val res = RokuSearchService.addWish(
            tmdbId = 12345, mediaType = MediaType.MOVIE.name,
            title = "Dup", posterPath = null, releaseYear = null,
            user = user,
        )
        assertEquals(false, res.success)
        assertEquals("already_wished", res.reason)
        assertEquals(1, WishListItem.findAll().size)
    }
}

// =============================================================================
// RokuTitleService
// =============================================================================

internal class RokuTitleServiceTest : RokuServiceTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("rokutitle") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    @Before
    fun reset() {
        cleanTables()
        configureNasRoot()
    }

    private val baseUrl = "https://example.test"
    private val apiKey = "key-${java.util.UUID.randomUUID()}"

    @Test
    fun `getTitleDetail returns null when title is hidden`() {
        val user = seedUser()
        val movie = seedMovie("Hidden").apply {
            hidden = true
            save()
        }
        seedTranscode(movie, "Movies/hidden.mp4")
        assertNull(RokuTitleService.getTitleDetail(movie.id!!,
            baseUrl, apiKey, user))
    }

    @Test
    fun `getTitleDetail returns null when title is not enriched`() {
        val user = seedUser()
        val movie = seedMovie("Not Enriched").apply {
            enrichment_status = EnrichmentStatus.PENDING.name
            save()
        }
        seedTranscode(movie, "Movies/ne.mp4")
        assertNull(RokuTitleService.getTitleDetail(movie.id!!,
            baseUrl, apiKey, user))
    }

    @Test
    fun `getTitleDetail returns null when content rating exceeds user ceiling`() {
        val viewer = seedUser().apply {
            rating_ceiling = 4
            save()
        }
        val movie = seedMovie("Hard R", rating = "R")
        seedTranscode(movie, "Movies/hr.mp4")
        assertNull(RokuTitleService.getTitleDetail(movie.id!!,
            baseUrl, apiKey, viewer))
    }

    @Test
    fun `getTitleDetail returns null for a movie with no playable transcodes`() {
        val user = seedUser()
        val movie = seedMovie("No Transcodes")
        // No Transcode rows seeded.
        assertNull(RokuTitleService.getTitleDetail(movie.id!!,
            baseUrl, apiKey, user))
    }

    @Test
    fun `getTitleDetail returns full movie payload on the happy path`() {
        val user = seedUser()
        val movie = seedMovie("Inception", year = 2010,
            popularity = 80.0, tmdbId = 27205)
        val tc = seedTranscode(movie, "Movies/Inception.mp4",
            format = MediaFormat.BLURAY)

        val detail = RokuTitleService.getTitleDetail(movie.id!!,
            baseUrl, apiKey, user)
        assertNotNull(detail)
        assertEquals("Inception", detail.name)
        assertEquals(MediaType.MOVIE.name, detail.mediaType)
        assertEquals(2010, detail.year)
        assertEquals("FHD", detail.quality)
        assertEquals(tc.id, detail.transcodeId)
        assertNotNull(detail.streamUrl)
        assertTrue(detail.streamUrl!!.contains(apiKey))
        // Movies have no seasons.
        assertNull(detail.seasons)
    }

    @Test
    fun `getTitleDetail returns TV detail with seasons grouped`() {
        val user = seedUser()
        val show = seedTv("Breaking Bad", year = 2008)
        val ep1 = Episode(title_id = show.id!!,
            season_number = 1, episode_number = 1,
            name = "Pilot").apply { save() }
        val ep2 = Episode(title_id = show.id!!,
            season_number = 1, episode_number = 2,
            name = "Cat in the Bag").apply { save() }
        val ep3 = Episode(title_id = show.id!!,
            season_number = 2, episode_number = 1,
            name = "Seven Thirty-Seven").apply { save() }
        seedTranscode(show, "Shows/BB/S01E01.mp4", episodeId = ep1.id)
        seedTranscode(show, "Shows/BB/S01E02.mp4", episodeId = ep2.id)
        seedTranscode(show, "Shows/BB/S02E01.mp4", episodeId = ep3.id)

        val detail = RokuTitleService.getTitleDetail(show.id!!,
            baseUrl, apiKey, user)
        assertNotNull(detail)
        assertEquals(MediaType.TV.name, detail.mediaType)
        // Two seasons with two + one episodes respectively.
        val seasons = detail.seasons
        assertNotNull(seasons)
        assertEquals(2, seasons.size)
        assertEquals(1, seasons[0].seasonNumber)
        assertEquals(2, seasons[0].episodes.size)
        assertEquals(2, seasons[1].seasonNumber)
        assertEquals(1, seasons[1].episodes.size)
    }

    @Test
    fun `getTitleDetail surfaces tags in the response`() {
        val user = seedUser()
        val movie = seedMovie("Tagged")
        seedTranscode(movie, "Movies/t.mp4")
        val tag = Tag(name = "Mind-Bending", bg_color = "#000",
            source_type = "MANUAL").apply { save() }
        TitleTag(title_id = movie.id!!, tag_id = tag.id!!).save()

        val detail = RokuTitleService.getTitleDetail(movie.id!!,
            baseUrl, apiKey, user)
        assertNotNull(detail)
        assertEquals(1, detail.tags.size)
        assertEquals("Mind-Bending", detail.tags.single().name)
    }

    @Test
    fun `getTitleDetail surfaces cast (top 10)`() {
        val user = seedUser()
        val movie = seedMovie("Big Cast")
        seedTranscode(movie, "Movies/bc.mp4")
        // Seed 12 cast members; cap is 10.
        for (i in 0 until 12) {
            CastMember(
                title_id = movie.id!!,
                tmdb_person_id = 1000 + i,
                name = "Actor $i",
                character_name = "Char $i",
                cast_order = i,
                popularity = (100 - i).toDouble(),
            ).save()
        }
        val detail = RokuTitleService.getTitleDetail(movie.id!!,
            baseUrl, apiKey, user)
        assertNotNull(detail)
        assertEquals(10, detail.cast.size,
            "buildCast should cap at top-10 by cast_order")
    }

    @Test
    fun `getTitleDetail surfaces resume position when progress exists`() {
        val user = seedUser()
        val movie = seedMovie("Resume Me")
        val tc = seedTranscode(movie, "Movies/resume.mp4")
        PlaybackProgress(
            user_id = user.id!!,
            transcode_id = tc.id!!,
            position_seconds = 600.0,
            duration_seconds = 7200.0,
            updated_at = LocalDateTime.now(),
        ).save()

        val detail = RokuTitleService.getTitleDetail(movie.id!!,
            baseUrl, apiKey, user)
        assertNotNull(detail)
        assertEquals(600, detail.resumePosition)
        // 600/7200 = 8% rounded down.
        assertEquals(8, detail.watchedPercent)
    }
}

// =============================================================================
// RokuFeedService
// =============================================================================

internal class RokuFeedServiceTest : RokuServiceTestBase() {

    companion object {
        private lateinit var ds: HikariDataSource

        @BeforeClass @JvmStatic
        fun setupDb() { ds = setupSchema("rokufeed") }

        @AfterClass @JvmStatic
        fun teardownDb() { teardownSchema(ds) }
    }

    @Before
    fun reset() {
        cleanTables()
        configureNasRoot()
    }

    private val baseUrl = "https://example.test"
    private val apiKey = "key-${java.util.UUID.randomUUID()}"

    @Test
    fun `generateFeed returns the empty-shape on an empty catalog`() {
        val feed = RokuFeedService.generateFeed(baseUrl, apiKey)
        assertTrue(feed.contains("\"providerName\""),
            "feed should carry Roku Direct Publisher provider envelope")
        assertTrue("\"movies\"" in feed)
        assertTrue("\"series\"" in feed)
    }

    @Test
    fun `generateFeed includes a playable movie`() {
        val movie = seedMovie("The Movie", year = 2020)
        seedTranscode(movie, "Movies/m.mp4")

        val feed = RokuFeedService.generateFeed(baseUrl, apiKey)
        assertTrue("The Movie" in feed)
        assertTrue("2020" in feed)
        // Stream URL embeds the key + transcode id.
        assertTrue(apiKey in feed)
    }

    @Test
    fun `generateFeed includes a TV series with episodes grouped under seasons`() {
        val show = seedTv("BB", year = 2008)
        val ep = Episode(title_id = show.id!!,
            season_number = 1, episode_number = 1,
            name = "Pilot").apply { save() }
        seedTranscode(show, "Shows/bb-s01e01.mp4", episodeId = ep.id)

        val feed = RokuFeedService.generateFeed(baseUrl, apiKey)
        assertTrue("\"series\"" in feed)
        assertTrue("BB" in feed)
        assertTrue("Pilot" in feed)
    }

    @Test
    fun `generateFeed drops hidden titles`() {
        val movie = seedMovie("Visible Movie")
        seedTranscode(movie, "Movies/visible.mp4")
        val hidden = seedMovie("Hidden Movie").apply {
            hidden = true
            save()
        }
        seedTranscode(hidden, "Movies/hidden.mp4")

        val feed = RokuFeedService.generateFeed(baseUrl, apiKey)
        assertTrue("Visible Movie" in feed)
        assertFalse("Hidden Movie" in feed)
    }

    @Test
    fun `generateFeed drops titles with no playable transcode (file missing)`() {
        val ghost = seedMovie("Ghost Movie")
        // Transcode points at a path we never write a file to.
        Transcode(
            title_id = ghost.id!!,
            file_path = File(nasRoot, "Movies/ghost.mp4").absolutePath,
            media_format = MediaFormat.BLURAY.name,
        ).save()

        val feed = RokuFeedService.generateFeed(baseUrl, apiKey)
        assertFalse("Ghost Movie" in feed,
            "title with non-existent file is unplayable and excluded")
    }

    @Test
    fun `generateFeed honors the user's content-rating ceiling when one is supplied`() {
        val viewer = seedUser().apply {
            rating_ceiling = 4  // PG-13 ceiling
            save()
        }
        val pg13 = seedMovie("PG-13 Movie", rating = "PG-13")
        seedTranscode(pg13, "Movies/pg13.mp4")
        val rRated = seedMovie("R-Rated Movie", rating = "R")
        seedTranscode(rRated, "Movies/r.mp4")

        val feed = RokuFeedService.generateFeed(baseUrl, apiKey, viewer)
        assertTrue("PG-13 Movie" in feed)
        assertFalse("R-Rated Movie" in feed)
    }

    @Test
    fun `generateFeed embeds genre names from the title-genre links`() {
        val movie = seedMovie("Sci-Fi Pic")
        seedTranscode(movie, "Movies/sf.mp4")
        val genre = Genre(name = "Science Fiction").apply { save() }
        TitleGenre(title_id = movie.id!!, genre_id = genre.id!!).save()

        val feed = RokuFeedService.generateFeed(baseUrl, apiKey)
        assertTrue("Science Fiction" in feed)
    }
}

private fun assertFalse(condition: Boolean, message: String? = null) =
    kotlin.test.assertFalse(condition, message)
