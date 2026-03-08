package net.stewart.mediamanager.service

import com.github.vokorm.findAll
import net.stewart.mediamanager.entity.*
import org.slf4j.LoggerFactory
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * In-memory inverted index for fast title search. Supports single terms, quoted phrases,
 * negation (`-term`), and tag filters (`tag:name`).
 *
 * Thread-safe via [ReentrantReadWriteLock] (concurrent reads, exclusive writes).
 *
 * Data scale: ~500–5,000 titles fits comfortably in memory (~3–5 MB).
 */
object SearchIndexService {
    private val log = LoggerFactory.getLogger(SearchIndexService::class.java)

    /** Sentinel character inserted between indexed fields to prevent cross-field phrase matches. */
    private const val FIELD_SENTINEL = "\u0000"

    private val lock = ReentrantReadWriteLock()

    /** Inverted index: normalized token -> title IDs that contain it. */
    private val tokenIndex = HashMap<String, MutableSet<Long>>()

    /** Per-title ordered token list for phrase matching (includes sentinels between fields). */
    private val titleTokens = HashMap<Long, List<String>>()

    /** Tag name (lowercase) -> title IDs with that tag. */
    private val tagIndex = HashMap<String, MutableSet<Long>>()

    /** Genre name (lowercase) -> title IDs with that genre. */
    private val genreIndex = HashMap<String, MutableSet<Long>>()

    /** All indexed title IDs — used as the universe set for exclusion-only queries. */
    private val allTitleIds = HashSet<Long>()

    /**
     * Tokenizes text for indexing or searching: lowercase, strip non-alphanumeric characters
     * (except spaces), collapse whitespace, split on whitespace.
     *
     * Keeps articles ("the", "a", "an") unlike TranscodeMatcherService.normalize() —
     * needed for phrase matching `"the dark knight"`.
     */
    fun tokenize(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(" ")
            .filter { it.isNotEmpty() }
    }

    /**
     * Full rebuild of the index from the database.
     * Called once at startup after all migrations and data cleanup.
     */
    fun rebuild() {
        val titles = Title.findAll()
        val tags = Tag.findAll().associateBy { it.id!! }
        val titleTags = TitleTag.findAll()
        val genres = Genre.findAll().associateBy { it.id!! }
        val titleGenres = TitleGenre.findAll()

        // Build media item product_name lookup: title_id -> list of product names
        val mediaItemsById = MediaItem.findAll().associateBy { it.id!! }
        val productNamesByTitle = HashMap<Long, List<String>>()
        for (mit in MediaItemTitle.findAll()) {
            val productName = mediaItemsById[mit.media_item_id]?.product_name ?: continue
            productNamesByTitle.getOrPut(mit.title_id) { mutableListOf() }
                .let { (it as MutableList).add(productName) }
        }

        // Build cast character_name lookup: title_id -> list of character names
        val characterNamesByTitle = HashMap<Long, List<String>>()
        for (cm in CastMember.findAll()) {
            val charName = cm.character_name ?: continue
            characterNamesByTitle.getOrPut(cm.title_id) { mutableListOf() }
                .let { (it as MutableList).add(charName) }
        }

        lock.write {
            tokenIndex.clear()
            titleTokens.clear()
            tagIndex.clear()
            genreIndex.clear()
            allTitleIds.clear()

            for (title in titles) {
                val id = title.id ?: continue
                indexTitleInternal(
                    id, title.name, title.sort_name, title.raw_upc_title, title.description,
                    productNamesByTitle[id] ?: emptyList(),
                    characterNamesByTitle[id] ?: emptyList()
                )
            }

            // Build tag index
            for (tt in titleTags) {
                val tag = tags[tt.tag_id] ?: continue
                val tagName = tag.name.lowercase()
                tagIndex.getOrPut(tagName) { HashSet() }.add(tt.title_id)
            }

            // Build genre index
            for (tg in titleGenres) {
                val genre = genres[tg.genre_id] ?: continue
                val genreName = genre.name.lowercase()
                genreIndex.getOrPut(genreName) { HashSet() }.add(tg.title_id)
            }
        }

        log.info("Search index rebuilt: {} titles, {} unique tokens, {} tags, {} genres",
            allTitleIds.size, tokenIndex.size, tagIndex.size, genreIndex.size)
    }

    /**
     * Searches the index. Returns matching title IDs, or `null` if the query is empty
     * (meaning "no text filter applied").
     */
    fun search(query: String): Set<Long>? {
        val parsed = SearchQueryParser.parse(query)
        if (parsed.isEmpty) return null

        return lock.read {
            var result: MutableSet<Long>? = null

            // Required terms: intersect posting lists (AND)
            for (term in parsed.requiredTerms) {
                val postings = tokenIndex[term] ?: return@read emptySet()
                result = if (result == null) HashSet(postings) else {
                    result.retainAll(postings)
                    result
                }
            }

            // Phrases: tokenize each phrase, find candidates from first token, verify contiguous
            for (phrase in parsed.phrases) {
                val phraseTokens = tokenize(phrase)
                if (phraseTokens.isEmpty()) continue

                val candidates = tokenIndex[phraseTokens[0]] ?: return@read emptySet()
                val phraseMatches = candidates.filter { titleId ->
                    containsPhrase(titleId, phraseTokens)
                }.toSet()

                result = if (result == null) HashSet(phraseMatches) else {
                    result.retainAll(phraseMatches)
                    result
                }
            }

            // Tag filters: intersect with tag index (also check genre index)
            for (tagName in parsed.tagFilters) {
                val tagMatches = HashSet<Long>()
                tagIndex[tagName]?.let { tagMatches.addAll(it) }
                genreIndex[tagName]?.let { tagMatches.addAll(it) }
                if (tagMatches.isEmpty()) return@read emptySet()

                result = if (result == null) tagMatches else {
                    result.retainAll(tagMatches)
                    result
                }
            }

            // If only exclusions (no positive terms), start with all indexed title IDs
            if (result == null && parsed.isExclusionOnly) {
                result = HashSet(allTitleIds)
            }

            // Excluded terms: remove from result set
            if (result != null) {
                for (term in parsed.excludedTerms) {
                    val postings = tokenIndex[term] ?: continue
                    result.removeAll(postings)
                }
            }

            result ?: emptySet()
        }
    }

