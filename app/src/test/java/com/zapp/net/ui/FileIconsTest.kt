package com.zapp.net.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `choose-icon-name.ts:498-515`, case by case. Every expectation below is what the original
 * function returns for that input - the order is: exact file name, then the longest dotted suffix,
 * then the default; a folder tries `name`, `.name`, `_name`, `__name__` before its default.
 */
class FileIconsTest {

    private fun file(path: String) = chooseIconName(path, isDirectory = false)
    private fun folder(path: String, open: Boolean = false) = chooseIconName(path, isDirectory = true, expanded = open)

    @Test
    fun `exact file name wins over its extension`() {
        assertEquals("Readme", file("readme.md"))
        assertEquals("Docker", file("Dockerfile"))
        assertEquals("Docker", file("docker-compose.yml"))
        assertEquals("Tsconfig", file("tsconfig.json"))
        assertEquals("Makefile", file("Makefile"))
        assertEquals("Rust", file("Cargo.toml"))
        assertEquals("Bun", file("bun.lockb"))
    }

    @Test
    fun `names are matched case-insensitively`() {
        assertEquals("Certificate", file("LICENSE"))
        assertEquals("Docker", file("DOCKERFILE"))
        assertEquals("Readme", file("README.MD"))
    }

    @Test
    fun `the longest dotted suffix wins`() {
        assertEquals("TestTs", file("a.test.ts"))
        assertEquals("TestJsx", file("foo.spec.tsx"))
        assertEquals("JavascriptMap", file("app.js.map"))
        assertEquals("TypescriptDef", file("x.d.ts"))
        assertEquals("TestJs", file("a.spec.js"))
    }

    @Test
    fun `a two-part suffix still beats the plain extension`() {
        // `d.ts` is longer than `ts`, so a declaration file is not just TypeScript
        assertEquals("TypescriptDef", file("d.ts"))
        assertEquals("JavascriptMap", file("js.map"))
    }

    @Test
    fun `plain extensions`() {
        assertEquals("Kotlin", file("main.kt"))
        assertEquals("Rust", file("main.rs"))
        assertEquals("Python", file("main.py"))
        assertEquals("Typescript", file("main.ts"))
        assertEquals("React_ts", file("App.tsx"))
        assertEquals("Markdown", file("notes.md"))
        assertEquals("Yaml", file(".github/workflows/ci.yml"))
        assertEquals("Tune", file(".env"))
        assertEquals("Console", file("run.sh"))
    }

    @Test
    fun `the whole name is also tried as an extension`() {
        // their `dottedSuffixesDesc` adds the name itself, so a bare `dockerfile` resolves
        assertEquals("Docker", file("dockerfile"))
    }

    @Test
    fun `unknown files fall back to the default`() {
        assertEquals("Document", file("mystery.zzz"))
        assertEquals("Document", file("no-extension"))
    }

    @Test
    fun `only the basename matters`() {
        assertEquals("Typescript", file("a/b/c.ts"))
        assertEquals("Python", file("a\\b\\c.py"))
        assertEquals("Rust", file("/abs/path/main.rs"))
    }

    @Test
    fun `folder name variants`() {
        assertEquals("FolderSrc", folder("src"))
        assertEquals("FolderLib", folder("lib"))
        assertEquals("FolderTest", folder("__tests__"))
        assertEquals("FolderGithub", folder(".github"))
        assertEquals("FolderTypescript", folder("@types"))
        assertEquals("FolderDocs", folder("docs"))
    }

    @Test
    fun `folder defaults and the open variant`() {
        assertEquals("Folder", folder("weird"))
        assertEquals("FolderOpen", folder("weird", open = true))
        assertEquals("FolderSrc", folder("src"))
        assertEquals("FolderSrcOpen", folder("src", open = true))
        assertEquals("FolderNodeOpen", folder("node_modules", open = true))
    }

    @Test
    fun `every icon the tables can name has artwork behind it`() {
        val missing = allIconNames().filter { it != "Document" && iconResOf(it) == 0 }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `the default is the only name with no artwork`() {
        // their `document` symbol is not in the build at all: the eager fallback glob in
        // `file-icon.tsx:174-183` inlines `folder` and `folder-open` and nothing else
        assertEquals(0, iconResOf("Document"))
    }

    @Test
    fun `an unknown name yields nothing`() {
        assertEquals(0, iconResOf("Whatever"))
    }
}
