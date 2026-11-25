package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The link rules of `session-ui/src/components/markdown.tsx`, checked off-device.
 *
 * `urlPattern` is `/^https?:\/\/[^\s<>()`"']+$/`; every case below is a boundary it draws.
 */
class MarkdownLinkTest {

    @Test
    fun `a bare https url in a code span is a link`() {
        assertEquals("https://opencode.ai/docs", MarkdownLink.codeUrl("https://opencode.ai/docs"))
    }

    @Test
    fun `http is allowed too`() {
        assertEquals("http://127.0.0.1:4096", MarkdownLink.codeUrl("http://127.0.0.1:4096"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("https://x.dev", MarkdownLink.codeUrl("  https://x.dev  "))
    }

    @Test
    fun `trailing sentence punctuation is not part of the address`() {
        assertEquals("https://x.dev", MarkdownLink.codeUrl("https://x.dev."))
        assertEquals("https://x.dev", MarkdownLink.codeUrl("https://x.dev,"))
        assertEquals("https://x.dev", MarkdownLink.codeUrl("https://x.dev?!"))
        assertEquals("https://x.dev/a", MarkdownLink.codeUrl("https://x.dev/a);"))
    }

    @Test
    fun `parentheses are excluded, so a wiki-style url is not linkified`() {
        // The character class is `[^\s<>()`"']+` — `(` and `)` are both banned, so their own
        // `codeUrl` returns undefined for this. Faithful, not a shortcut.
        assertNull(MarkdownLink.codeUrl("https://x.dev/a(b)"))
        assertNull(MarkdownLink.codeUrl("https://en.wikipedia.org/wiki/Rust_(language)"))
    }

    @Test
    fun `other schemes are not links`() {
        assertNull(MarkdownLink.codeUrl("mailto:a@b.c"))
        assertNull(MarkdownLink.codeUrl("ftp://x.dev"))
        assertNull(MarkdownLink.codeUrl("file:///sdcard/x"))
        assertNull(MarkdownLink.codeUrl("javascript:alert(1)"))
    }

    @Test
    fun `a bare domain without a scheme is not a link`() {
        assertNull(MarkdownLink.codeUrl("opencode.ai"))
        assertNull(MarkdownLink.codeUrl("www.opencode.ai"))
    }

    @Test
    fun `a url with a space is not a link`() {
        assertNull(MarkdownLink.codeUrl("https://x.dev/a b"))
    }

    @Test
    fun `a url with a quote or angle bracket is not a link`() {
        assertNull(MarkdownLink.codeUrl("https://x.dev/\"a\""))
        assertNull(MarkdownLink.codeUrl("https://x.dev/<a>"))
        assertNull(MarkdownLink.codeUrl("https://x.dev/`a`"))
    }

    @Test
    fun `an anchor with an http href is a link`() {
        assertEquals("https://x.dev/a", MarkdownLink.anchorTarget("https://x.dev/a"))
    }

    @Test
    fun `an anchor with a fragment is not opened externally`() {
        // Desktop scrolls to the heading inside the document; a phone transcript has no
        // document to scroll, so it stays text rather than opening a broken URL.
        assertNull(MarkdownLink.anchorTarget("#install"))
    }

    @Test
    fun `a relative anchor is not opened externally`() {
        assertNull(MarkdownLink.anchorTarget("./docs/install.md"))
        assertNull(MarkdownLink.anchorTarget("/api/session"))
    }

    @Test
    fun `an empty or missing href is not a link`() {
        assertNull(MarkdownLink.anchorTarget(null))
        assertNull(MarkdownLink.anchorTarget(""))
        assertNull(MarkdownLink.anchorTarget("   "))
    }
}
