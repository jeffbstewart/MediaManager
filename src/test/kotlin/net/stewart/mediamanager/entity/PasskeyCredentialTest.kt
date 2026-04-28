package net.stewart.mediamanager.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PasskeyCredentialTest {

    // PasskeyCredential overrides equals/hashCode because data-class
    // equality is unsafe for this entity: the public_key ByteArray
    // doesn't implement structural equality, and the credential_id
    // is the canonical identity. Verify both branches.

    @Test
    fun `equals matches when id and credential_id agree`() {
        val a = PasskeyCredential(id = 1, credential_id = "abc")
        val b = PasskeyCredential(id = 1, credential_id = "abc")
        assertEquals(a, b)
    }

    @Test
    fun `equals differs when credential_id differs`() {
        val a = PasskeyCredential(id = 1, credential_id = "abc")
        val b = PasskeyCredential(id = 1, credential_id = "xyz")
        assertNotEquals(a, b)
    }

    @Test
    fun `equals differs when id differs`() {
        val a = PasskeyCredential(id = 1, credential_id = "abc")
        val b = PasskeyCredential(id = 2, credential_id = "abc")
        assertNotEquals(a, b)
    }

    @Test
    fun `equals ignores public_key bytes`() {
        // The whole point of overriding equals is that the byte array
        // doesn't participate — id + credential_id are canonical.
        val a = PasskeyCredential(id = 1, credential_id = "abc",
            public_key = byteArrayOf(1, 2, 3))
        val b = PasskeyCredential(id = 1, credential_id = "abc",
            public_key = byteArrayOf(9, 9, 9))
        assertEquals(a, b)
    }

    @Test
    fun `equals identity is true`() {
        val a = PasskeyCredential(id = 1, credential_id = "abc")
        assertTrue(a == a)
    }

    @Test
    fun `equals returns false for non-PasskeyCredential`() {
        val a = PasskeyCredential(id = 1, credential_id = "abc")
        assertFalse(a.equals("not a credential"))
        assertFalse(a.equals(null))
    }

    @Test
    fun `hashCode is stable across instances with same credential_id`() {
        val a = PasskeyCredential(id = 1, credential_id = "abc")
        val b = PasskeyCredential(id = 99, credential_id = "abc",
            public_key = byteArrayOf(7, 7, 7))
        // hashCode only uses credential_id, so distinct ids still
        // hash identically.
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `hashCode differs for different credential_id`() {
        val a = PasskeyCredential(credential_id = "abc")
        val b = PasskeyCredential(credential_id = "xyz")
        assertNotEquals(a.hashCode(), b.hashCode())
    }
}
