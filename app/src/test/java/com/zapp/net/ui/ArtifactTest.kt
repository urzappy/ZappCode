package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop bundle's artifact table (`artifactMime` / `artifactKind`).
 *
 * The cases that are easy to get wrong and are therefore pinned here:
 * - a name whose only dot is at index 0 (`.gitignore`) has **no** extension;
 * - the extension is taken from the **last** path segment, on either separator;
 * - an unknown extension is `Text`, not a guess;
 * - a known MIME outside the named groups falls through to `Video` unless it is an office type.
 */
class ArtifactTest {

    @Test
    fun extensionComesFromTheLastSegmentOnEitherSeparator() {
        assertEquals("png", Artifact.extensionOf("a/b/c/photo.PNG"))
        assertEquals("png", Artifact.extensionOf("a\\b\\photo.png"))
        assertEquals("ts", Artifact.extensionOf("src/Main.kt.ts"))
        assertEquals("kt", Artifact.extensionOf("Main.kt"))
    }

    @Test
    fun aLeadingDotIsNotAnExtension() {
        // index 0 -> no extension, so `.gitignore` is text and not a "gitignore" file type
        assertEquals("", Artifact.extensionOf(".gitignore"))
        assertEquals("", Artifact.extensionOf("dir/.env"))
        assertNull(Artifact.mimeOf(".gitignore"))
        assertEquals(Artifact.Kind.Text, Artifact.kindOf(".gitignore"))
        // a dot later in the name still counts, and the **last** one wins
        assertEquals("local", Artifact.extensionOf(".env.local"))
    }

    @Test
    fun anUnknownExtensionIsText() {
        assertNull(Artifact.mimeOf("notes.zzz"))
        assertEquals(Artifact.Kind.Text, Artifact.kindOf("notes.zzz"))
        assertEquals(Artifact.Kind.Text, Artifact.kindOf("Makefile"))
    }

    @Test
    fun theNamedGroupsWinBeforeThePrefixRules() {
        assertEquals(Artifact.Kind.Svg, Artifact.kindOf("logo.svg"))
        assertEquals(Artifact.Kind.Pdf, Artifact.kindOf("doc.pdf"))
        assertEquals(Artifact.Kind.Html, Artifact.kindOf("index.htm"))
        assertEquals(Artifact.Kind.Markdown, Artifact.kindOf("README.mdx"))
        assertEquals(Artifact.Kind.Mermaid, Artifact.kindOf("flow.mmd"))
        assertEquals(Artifact.Kind.Table, Artifact.kindOf("data.csv"))
        assertEquals(Artifact.Kind.Table, Artifact.kindOf("data.tsv"))
    }

    @Test
    fun prefixGroupsCoverImageAudioAndFont() {
        assertEquals(Artifact.Kind.Image, Artifact.kindOf("a.webp"))
        assertEquals(Artifact.Kind.Image, Artifact.kindOf("a.heic"))
        assertEquals(Artifact.Kind.Audio, Artifact.kindOf("a.flac"))
        assertEquals(Artifact.Kind.Audio, Artifact.kindOf("a.opus"))
        assertEquals(Artifact.Kind.Font, Artifact.kindOf("a.woff2"))
    }

    @Test
    fun officeTypesAreNamedAndEverythingElseKnownIsVideo() {
        assertEquals(Artifact.Kind.Document, Artifact.kindOf("a.docx"))
        assertEquals(Artifact.Kind.Spreadsheet, Artifact.kindOf("a.xlsx"))
        assertEquals(Artifact.Kind.Presentation, Artifact.kindOf("a.pptx"))
        // mp4 / mkv / mov all land on the fallthrough
        assertEquals(Artifact.Kind.Video, Artifact.kindOf("a.mp4"))
        assertEquals(Artifact.Kind.Video, Artifact.kindOf("a.mkv"))
        assertEquals(Artifact.Kind.Video, Artifact.kindOf("a.mov"))
    }

    @Test
    fun onlyImageAndSvgAreDrawableFromBytes() {
        assertTrue(Artifact.isImage(Artifact.kindOf("a.png")))
        assertTrue(Artifact.isImage(Artifact.kindOf("a.svg")))
        assertFalse(Artifact.isImage(Artifact.kindOf("a.pdf")))
        assertFalse(Artifact.isImage(Artifact.kindOf("a.mp3")))
        assertFalse(Artifact.isImage(Artifact.Kind.Text))
    }

    @Test
    fun theMimeValuesAreTheirs() {
        assertEquals("image/jpeg", Artifact.mimeOf("a.jpg"))
        assertEquals("image/jpeg", Artifact.mimeOf("a.jpeg"))
        assertEquals("audio/ogg", Artifact.mimeOf("a.ogg"))
        assertEquals("audio/ogg", Artifact.mimeOf("a.opus"))
        assertEquals("video/quicktime", Artifact.mimeOf("a.mov"))
        assertEquals("image/x-icon", Artifact.mimeOf("a.ico"))
        assertEquals("text/tab-separated-values", Artifact.mimeOf("a.tsv"))
    }
}
