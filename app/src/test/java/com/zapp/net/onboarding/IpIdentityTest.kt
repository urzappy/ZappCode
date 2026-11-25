package com.zapp.net.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests exist because the privacy claim is the whole feature. A masking bug that
 * leaks one hextet makes the screen a lie, and a canonicalisation bug makes two different
 * machines share an identity. Both are checked here rather than on the phone.
 */
class IpIdentityTest {

    private val warp = "2a09:bac5:4ff3:25d7::3c5:10"

    @Test
    fun `canonical strips zone, brackets, case and whitespace`() {
        assertEquals(warp, IpIdentity.canonical("  2A09:BAC5:4FF3:25D7::3C5:10  "))
        assertEquals(warp, IpIdentity.canonical("[$warp]"))
        assertEquals(warp, IpIdentity.canonical("$warp%wlan0"))
        assertEquals(
            "203.0.113.47",
            IpIdentity.canonical("::ffff:203.0.113.47"),
        )
    }

    @Test
    fun `id is stable for the same input and differs across addresses`() {
        val a = IpIdentity.idOf(warp)
        val b = IpIdentity.idOf(IpIdentity.canonical("2A09:BAC5:4FF3:25D7::3C5:10"))
        assertEquals(a, b)
        assertNotEquals(a, IpIdentity.idOf("2a09:bac5:4ff3:25d8::3c5:10"))
    }

    @Test
    fun `id is 16 hex chars and never contains the address`() {
        val id = IpIdentity.idOf(warp)
        assertEquals(16, id.length)
        assertTrue(id.all { it in "0123456789ABCDEF" })
        assertFalse(id.contains("2a09", ignoreCase = true))
        // The last hextets are the most identifying part of the address; they must not
        // survive into the id in any form.
        assertFalse(id.contains("3C5", ignoreCase = true))
        assertFalse(id.contains("10", ignoreCase = false) && id.endsWith("10"))
    }

    @Test
    fun `masked hides everything between the first two and last two hextets`() {
        val i = IpIdentity.identify(warp)
        assertNotNull(i)
        val masked = i!!.masked
        assertTrue("kept the /32 prefix: $masked", masked.startsWith("2a09:bac5:"))
        assertFalse("leaked 4ff3: $masked", masked.contains("4ff3"))
        assertFalse("leaked 25d7: $masked", masked.contains("25d7"))
        assertFalse("leaked 3c5: $masked", masked.contains("3c5"))
    }

    @Test
    fun `masked ipv4 keeps only first and last octet`() {
        val i = IpIdentity.identify("203.0.113.47")!!
        assertEquals("203.•••.•••.47", i.masked)
        assertEquals(IpIdentity.Strength.V4_NOT_PRIVATE, i.family)
    }

    @Test
    fun `ipv6 is reported private and ipv4 is not`() {
        assertEquals(IpIdentity.Strength.V6_PRIVATE, IpIdentity.identify(warp)!!.family)
        assertEquals(IpIdentity.Strength.V4_NOT_PRIVATE, IpIdentity.identify("8.8.8.8")!!.family)
    }

    @Test
    fun `fingerprint uses the expanded tail so short forms cannot collide`() {
        // `::3c5:10` and `:0:0:3c5:10` are the same address written two ways; the
        // fingerprint has to agree, which is what expansion is for.
        val a = IpIdentity.identify("2a09:bac5:4ff3:25d7::3c5:10")!!.fingerprint
        val b = IpIdentity.identify("2a09:bac5:4ff3:25d7:0:0:3c5:10")!!.fingerprint
        assertEquals(a, b)
        assertEquals("#03c5:0010", a)
    }

    @Test
    fun `different addresses in one prefix get different ids`() {
        // The provider buckets on the host address, so the id must too. Collapsing to
        // the /64 would merge every device on one WARP lease into a single account.
        val a = IpIdentity.idOf("2a09:bac5:4ff3:25d7::3c5:10")
        val b = IpIdentity.idOf("2a09:bac5:4ff3:25d7::3c5:11")
        assertNotEquals(a, b)
    }

    @Test
    fun `blank and junk input is refused`() {
        assertEquals(null, IpIdentity.identify(""))
        assertEquals(null, IpIdentity.identify("   "))
        assertFalse(IpIdentity.looksLikeAddress("not an address"))
        assertTrue(IpIdentity.looksLikeAddress(warp))
    }
}
