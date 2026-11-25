package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `A` / `Ce` / `xe` out of `panel-BydSK99n.js` @ 1 242. The address bar is a search box: a term
 * goes to Google, a host goes to that host, a blank tab stays blank.
 */
class BrowserUrlTest {

    @Test
    fun blankStaysBlank() {
        assertEquals("about:blank", browserUrl(""))
        assertEquals("about:blank", browserUrl("   "))
        assertEquals("about:blank", browserUrl("about:blank"))
        assertEquals("about:blank", browserUrl("ABOUT:BLANK"))
    }

    @Test
    fun termsBecomeSearches() {
        assertEquals(
            "https://www.google.com/search?q=hello%20world",
            browserUrl("hello world"),
        )
        assertEquals(
            "https://www.google.com/search?q=rust%20borrow%20checker",
            browserUrl("rust borrow checker"),
        )
        assertEquals("https://www.google.com/search?q=notaurl", browserUrl("notaurl"))
    }

    @Test
    fun hostsStayHosts() {
        assertEquals("https://example.com", browserUrl("example.com"))
        assertEquals("https://localhost:4096", browserUrl("localhost:4096"))
        assertEquals("https://192.168.1.7:4096", browserUrl("192.168.1.7:4096"))
        assertEquals("https://example.com/a?b=c", browserUrl("example.com/a?b=c"))
    }

    @Test
    fun schemesAreKept() {
        assertEquals("https://example.com/x", browserUrl("https://example.com/x"))
        assertEquals("http://localhost:4096/app", browserUrl("http://localhost:4096/app"))
        assertEquals("about:config", browserUrl("about:config"))
    }

    @Test
    fun credentialsAreSearched() {
        // `xe` refuses a URL carrying a username or password, so their `Ce` answers "search".
        assertEquals(
            "https://www.google.com/search?q=user%3Apass%40example.com",
            browserUrl("user:pass@example.com"),
        )
    }

    @Test
    fun aSchemeWithAnEmptyAuthorityIsKept() {
        // Their `URL.canParse("ht:///")` is true, so `Ce` answers "not a search" and `A` returns
        // the string untouched. Verified against their own functions in Node.
        assertEquals("ht:///", browserUrl("ht:///"))
        assertEquals("ht://x/y", browserUrl("ht://x/y"))
        assertEquals("ftp://files.example.com/a", browserUrl("ftp://files.example.com/a"))
    }
}