    /** Re-indexes a single title after it was created or updated. */
    fun onTitleChanged(titleId: Long) {
        val title = Title.findById(titleId)
        if (title == null) {
            onTitleDeleted(titleId)
            return
        }

        val productNames = MediaItemTitle.findAll()
            .filter { it.title_id == titleId }
            .mapNotNull { mit -> MediaItem.findById(mit.media_item_id)?.product_name }

        val characterNames = CastMember.findAll()
            .filter { it.title_id == titleId }
            .mapNotNull { it.character_name }

        lock.write {
            removeTitleInternal(titleId)
            indexTitleInternal(
                titleId, title.name, title.sort_name, title.raw_upc_title, title.description,
                productNames, characterNames
            )
        }
    }

    /** Removes a title from the index. */
    fun onTitleDeleted(titleId: Long) {
        lock.write {
            removeTitleInternal(titleId)
        }
    }

    /** Rebuilds the tag and genre indexes from DB. Called after tag mutations. */
    fun onTagChanged() {
        val tags = Tag.findAll().associateBy { it.id!! }
        val titleTags = TitleTag.findAll()
        val genres = Genre.findAll().associateBy { it.id!! }
        val titleGenres = TitleGenre.findAll()

        lock.write {
            tagIndex.clear()
            genreIndex.clear()

            for (tt in titleTags) {
                val tag = tags[tt.tag_id] ?: continue
                val tagName = tag.name.lowercase()
                tagIndex.getOrPut(tagName) { HashSet() }.add(tt.title_id)
            }

            for (tg in titleGenres) {
                val genre = genres[tg.genre_id] ?: continue
                val genreName = genre.name.lowercase()
                genreIndex.getOrPut(genreName) { HashSet() }.add(tg.title_id)
            }
        }
    }

    /** Clears the entire index. */
    internal fun clear() {
        lock.write {
            tokenIndex.clear()
            titleTokens.clear()
            tagIndex.clear()
            genreIndex.clear()
            allTitleIds.clear()
        }
    }

    /** Indexes a title directly (no DB lookup). For testing only. */
    internal fun indexTitleForTest(
        titleId: Long,
        name: String,
        sortName: String? = null,
        rawUpcTitle: String? = null,
        description: String? = null,
        productNames: List<String> = emptyList(),
        characterNames: List<String> = emptyList()
    ) {
        lock.write {
            indexTitleInternal(titleId, name, sortName, rawUpcTitle, description, productNames, characterNames)
        }
    }

    /** Adds a tag association directly. For testing only. */
    internal fun addTagForTest(tagName: String, titleId: Long) {
        lock.write {
            tagIndex.getOrPut(tagName.lowercase()) { HashSet() }.add(titleId)
        }
    }

    /** Adds a genre association directly. For testing only. */
    internal fun addGenreForTest(genreName: String, titleId: Long) {
        lock.write {
            genreIndex.getOrPut(genreName.lowercase()) { HashSet() }.add(titleId)
        }
    }

    // --- Private helpers ---

    private fun indexTitleInternal(
        titleId: Long, name: String, sortName: String?,
        rawUpcTitle: String?, description: String?,
        productNames: List<String> = emptyList(),
        characterNames: List<String> = emptyList()
    ) {
        allTitleIds.add(titleId)

        val allTokens = mutableListOf<String>()

        // Index each field with sentinels between them
        val fields = listOfNotNull(name, sortName, rawUpcTitle, description) +
            productNames + characterNames
        for ((idx, field) in fields.withIndex()) {
            if (idx > 0) allTokens.add(FIELD_SENTINEL)
            val fieldTokens = tokenize(field)
            allTokens.addAll(fieldTokens)
            for (token in fieldTokens) {
                tokenIndex.getOrPut(token) { HashSet() }.add(titleId)
            }
        }

        titleTokens[titleId] = allTokens
    }

    private fun removeTitleInternal(titleId: Long) {
        allTitleIds.remove(titleId)

        val tokens = titleTokens.remove(titleId) ?: return
        for (token in tokens) {
            if (token == FIELD_SENTINEL) continue
            val postings = tokenIndex[token] ?: continue
            postings.remove(titleId)
            if (postings.isEmpty()) {
                tokenIndex.remove(token)
            }
        }
    }

    /**
     * Checks if [titleId]'s token list contains [phraseTokens] as a contiguous subsequence,
     * not spanning across field sentinels.
     */
    private fun containsPhrase(titleId: Long, phraseTokens: List<String>): Boolean {
        val tokens = titleTokens[titleId] ?: return false
        if (phraseTokens.size > tokens.size) return false

        outer@ for (start in 0..tokens.size - phraseTokens.size) {
            for (j in phraseTokens.indices) {
                val actual = tokens[start + j]
                if (actual == FIELD_SENTINEL || actual != phraseTokens[j]) {
                    continue@outer
                }
            }
            return true
        }
        return false
    }
}
