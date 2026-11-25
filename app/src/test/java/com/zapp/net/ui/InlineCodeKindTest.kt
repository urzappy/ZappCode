package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `session-ui/src/components/markdown-inline-code-kind.ts:45` — `inlineCodeKind`.
 *
 * Their rule is a cheap syntax check: a `path` only becomes a link once the host confirms the
 * file exists. These cases pin the syntax half.
 */
class InlineCodeKindTest {

    @Test
    fun `an http url is a url`() {
        assertEquals(InlineCodeKind.Kind.Url, InlineCodeKind.kind("https://opencode.ai/docs"))
        assertEquals(InlineCodeKind.Kind.Url, InlineCodeKind.kind("HTTP://x.dev"))
    }

    @Test
    fun `a path with a separator and a letter is a path`() {
        assertEquals(InlineCodeKind.Kind.Path, InlineCodeKind.kind("src/main/Main.kt"))
        assertEquals(InlineCodeKind.Kind.Path, InlineCodeKind.kind("./gradlew"))
        assertEquals(InlineCodeKind.Kind.Path, InlineCodeKind.kind("app\\build.gradle.kts"))
    }

    @Test
    fun `a bare extension is a path`() {
        assertEquals(InlineCodeKind.Kind.Path, InlineCodeKind.kind("MainActivity.kt:299"))
        assertEquals(InlineCodeKind.Kind.Path, InlineCodeKind.kind("ZAPP-SESSION-LOG.md"))
    }

    @Test
    fun `a known extensionless file is a path`() {
        for (name in listOf("Dockerfile", "Makefile", "LICENSE", "README", "Justfile")) {
            assertEquals(name, InlineCodeKind.Kind.Path, InlineCodeKind.kind(name))
        }
    }

    @Test
    fun `a known name is case-sensitive, like their Set`() {
        assertEquals(InlineCodeKind.Kind.None, InlineCodeKind.kind("dockerfile"))
    }

    @Test
    fun `a bare word is not a path`() {
        assertEquals(InlineCodeKind.Kind.None, InlineCodeKind.kind("true"))
        assertEquals(InlineCodeKind.Kind.None, InlineCodeKind.kind("null"))
        assertEquals(InlineCodeKind.Kind.None, InlineCodeKind.kind("v2"))
    }

    @Test
    fun `a version number is not a path`() {
        // No letter, so `[a-z]` fails even though `.` is a separator.
        assertEquals(InlineCodeKind.Kind.None, InlineCodeKind.kind("1.5"))
        assertEquals(InlineCodeKind.Kind.None, InlineCodeKind.kind("2.0.1"))
    }

    @Test
    fun `whitespace disqualifies a path`() {
        assertEquals(InlineCodeKind.Kind.None, InlineCodeKind.kind("npm run build"))
        assertEquals(InlineCodeKind.Kind.None, InlineCodeKind.kind("a b.kt"))
    }

    @Test
    fun `code punctuation disqualifies a path`() {
        for (text in listOf("a|b", "x=y", "a&b", "f(x)", "<T>", "\"s\"", "'s'", "a;b", "*.kt")) {
            assertEquals(text, InlineCodeKind.Kind.None, InlineCodeKind.kind(text))
        }
    }

    @Test
    fun `a network share is refused`() {
        // "would make the server connect to the host just to check it" — `:61`
        assertEquals(InlineCodeKind.Kind.None, InlineCodeKind.kind("\\\\host\\share"))
        assertEquals(InlineCodeKind.Kind.None, InlineCodeKind.kind("//host/share"))
    }

    @Test
    fun `a scheme with slashes is not a path`() {
        assertEquals(InlineCodeKind.Kind.None, InlineCodeKind.kind("file:///sdcard/x"))
        assertEquals(InlineCodeKind.Kind.None, InlineCodeKind.kind("ftp://x.dev"))
        assertEquals(InlineCodeKind.Kind.None, InlineCodeKind.kind("vscode://open"))
    }

    @Test
    fun `a scheme without slashes is only a syntax candidate`() {
        // Their `SCHEME` test needs `://`, and `@` is not in the punctuation set, so
        // `mailto:a@b.c` passes the syntax check and is left for the host to refuse.
        assertEquals(InlineCodeKind.Kind.Path, InlineCodeKind.kind("mailto:a@b.c"))
    }

    @Test
    fun `an empty span is nothing`() {
        assertEquals(InlineCodeKind.Kind.None, InlineCodeKind.kind(""))
    }

    @Test
    fun `a path with a line suffix still reads as a path`() {
        assertEquals(InlineCodeKind.Kind.Path, InlineCodeKind.kind("MarkdownText.kt#L97"))
        assertEquals("MarkdownText.kt", PathSuffix.parse("MarkdownText.kt#L97").path)
    }
}
