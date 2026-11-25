package com.zapp.net.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import com.zapp.net.ui.theme.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * marked's `autolink` and `url` tokenizers, pinned against the shipped bundle
 * (`markdown-QX4Pt8NM.js` @ 22 901 / 24 662 / 38 052).
 *
 * The second half drives [inlineSpan] end to end, because a token that is found but never
 * painted is the exact bug the owner reported: *"no the text link that i can click
 * automatically"* - the reply had a URL in it and the URL was plain text.
 */
class AutolinkTest {

    // ---- the tokenizer ------------------------------------------------------------------

    @Test
    fun `a bare https url is a link`() {
        val h = Autolink.at("https://example.com", 0)
        assertEquals("https://example.com", h?.text)
        assertEquals("https://example.com", h?.href)
        assertEquals(19, h?.rawLength)
    }

    @Test
    fun `www is a link and gets the http scheme`() {
        val h = Autolink.at("www.example.com", 0)
        assertEquals("www.example.com", h?.text)
        assertEquals("http://www.example.com", h?.href)
    }

    @Test
    fun `uppercase WWW is not a link - their literal is lowercase and the rule has no i flag`() {
        assertNull(Autolink.at("WWW.example.com", 0))
    }

    @Test
    fun `ftp is a link`() {
        val h = Autolink.at("ftp://x.dev", 0)
        assertEquals("ftp://x.dev", h?.href)
    }

    @Test
    fun `a host and port is a link - our extension, marked would leave it plain`() {
        val h = Autolink.at("127.0.0.1:8090", 0)
        assertEquals("127.0.0.1:8090", h?.text)
        assertEquals("http://127.0.0.1:8090", h?.href)
        assertEquals(14, h?.rawLength)
        assertEquals("http://localhost:4096", Autolink.at("localhost:4096", 0)?.href)
        assertEquals("http://x.dev:8080", Autolink.at("x.dev:8080", 0)?.href)
    }

    @Test
    fun `the host and port rule does not eat code or prose`() {
        // The guards that keep the extension from turning every dotted word into a link:
        // a host with no port, a TLD-less label, and `name:value` are all still text.
        assertNull(Autolink.at("MainActivity.kt", 0))
        assertNull(Autolink.at("src/main/java", 0))
        assertNull(Autolink.at("android:label", 0))
        assertNull(Autolink.at("v2.3.2", 0))
        assertNull(Autolink.at("10:30", 0))
        assertNull(Autolink.at("foo.bar/baz", 0))
    }

    @Test
    fun `a real url still wins over the host and port rule`() {
        val h = Autolink.at("https://x.dev:8080/a", 0)
        assertEquals("https://x.dev:8080/a", h?.href)
    }

    @Test
    fun `a sentence full stop is not part of the address`() {
        val h = Autolink.at("see https://x.dev.", 4)
        assertEquals("https://x.dev", h?.text)
        assertEquals(13, h?.rawLength)
    }

    @Test
    fun `a closing paren is backpedalled away`() {
        val h = Autolink.at("(https://x.dev)", 1)
        assertEquals("https://x.dev", h?.text)
        assertEquals(13, h?.rawLength)
    }

    @Test
    fun `closing emphasis stars are left for the emphasis pass`() {
        val h = Autolink.at("**https://x**", 2)
        assertEquals("https://x", h?.text)
        assertEquals(9, h?.rawLength)
    }

    @Test
    fun `an underscore does not truncate the link`() {
        // `_` is in `_backpedal`'s second class `[?!.,:;*_'"~)]`, so it is consumed like
        // any other character. Checked against the regex itself in node:
        //   "https://x.dev/a_b" -> "https://x.dev/a_b"
        val h = Autolink.at("https://x.dev/a_b", 0)
        assertEquals("https://x.dev/a_b", h?.text)
        assertEquals(17, h?.rawLength)
    }

    @Test
    fun `dashes dots and tildes survive`() {
        val h = Autolink.at("https://x.dev/a.b_c-d~e", 0)
        assertEquals("https://x.dev/a.b_c-d~e", h?.text)
    }

