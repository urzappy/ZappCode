package com.zapp.net.ui

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `settings.permissions.tool.*` - fourteen keys, copied from `en.ts:1284-1311`.
 *
 * The card shows these at the moment a tool asks for permission, so a wrong title would name the
 * wrong thing while the reader is deciding whether to allow it. The assertions are literal
 * strings on purpose: if a future edit drifts from the bundle, this fails.
 */
class PermissionCopyTest {

    @Test
    fun everyToolKeyIsTheActionTheServerSends() {
        val expected = listOf(
            "read", "edit", "glob", "grep", "list", "bash", "task",
            "skill", "lsp", "todowrite", "webfetch", "websearch",
            "external_directory", "doom_loop",
        )
        assertEquals(expected, PermissionCopy.tools.map { it.key })
    }

    @Test
    fun titlesAndDescriptionsAreVerbatim() {
        assertEquals("Read", PermissionCopy.title("read"))
        assertEquals("Reading a file (matches the file path)", PermissionCopy.description("read"))
        assertEquals("Edit", PermissionCopy.title("edit"))
        assertEquals(
            "Modify files, including edits, writes, and patches",
            PermissionCopy.description("edit"),
        )
        assertEquals("Bash", PermissionCopy.title("bash"))
        assertEquals("Run shell commands", PermissionCopy.description("bash"))
        assertEquals("Todo Write", PermissionCopy.title("todowrite"))
        assertEquals("Update the todo list", PermissionCopy.description("todowrite"))
        assertEquals("External Directory", PermissionCopy.title("external_directory"))
        assertEquals(
            "Access files outside the project directory",
            PermissionCopy.description("external_directory"),
        )
        assertEquals("Doom Loop", PermissionCopy.title("doom_loop"))
        assertEquals(
            "Detect repeated tool calls with identical input",
            PermissionCopy.description("doom_loop"),
        )
    }

    /** Every tool has both halves - a card with a title and no line would look truncated. */
    @Test
    fun everyToolHasATitleAndADescription() {
        for (t in PermissionCopy.tools) {
            assertTrue("${t.key} title blank", t.title.isNotBlank())
            assertTrue("${t.key} description blank", t.description.isNotBlank())
            assertNotNull(PermissionCopy.find(t.key))
        }
        val keys = PermissionCopy.tools.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    /**
     * An action we have never seen is shown raw and given no invented description. That is the
     * whole honesty rule of this file: name what was asked, do not translate what you do not know.
     */
    @Test
    fun unknownActionIsShownRawAndUndescribed() {
        assertNull(PermissionCopy.find("some_future_tool"))
        assertEquals("some_future_tool", PermissionCopy.title("some_future_tool"))
        assertNull(PermissionCopy.description("some_future_tool"))
        assertEquals("", PermissionCopy.title(""))
    }

    @Test
    fun headingIsTheirNotificationTitle() {
        assertEquals("Permission required", PermissionCopy.HEADING)
    }

    /**
     * The palette's command label and the toast title are different strings in the bundle - one
     * names the action, the other reports what happened. Conflating them would read as a typo.
     */
    @Test
    fun commandLabelsDifferFromToastTitles() {
        assertEquals("Auto-accept permissions", PermissionCopy.COMMAND_ENABLE)
        assertEquals("Stop auto-accepting permissions", PermissionCopy.COMMAND_DISABLE)
        assertEquals("Auto-accepting permissions", PermissionCopy.toastTitle(true))
        assertEquals("Stopped auto-accepting permissions", PermissionCopy.toastTitle(false))
        assertFalse(PermissionCopy.COMMAND_ENABLE == PermissionCopy.toastTitle(false))
        assertFalse(PermissionCopy.COMMAND_DISABLE == PermissionCopy.toastTitle(true))
    }

    @Test
    fun toastDescriptionsAreTheTwoOutcomes() {
        assertEquals(
            "Permission requests will be automatically approved",
            PermissionCopy.toastDescription(true),
        )
        assertEquals(
            "Permission requests will require approval",
            PermissionCopy.toastDescription(false),
        )
    }

    // ---- the card body, ported from `route-9_N8wbSw.js` ----

    @Test
    fun decisionsAreTheirThreeLabels() {
        assertEquals("Deny", PermissionCopy.DENY)
        assertEquals("Allow always", PermissionCopy.ALLOW_ALWAYS)
        assertEquals("Allow once", PermissionCopy.ALLOW_ONCE)
    }

    /**
     * Their `permission-input` rows. `JSON.stringify` is the formatter, so a string primitive is
     * used raw and everything else keeps its JSON shape.
     */
    @Test
    fun inputLinesFormatLikeJsonStringify() {
        val metadata = buildJsonObject {
            put(
                "input",
                buildJsonObject {
                    put("filePath", JsonPrimitive("/sdcard/app/Main.kt"))
                    put("offset", JsonPrimitive(42))
                    put("limit", JsonPrimitive(true))
                    put("nested", buildJsonObject { put("a", JsonPrimitive(1)) })
                    put("list", buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(2)) })
                    put("nothing", JsonNull)
                },
            )
        }
        assertEquals(
            listOf(
                "filePath: /sdcard/app/Main.kt",
                "offset: 42",
                "limit: true",
                "nested: {\"a\":1}",
                "list: [1,2]",
                "nothing: null",
            ),
            PermissionCopy.inputLines(metadata),
        )
    }

    /** A string keeps no quotes, which is exactly what `JSON.stringify` would have added. */
    @Test
    fun aStringValueIsNotQuoted() {
        val metadata = buildJsonObject {
            put("input", buildJsonObject { put("command", JsonPrimitive("ls -la")) })
        }
        assertEquals(listOf("command: ls -la"), PermissionCopy.inputLines(metadata))
    }

    @Test
    fun keyOrderIsTheServersOrder() {
        val metadata = buildJsonObject {
            put(
                "input",
                buildJsonObject {
                    put("z", JsonPrimitive(1))
                    put("a", JsonPrimitive(2))
                    put("m", JsonPrimitive(3))
                },
            )
        }
        assertEquals(listOf("z: 1", "a: 2", "m: 3"), PermissionCopy.inputLines(metadata))
    }

    @Test
    fun noMetadataMeansNoRows() {
        assertEquals(emptyList<String>(), PermissionCopy.inputLines(null))
        assertEquals(emptyList<String>(), PermissionCopy.inputLines(buildJsonObject { }))
    }

    @Test
    fun metadataWithoutAnObjectInputMeansNoRows() {
        assertEquals(
            emptyList<String>(),
            PermissionCopy.inputLines(buildJsonObject { put("input", JsonPrimitive("not an object")) }),
        )
        assertEquals(
            emptyList<String>(),
            PermissionCopy.inputLines(buildJsonObject { put("other", JsonPrimitive(1)) }),
        )
    }
}
