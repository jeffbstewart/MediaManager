package net.stewart.mediamanager.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UniqueIdTest {

    @Test
    fun `UpcUniqueId stores the raw upc as the storage key`() {
        val id = UpcUniqueId("0883929636686")
        assertEquals("0883929636686", id.storageKey)
    }

    @Test
    fun `UpcUniqueId shards on positions 7 and 8`() {
        // UPC-A layout: S-MMMMM-PPPPP-C. Position 7 + 8 are the first
        // two product-code digits — vary most across items from one
        // manufacturer.
        // "0883929636686" indexed:  0 1 2 3 4 5 6 7 8 9 10 11 12
        //                           0 8 8 3 9 2 9 6 3 6  6  8  6
        val id = UpcUniqueId("0883929636686")
        assertEquals('6', id.shard1)
        assertEquals('3', id.shard2)
    }

    @Test
    fun `UpcUniqueId rejects non-alphanumeric input`() {
        assertFailsWith<IllegalArgumentException> {
            UpcUniqueId("0883-929-636686")
        }
        assertFailsWith<IllegalArgumentException> {
            UpcUniqueId("0883 929 636686")
        }
        assertFailsWith<IllegalArgumentException> {
            UpcUniqueId("")
        }
    }

    @Test
    fun `UpcUniqueId rejects too-short input`() {
        // The product-code shard requires at least 10 characters.
        assertFailsWith<IllegalArgumentException> {
            UpcUniqueId("123456789")  // 9 chars
        }
    }

    @Test
    fun `UpcUniqueId accepts mixed alphanumeric`() {
        // Some non-physical SKUs use letters; the contract says
        // alphanumeric, not numeric-only.
        val id = UpcUniqueId("ABC1234567890")
        assertEquals("ABC1234567890", id.storageKey)
        assertEquals('5', id.shard1)
        assertEquals('6', id.shard2)
    }
}