    @Test
    fun `a trailing html entity is not swallowed`() {
        // `&(?![a-zA-Z0-9]+;$)` - `&amp;` at the end is an entity, not part of the href.
        val h = Autolink.at("https://x.dev/?a=1&amp;", 0)
        assertEquals("https://x.dev/?a=1", h?.text)
        assertEquals(18, h?.rawLength)
    }

    @Test
    fun `an ampersand in the middle is part of the link`() {
        val h = Autolink.at("https://x.dev/?a=1&b=2", 0)
        assertEquals("https://x.dev/?a=1&b=2", h?.text)
    }

    @Test
    fun `a bare email becomes mailto`() {
        val h = Autolink.at("a@b.com", 0)
        assertEquals("a@b.com", h?.text)
        assertEquals("mailto:a@b.com", h?.href)
        assertEquals(7, h?.rawLength)
    }

    @Test
    fun `an angle bracket autolink is a link`() {
        val h = Autolink.at("<https://x.dev>", 0)
        assertEquals("https://x.dev", h?.text)
        assertEquals("https://x.dev", h?.href)
        assertEquals(15, h?.rawLength)
    }

    @Test
    fun `an angle bracket email autolink becomes mailto`() {
        val h = Autolink.at("<a@b.com>", 0)
        assertEquals("a@b.com", h?.text)
        assertEquals("mailto:a@b.com", h?.href)
        assertEquals(9, h?.rawLength)
    }

    @Test
    fun `an unclosed angle bracket is not a link`() {
        assertNull(Autolink.at("<https://x.dev", 0))
    }

    @Test
    fun `plain words are not links`() {
        assertNull(Autolink.at("hello world", 0))
        assertNull(Autolink.at("src/main/java", 0))
        assertNull(Autolink.at("", 0))
    }

    // ---- the renderer -------------------------------------------------------------------

    private fun links(s: AnnotatedString): List<LinkAnnotation> =
        s.getLinkAnnotations(0, s.length).map { it.item }

    @Test
    fun `a bare url in prose is painted with the link token`() {
        val s = inlineSpan("open https://x.dev now", Color.White)
        assertEquals("open https://x.dev now", s.text)
        val range = s.getLinkAnnotations(0, s.length).single()
        assertEquals(5, range.start)
        assertEquals(18, range.end)
        val url = range.item as LinkAnnotation.Url
        assertEquals("https://x.dev", url.url)
        assertEquals(Link, url.styles?.style?.color)
    }

    @Test
    fun `www is painted and its target carries the scheme`() {
        val s = inlineSpan("www.x.dev", Color.White)
        val url = links(s).single() as LinkAnnotation.Url
        assertEquals("http://www.x.dev", url.url)
    }

    @Test
    fun `a host and port in prose is painted and tappable`() {
        val s = inlineSpan("check 127.0.0.1:8090 now", Color.White)
        val range = s.getLinkAnnotations(0, s.length).single()
        assertEquals(6, range.start)
        assertEquals(20, range.end)
        val url = range.item as LinkAnnotation.Url
        assertEquals("http://127.0.0.1:8090", url.url)
        assertEquals(Link, url.styles?.style?.color)
    }

    @Test
    fun `a mailto is coloured but not tappable - anchorTarget only admits http(s)`() {
        val s = inlineSpan("write a@b.com", Color.White)
        assertTrue(links(s).isEmpty())
        assertEquals(Link, s.spanStyles.last().item.color)
    }

    @Test
    fun `a url that is the whole inline code span is an anchor - their markCodeLinks`() {
        // `markdown.tsx:973` turns a code span that *is* a URL into an anchor. This is the
        // pre-existing `codeUrl` path, not the new autolink; it proves the autolink does
        // not fight it.
        val s = inlineSpan("`https://x.dev`", Color.White)
        assertEquals("https://x.dev", s.text)
        val url = links(s).single() as LinkAnnotation.Url
        assertEquals("https://x.dev", url.url)
    }

    @Test
    fun `a code span containing more than the url is not an anchor`() {
        val s = inlineSpan("`see https://x.dev`", Color.White)
        assertTrue(links(s).isEmpty())
    }

    @Test
    fun `a url keeps the emphasis around it`() {
        val s = inlineSpan("**https://x**", Color.White)
        assertEquals("https://x", s.text)
        val url = links(s).single() as LinkAnnotation.Url
        assertEquals("https://x", url.url)
    }
}
