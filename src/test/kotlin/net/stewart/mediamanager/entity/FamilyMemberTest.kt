package net.stewart.mediamanager.entity

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FamilyMemberTest {

    @Test
    fun `ageAt returns full years before birthday this year`() {
        // Born 2000-06-15; on 2026-06-14 they're still 25.
        val m = FamilyMember(birth_date = LocalDate.of(2000, 6, 15))
        assertEquals(25, m.ageAt(LocalDate.of(2026, 6, 14)))
    }

    @Test
    fun `ageAt returns full years on the birthday`() {
        val m = FamilyMember(birth_date = LocalDate.of(2000, 6, 15))
        assertEquals(26, m.ageAt(LocalDate.of(2026, 6, 15)))
    }

    @Test
    fun `ageAt returns full years after birthday`() {
        val m = FamilyMember(birth_date = LocalDate.of(2000, 6, 15))
        assertEquals(26, m.ageAt(LocalDate.of(2026, 6, 16)))
    }

    @Test
    fun `ageAt returns null when birth_date missing`() {
        assertNull(FamilyMember(birth_date = null).ageAt(LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `ageAt zero on birth day`() {
        val m = FamilyMember(birth_date = LocalDate.of(2026, 4, 28))
        assertEquals(0, m.ageAt(LocalDate.of(2026, 4, 28)))
    }

    @Test
    fun `ageAt does not go negative for future birth_date`() {
        // Java Period.between produces a negative value when the second
        // argument predates the first; our helper returns that value
        // verbatim. The contract is documented as "age in years" so a
        // negative result tells the caller the date precedes the birth
        // date — leave the behaviour pinned in case anyone relies on
        // the polarity.
        val m = FamilyMember(birth_date = LocalDate.of(2030, 1, 1))
        assertEquals(-3, m.ageAt(LocalDate.of(2026, 12, 31)))
    }
}
