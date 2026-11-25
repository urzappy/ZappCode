package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prose highlighter, checked against the exact block the owner pointed at on the phone.
 *
 * The screenshot he sent was one assistant reply rendered as flat grey:
 *
 *     time   : 2026-11-21 15:10:24 UTC / 2026-11-21 23:10:24 PHT model :
 *     deepseek-v4.1-flash-free (provider: opencode) session : ses e0880b09fffe5... host : localhost /
 *     aarch64 / u0_a448 lan ip : 192.168.1.7 vpn ip : 172.16.0.2 public ip: 2a09:bac5:4ff7:2e1e::498:2c
 *     tokens : not exposed by harness (no live counter)
 *
 * Each assertion below is one of those details, so a regex change that stops colouring dates, or
 * starts colouring a clock as an address, fails here.
 */
class SemanticTest {

    private fun kinds(text: String) = Semantic.hits(text).map { text.substring(it.start, it.end) to it.kind }

    private fun coloured(text: String): List<Pair<String, Sem>> = kinds(text)

    private fun assertHas(text: String, token: String, kind: Sem) {
        val hits = coloured(text)
        val hit = hits.firstOrNull { it.first == token }
        assertTrue("$token not coloured in: $text", hit != null)
        assertEquals("$token coloured as ${hit!!.second}", kind, hit.second)
    }

    @Test
    fun `iso date is a date`() {
        assertHas("time : 2026-11-21 15:10:24 UTC", "2026-11-21", Sem.DATE)
    }

    @Test
    fun `clock with seconds is a date not an address`() {
        val hits = coloured("15:10:24 UTC")
        assertEquals(listOf("15:10:24" to Sem.DATE), hits)
    }

    @Test
    fun `clock without seconds is a date`() {
        assertHas("at 23:10 PHT", "23:10", Sem.DATE)
    }

    @Test
    fun `ipv4 is one address not four numbers`() {
        val hits = coloured("lan ip : 192.168.1.7")
        assertEquals(1, hits.count { it.second == Sem.ADDRESS })
        assertHas("lan ip : 192.168.1.7", "192.168.1.7", Sem.ADDRESS)
    }

    @Test
    fun `compressed ipv6 is one address`() {
        assertHas("public ip: 2a09:bac5:4ff7:2e1e::498:2c", "2a09:bac5:4ff7:2e1e::498:2c", Sem.ADDRESS)
    }

    @Test
    fun `an out of range quad is not an address`() {
        val hits = coloured("999.1.1.1")
        assertTrue(hits.none { it.second == Sem.ADDRESS })
    }

    @Test
    fun `a version is a number`() {
        assertHas("model : deepseek-v4.1-flash-free", "v4.1", Sem.NUMBER)
        assertHas("upgraded to 2.3.2 today", "2.3.2", Sem.NUMBER)
    }

    @Test
    fun `a duration is a number`() {
        assertHas("~1h49m gap since the last check", "~1h", Sem.NUMBER)
        assertHas("Only 26 seconds since the last check", "26", Sem.NUMBER)
    }

    @Test
    fun `a long hex run is an identifier`() {
        assertHas("session : e0880b09fffe5a5d1e", "e0880b09fffe5a5d1e", Sem.IDENTIFIER)
    }

    @Test
    fun `a field label is a label`() {
        assertHas("lan ip : 192.168.1.7", "lan ip", Sem.LABEL)
        assertHas("time   : 2026-11-21", "time", Sem.LABEL)
        assertHas("host : localhost", "host", Sem.LABEL)
    }

    @Test
    fun `a label needs whitespace after the colon`() {
        // `localhost:8080` is a host and port, not a field called `localhost`.
        assertTrue(coloured("localhost:8080").none { it.second == Sem.LABEL })
    }

    @Test
    fun `a number inside a word is left alone`() {
        // `u0_a448` and `aarch64` are identifiers, not counts.
        assertTrue(coloured("aarch64 / u0_a448").isEmpty())
    }

    @Test
    fun `hits never overlap and never come out of order`() {
        val text = "time : 2026-11-21 15:10:24 UTC / 2026-11-21 23:10:24 PHT host : " +
            "localhost / aarch64 / u0_a448 lan ip : 192.168.1.7 vpn ip : 172.16.0.2 " +
            "public ip: 2a09:bac5:4ff7:2e1e::498:2c tokens : not exposed (no live counter)"
        val hits = Semantic.hits(text)
        var last = 0
        for (h in hits) {
            assertTrue("overlap at ${h.start}", h.start >= last)
            assertTrue("empty range at ${h.start}", h.end > h.start)
            assertTrue("out of bounds", h.end <= text.length)
            last = h.end
        }
    }

    @Test
    fun `an empty string has no hits`() {
        assertTrue(Semantic.hits("").isEmpty())
    }

    @Test
    fun `plain prose stays plain`() {
        assertTrue(Semantic.hits("Only nothing changed, including the public address.").isEmpty())
    }

    @Test
    fun `the four colours are distinct`() {
        val c = listOf(Sem.DATE.color, Sem.ADDRESS.color, Sem.NUMBER.color, Sem.IDENTIFIER.color)
        assertEquals(c.size, c.toSet().size)
        // A label is structure, not an accent: it must not equal any of the four.
        assertTrue(Sem.LABEL.color !in c)
    }
}
