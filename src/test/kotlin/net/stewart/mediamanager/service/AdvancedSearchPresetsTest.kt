package net.stewart.mediamanager.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdvancedSearchPresetsTest {

    @Test
    fun `ALL is non-empty and every preset has stable key plus name`() {
        val presets = AdvancedSearchPresets.ALL
        assertTrue(presets.isNotEmpty(), "ALL should not be empty")
        for (p in presets) {
            assertTrue(p.key.isNotBlank(), "preset $p has blank key")
            assertTrue(p.name.isNotBlank(), "preset $p has blank name")
            assertTrue(p.description.isNotBlank(), "preset $p has blank description")
        }
    }

    @Test
    fun `keys are URL-safe`() {
        // The key is documented as "URL-safe, suitable for analytics /
        // bookmarks" — assert lowercase + underscore form so a future
        // entry with spaces / punctuation fails this gate.
        val pattern = Regex("^[a-z0-9_]+$")
        for (p in AdvancedSearchPresets.ALL) {
            assertTrue(pattern.matches(p.key), "key '${p.key}' is not URL-safe")
        }
    }

    @Test
    fun `keys are unique`() {
        val keys = AdvancedSearchPresets.ALL.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "duplicate keys in ALL")
    }

    @Test
    fun `bpm ranges are sane when set`() {
        for (p in AdvancedSearchPresets.ALL) {
            val lo = p.bpmMin
            val hi = p.bpmMax
            if (lo != null) assertTrue(lo > 0, "${p.key} bpmMin <= 0")
            if (hi != null) assertTrue(hi > 0, "${p.key} bpmMax <= 0")
            if (lo != null && hi != null) {
                assertTrue(lo <= hi, "${p.key} bpmMin ($lo) > bpmMax ($hi)")
            }
        }
    }

    @Test
    fun `time signatures are well-known dance meters`() {
        // Curated list — guards against typos like "4-4" vs "4/4".
        val allowed = setOf("3/4", "4/4", "6/8")
        for (p in AdvancedSearchPresets.ALL) {
            val ts = p.timeSignature ?: continue
            assertTrue(ts in allowed, "${p.key} time_signature '$ts' is not in $allowed")
        }
    }

    @Test
    fun `byKey returns the matching preset`() {
        val cha = AdvancedSearchPresets.byKey("cha_cha")
        assertNotNull(cha)
        assertEquals("Cha-Cha", cha.name)
        assertEquals("4/4", cha.timeSignature)
        assertEquals(118, cha.bpmMin)
        assertEquals(128, cha.bpmMax)
    }

    @Test
    fun `byKey returns null for unknown key`() {
        assertNull(AdvancedSearchPresets.byKey("foxtrot_extreme"))
        assertNull(AdvancedSearchPresets.byKey(""))
    }

    @Test
    fun `waltz family uses 3-4 time`() {
        // The waltz entries are the only 3/4 presets in the curated set.
        val waltzes = AdvancedSearchPresets.ALL.filter { it.timeSignature == "3/4" }
        assertTrue(waltzes.size >= 2, "expected slow + viennese waltz")
        assertTrue(waltzes.any { it.key == "slow_waltz" })
        assertTrue(waltzes.any { it.key == "viennese_waltz" })
    }
}
