package com.zapp.net.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * OpenCode's own glyphs, path for path.
 *
 * Source of truth: `ui/src/icons/catalog.ts` in the desktop bundle. Every entry there is a
 * 16x16 (or 20x20) viewBox with `stroke="currentColor"` and no explicit stroke-width, so the
 * stroke here is 1 unit on a 16 unit viewport - the same weight the desktop draws.
 *
 * These replace the Material lookalikes (Search/Add/Menu/ArrowUpward/Stop) that were only
 * approximately the same shape.
 */
object OcIcons {

    private class Spec(
        val d: String,
        val filled: Boolean = false,
        val cap: StrokeCap = StrokeCap.Butt,
        val join: StrokeJoin = StrokeJoin.Miter,
        /** PC draws some glyph halves at 50% (e.g. `split`'s two panes). */
        val alpha: Float = 1f,
    )

    private fun stroke(d: String, cap: StrokeCap = StrokeCap.Butt, join: StrokeJoin = StrokeJoin.Miter) =
        Spec(d, filled = false, cap = cap, join = join)

    private fun fill(d: String) = Spec(d, filled = true)

    private fun fillHalf(d: String) = Spec(d, filled = true, alpha = 0.5f)

    private fun build(name: String, size: Float, specs: List<Spec>): ImageVector {
        val b = ImageVector.Builder(
            name = name,
            defaultWidth = size.dp,
            defaultHeight = size.dp,
            viewportWidth = size,
            viewportHeight = size,
        )
        specs.forEach { s ->
            b.addPath(
                pathData = PathParser().parsePathString(s.d).toNodes(),
                fill = if (s.filled) SolidColor(Color.Black) else null,
                stroke = if (s.filled) null else SolidColor(Color.Black),
                strokeLineWidth = 1f,
                strokeLineCap = s.cap,
                strokeLineJoin = s.join,
                fillAlpha = s.alpha,
                strokeAlpha = s.alpha,
            )
        }
        return b.build()
    }

    /** `clock` - the history control's glyph (`ui.promptInput.history`). */
    val Clock: ImageVector by lazy {
        build(
            "oc.clock", 16f,
            listOf(
                stroke("M8 1.5a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13", StrokeCap.Round, StrokeJoin.Round),
                stroke("M8 4.6V8.2L10.6 9.8", StrokeCap.Round, StrokeJoin.Round),
            ),
        )
    }

    /** `magnifying-glass` */
    val Search: ImageVector by lazy {
        build(
            "oc.magnifying-glass", 16f,
            listOf(
                stroke(
                    "M11.5 7C11.5 9.48528 9.48528 11.5 7 11.5C4.51472 11.5 2.5 9.48528 2.5 7" +
                        "C2.5 4.51472 4.51472 2.5 7 2.5C9.48528 2.5 11.5 4.51472 11.5 7Z",
                ),
                stroke("M13.5 13.5L10.5 10.5", StrokeCap.Round),
            ),
        )
    }

    /** `plus` */
    val Plus: ImageVector by lazy {
        build(
            "oc.plus", 16f,
            listOf(stroke("M8 2.5V8M8 8V13.5M8 8H2.5M8 8H13.5", StrokeCap.Round, StrokeJoin.Round)),
        )
    }

    /** `menu` */
    val Menu: ImageVector by lazy {
        build("oc.menu", 16f, listOf(stroke("M2 8.00006H14M2 4.5H14M2 11.5H14")))
    }

    /** `arrow-up` - the send glyph */
    val ArrowUp: ImageVector by lazy {
        build(
            "oc.arrow-up", 16f,
            listOf(stroke("M8 12.5V3.5M11.5 7L8 3.5L4.5 7", StrokeCap.Round, StrokeJoin.Round)),
        )
    }

    /** `stop` - filled square */
    val Stop: ImageVector by lazy {
        build("oc.stop", 16f, listOf(fill("M13 3H3V13H13V3Z")))
    }

    /** `outline-dots` - the row overflow trigger */
    val Dots: ImageVector by lazy {
        build(
            "oc.outline-dots", 16f,
            listOf(
                fill(
                    "M5 8C5 8.55228 4.55228 9 4 9C3.44772 9 3 8.55228 3 8C3 7.44772 3.44772 7 4 7" +
                        "C4.55228 7 5 7.44772 5 8Z",
                ),
                fill(
                    "M9 8C9 8.55228 8.55228 9 8 9C7.44772 9 7 8.55228 7 8C7 7.44772 7.44772 7 8 7" +
                        "C8.55228 7 9 7.44772 9 8Z",
                ),
                fill(
                    "M13 8C13 8.55228 12.5523 9 12 9C11.4477 9 11 8.55228 11 8C11 7.44772 11.4477 7 12 7" +
                        "C12.5523 7 13 7.44772 13 8Z",
                ),
            ),
        )
    }

    /** `close` */
    val Close: ImageVector by lazy {
        build(
            "oc.close", 16f,
            listOf(stroke("M4.5 11.5L8 8M8 8L11.5 4.5M8 8L4.5 4.5M8 8L11.5 11.5", StrokeCap.Round)),
        )
    }

    /** `fill-triangle-down` - the dropdown marker */
    val TriangleDown: ImageVector by lazy {
        build(
            "oc.fill-triangle-down", 16f,
            listOf(
                fill(
                    "M5.37624 6.75194C5.18184 6.41861 5.42224 6 5.80814 6H10.1921C10.578 6" +
                        "10.8184 6.41861 10.624 6.75194L8.43203 10.5096C8.23909 10.8404 7.76119 10.8404" +
                        "7.56825 10.5096L5.37624 6.75194Z",
                ),
            ),
        )
    }

    /** `chevron-down`, with the catalog's `rotate(180 8 8)` worked into the coordinates. */
    val ChevronDown: ImageVector by lazy {
        build(
            "oc.chevron-down", 16f,
            listOf(stroke("M11.5 6.5L8 9.5L4.5 6.5", StrokeCap.Round, StrokeJoin.Round)),
        )
    }

    /**
     * `chevrons-up-down` - the glyph PC puts at the inline end of a `file-counts` row
     * (`Gp`/`sb` template in `route-BPCnB8DM.js`: filename, `diff-changes`, then this control) so
     * the row reads as "expand both ways". It is two chevrons, not one rotated chevron: a single
     * arrow would say "collapse", which is the wrong affordance on a card that also opens its
     * patch downward.
     */
    val ChevronsUpDown: ImageVector by lazy {
        build(
            "oc.chevrons-up-down", 16f,
            listOf(
                stroke("M4.5 6L8 2.5L11.5 6", StrokeCap.Round, StrokeJoin.Round),
                stroke("M4.5 10L8 13.5L11.5 10", StrokeCap.Round, StrokeJoin.Round),
            ),
        )
    }

    /** `archive` */
    val Archive: ImageVector by lazy {
        build(
            "oc.archive", 16f,
            listOf(
                stroke(
                    "M2.5 5.5V13.5H13.5V5.5M2.5 5.5H1.5V2.5H14.5V5.5H13.5M2.5 5.5H13.5M6.5 7.5H9.5",
                    StrokeCap.Round, StrokeJoin.Round,
                ),
            ),
        )
    }

    /** `edit` */
    val Edit: ImageVector by lazy {
        build(
            "oc.edit", 16f,
            listOf(
                stroke(
                    "M8.5 2.5H2.5V13.5H13.5V7.5M7.5 8.5V6.5L12.5 1.5L14.5 3.5L9.5 8.5H7.5Z",
                    StrokeCap.Round, StrokeJoin.Round,
                ),
            ),
        )
    }

    /** `trash` */
    val Trash: ImageVector by lazy {
        build(
            "oc.trash", 16f,
            listOf(
                stroke(
                    "M5.5 4.5H2.5L4 14.5H12L13.5 4.5H10.5M5.5 4.5V1.5H10.5V4.5M5.5 4.5H10.5" +
                        "M6.5 11.5L6 7M9.5 11.5L10 7",
                    StrokeCap.Round, StrokeJoin.Round,
                ),
            ),
        )
    }

    /** `folder` */
    val Folder: ImageVector by lazy {
        build(
            "oc.folder", 16f,
            listOf(stroke("M1.5 13.5V2.5H5.5L8.5 5.5H14.5V13.5H1.5Z", StrokeCap.Round, StrokeJoin.Round)),
        )
    }

    /** `branch` - circles written as arcs so one stroke path carries the whole glyph */
    val Branch: ImageVector by lazy {
        build(
            "oc.branch", 16f,
            listOf(
                stroke("M6.5 12a1.5 1.5 0 1 0-3 0a1.5 1.5 0 1 0 3 0Z"),
                stroke(
                    "M5.22983 10.4384V5.43842M6.55305 11.8019H8.83276C9.94392 11.8019 10.8421 10.8962" +
                        "10.8327 9.78506L10.7966 5.50012",
                    StrokeCap.Round, StrokeJoin.Round,
                ),
                stroke("M6.5 4a1.5 1.5 0 1 0-3 0a1.5 1.5 0 1 0 3 0Z"),
                stroke("M12.5 4a1.5 1.5 0 1 0-3 0a1.5 1.5 0 1 0 3 0Z"),
            ),
        )
    }

    /** `settings-gear` */
    val Gear: ImageVector by lazy {
        build(
            "oc.settings-gear", 16f,
            listOf(
                stroke(
                    "M1.5 11V5L8 1.5L14.5 5V11L8 14.5L1.5 11Z",
                    StrokeCap.Round, StrokeJoin.Round,
                ),
                stroke(
                    "M10.5 8C10.5 9.38071 9.38071 10.5 8 10.5C6.61929 10.5 5.5 9.38071 5.5 8" +
                        "C5.5 6.61929 6.61929 5.5 8 5.5C9.38071 5.5 10.5 6.61929 10.5 8Z",
                ),
            ),
        )
    }

    /** `copy` */
    val Copy: ImageVector by lazy {
        build(
            "oc.copy", 16f,
            listOf(
                stroke("M9.5 3.5V1.5H1.5V10.5H4.5", StrokeCap.Round, StrokeJoin.Round),
                stroke("M6.5 14.5V5.5H14.5V14.5H6.5Z", StrokeCap.Round, StrokeJoin.Round),
            ),
        )
    }

    /** `check` */
    val Check: ImageVector by lazy {
        build(
            "oc.check", 16f,
            listOf(stroke("M4.5 7.5L7 11.5L11.5 4.5", StrokeCap.Round, StrokeJoin.Round)),
        )
    }

    /** `terminal` */
    val Terminal: ImageVector by lazy {
        build(
            "oc.terminal", 16f,
            listOf(
                stroke("M4.5 11.5L7.5 8.5L4.5 5.5M11.5 11.5H8.5", StrokeCap.Round, StrokeJoin.Round),
                stroke(
                    "M1.5 14.25V1.75C1.5 1.61193 1.61193 1.5 1.75 1.5H14.25C14.3881 1.5 14.5 1.61193" +
                        "14.5 1.75V14.25C14.5 14.3881 14.3881 14.5 14.25 14.5H1.75C1.61193 14.5 1.5 14.3881" +
                        "1.5 14.25Z",
                    StrokeCap.Round,
                ),
            ),
        )
    }

    /** `file-document` */
    val FileDocument: ImageVector by lazy {
        build(
            "oc.file-document", 16f,
            listOf(
                stroke(
                    "M3.5 13.25V2.75C3.5 2.61193 3.61193 2.5 3.75 2.5H8.39645C8.46275 2.5 8.52634 2.52634" +
                        " 8.57322 2.57322L12.4268 6.42678C12.4737 6.47366 12.5 6.53725 12.5 6.60355V13.25" +
                        "C12.5 13.3881 12.3881 13.5 12.25 13.5H3.75C3.61193 13.5 3.5 13.3881 3.5 13.25Z",
                    StrokeCap.Round,
                ),
            ),
        )
    }

    /**
     * The five source glyphs the owner picked out of Android's own file picker.
     *
     * His words: "I LOVE THE ICON OF THOSE CAN YOU REPLACE THOSE ICON TO THE + AND ADD A TEXT".
     * They are drawn here rather than pulled from the system so they stay monochrome and the
     * same weight as every other glyph in the app - a colourful DocumentsUI tile would be the
     * only colour in the composer.
     */

    /** `Files` - the tray with a down arrow, the shape Android uses for Downloads. */
    val Files: ImageVector by lazy {
        build(
            "oc.files", 16f,
            listOf(
                stroke(
                    "M8 2.25V9.6M5.15 6.85 8 9.7l2.85-2.85M2.6 11.4v1.35c0 .414.336.75.75.75h9.3" +
                        "c.414 0 .75-.336.75-.75V11.4",
                    StrokeCap.Round,
                    StrokeJoin.Round,
                ),
            ),
        )
    }

    /** `Images` - frame, horizon and a sun. */
    val Images: ImageVector by lazy {
        build(
            "oc.images", 16f,
            listOf(
                stroke(
                    "M2.5 4.1c0-.608.492-1.1 1.1-1.1h8.8c.608 0 1.1.492 1.1 1.1v7.8" +
                        "c0 .608-.492 1.1-1.1 1.1H3.6c-.608 0-1.1-.492-1.1-1.1V4.1Z",
                    StrokeCap.Round,
                    StrokeJoin.Round,
                ),
                stroke(
                    "M2.9 11.2 5.9 8.2l2.1 2.1 1.5-1.5 3.6 3.4",
                    StrokeCap.Round,
                    StrokeJoin.Round,
                ),
                stroke(
                    "M10.35 5.35a1.05 1.05 0 1 1-2.1 0 1.05 1.05 0 0 1 2.1 0Z",
                    StrokeCap.Round,
                    StrokeJoin.Round,
                ),
            ),
        )
    }

    /** `Audio` - a quaver, two heads and one stem. */
    val Audio: ImageVector by lazy {
        build(
            "oc.audio", 16f,
            listOf(
                stroke("M6.1 11.6V4.35l6.1-1.2v7.05", StrokeCap.Round, StrokeJoin.Round),
                stroke(
                    "M6.1 12.35a1.45 1.45 0 1 1-2.9 0 1.45 1.45 0 0 1 2.9 0Z",
                    StrokeCap.Round,
                    StrokeJoin.Round,
                ),
                stroke(
                    "M12.2 10.2a1.45 1.45 0 1 1-2.9 0 1.45 1.45 0 0 1 2.9 0Z",
                    StrokeCap.Round,
                    StrokeJoin.Round,
                ),
            ),
        )
    }

    /** `Videos` - a frame with a play triangle, the shape Android uses for video. */
    val Videos: ImageVector by lazy {
        build(
            "oc.videos", 16f,
            listOf(
                stroke(
                    "M2.5 4.1c0-.608.492-1.1 1.1-1.1h8.8c.608 0 1.1.492 1.1 1.1v7.8" +
                        "c0 .608-.492 1.1-1.1 1.1H3.6c-.608 0-1.1-.492-1.1-1.1V4.1Z",
                    StrokeCap.Round,
                    StrokeJoin.Round,
                ),
                fill("M6.7 5.75 10.4 8l-3.7 2.25V5.75Z"),
            ),
        )
    }

    /** `grid-plus` - the new-tab glyph in the titlebar */    val GridPlus: ImageVector by lazy {
        build(
            "oc.grid-plus", 16f,
            listOf(
                stroke(
                    "M13.9948 11.668H9.32812M11.6641 9.33203V13.9987M6.66667 9.33203V13.9987H2V9.33203" +
                        "H6.66667ZM6.66667 2V6.66667H2V2H6.66667ZM13.9948 2V6.66667H9.32812V2H13.9948Z",
                    StrokeCap.Butt, StrokeJoin.Miter,
                ),
            ),
        )
    }

    /** `outline-worktree` - the project row marker */
    val Worktree: ImageVector by lazy {
        build(
            "oc.outline-worktree", 16f,
            listOf(
                stroke(
                    "M1.5 11.5H5L9.5 5.5H14.5M12 8L14.5 5.5L12 3M9 11.5H14.5",
                    StrokeCap.Round, StrokeJoin.Round,
                ),
            ),
        )
    }

    /** `split` - PC's side-panel glyph: a framed box holding two 50% panes. */
    val Split: ImageVector by lazy {
        build(
            "oc.split", 16f,
            listOf(
                stroke("M14.5 13.5H1.5V2.5H14.5V13.5Z", StrokeCap.Round, StrokeJoin.Round),
                fillHalf("M3 4H7V12H3Z"),
                fillHalf("M9 4H13V12H9Z"),
            ),
        )
    }

    /** `globe` - the browser tab glyph (`catalog.ts` @ 381). */
    val Globe: ImageVector by lazy {
        build(
            "oc.globe", 16f,
            listOf(
                stroke("M14.5 8C14.5 11.5899 11.5899 14.5 8 14.5C4.41015 14.5 1.5 11.5899 1.5 8C1.5 4.41015 4.41015 1.5 8 1.5C11.5899 1.5 14.5 4.41015 14.5 8Z"),
                stroke("M1.5 8H14.5", StrokeCap.Round, StrokeJoin.Round),
                stroke(
                    "M8 1.5C6.5 3 5.5 5.5 5.5 8C5.5 10.5 6.5 13 8 14.5C9.5 13 10.5 10.5 10.5 8" +
                        "C10.5 5.5 9.5 3 8 1.5Z",
                    StrokeCap.Round, StrokeJoin.Round,
                ),
            ),
        )
    }

    /** `arrow-left` - `arrow-up`'s path rotated 270 degrees about (8,8), the g-transform flattened. */
    val ArrowLeft: ImageVector by lazy {
        build(
            "oc.arrow-left", 16f,
            listOf(stroke("M12.5 8H3.5M7 4.5L3.5 8L7 11.5", StrokeCap.Round, StrokeJoin.Round)),
        )
    }

    /** `arrow-right` - the same path rotated 90 degrees. */
    val ArrowRight: ImageVector by lazy {
        build(
            "oc.arrow-right", 16f,
            listOf(stroke("M3.5 8H12.5M9 4.5L12.5 8L9 11.5", StrokeCap.Round, StrokeJoin.Round)),
        )
    }

    /**
     * `download` - their catalog entry, 16x16, two paths both `round`-capped and `round`-joined:
     * the arrow shaft and the tray.
     */
    val Download: ImageVector by lazy {
        build(
            "oc.download", 16f,
            listOf(
                stroke("M8 2.5L8 10.5M4.5 7L8 10.5L11.5 7", StrokeCap.Round, StrokeJoin.Round),
                stroke("M1.5 10.5V13.5H14.5V10.5", StrokeCap.Round, StrokeJoin.Round),
            ),
        )
    }

    /** `chevron-right` - `chevron-down` rotated 90 degrees. */
    val ChevronRight: ImageVector by lazy {        build(
            "oc.chevron-right", 16f,
            listOf(stroke("M6.5 4.5L9.5 8L6.5 11.5", StrokeCap.Round, StrokeJoin.Round)),
        )
    }

    /** `outline-rotate-clockwise` - the browser bar's reload (`catalog.ts` @ 469). */
    val Rotate: ImageVector by lazy {
        build(
            "oc.rotate", 16f,
            listOf(
                stroke(
                    "M14.1992 8C14.1992 9.18669 13.8473 10.3467 13.188 11.3334C12.5288 12.3201 11.5917 13.0892 " +
                        "10.4953 13.5433C9.39897 13.9974 8.19257 14.1162 7.02868 13.8847C5.86479 13.6532 5.7957 " +
                        "13.0818 3.95658 12.2426C3.11747 11.4035 2.54602 10.3344 2.31451 9.17054C2.083 8.00666 " +
                        "2.20182 6.80026 2.65594 5.7039C3.11007 4.60754 3.87911 3.67047 4.8658 3.01118C5.85249 " +
                        "2.35189 7.01253 2 8.19922 2C9.87922 2 11.4859 2.66667 12.6926 3.82667L14.1992 5.33333" +
                        "M10.866 5.33333L14.1992 5.33333L14.1993 2",
                    StrokeCap.Square, StrokeJoin.Round,
                ),
            ),
        )
    }

    /** `outline-square-arrow` - "open in system browser" (`catalog.ts`). */
    val SquareArrow: ImageVector by lazy {
        build(
            "oc.square-arrow", 16f,
            listOf(
                stroke(
                    "M5.5 2.5H2.5V13.5H13.5V10.5M8.5 7.5L13.5 2.5M13.5 7.5V2.5H8.5",
                    StrokeCap.Round, StrokeJoin.Round,
                ),
            ),
        )
    }

    /** `window-analytics` - PC's session-details trigger (`details/renderer.tsx`, `session.header` order 20). */
    val WindowAnalytics: ImageVector by lazy {
        build(
            "oc.window-analytics", 16f,
            listOf(
                stroke(
                    "M6.5 2.5H1.5V13.5H14.5V10.5M9.5 2.5H14.5V7.5H9.5V2.5Z",
                    StrokeCap.Round, StrokeJoin.Round,
                ),
            ),
        )
    }

    /**
     * `status` - the Context panel's glyph.
     *
     * `context/renderer.tsx` registers the panel with `icon:"status"`, and its `closed` handler
     * returns the same name. Straight from `catalog.ts`: viewBox 20x20, one stroked path.
     */
    val Status: ImageVector by lazy {
        build(
            "oc.status", 20f,
            listOf(
                stroke("M2 10V18H18V10M2 10V2H18V10M2 10H18M5 6H9M5 14H9"),
            ),
        )
    }

    /**
     * `mcp` - the Extensions panel's MCP row and `Manage MCP servers`.
     *
     * `details/server-panel.tsx:74` names it, and `catalog.ts` draws it on a 20 viewBox as three
     * round-capped strokes. Not the two-lines-and-dots approximation this file used to hand-draw.
     */
    val Mcp: ImageVector by lazy {
        build(
            "oc.mcp", 20f,
            listOf(
                stroke(
                    "M0.972656 9.37176L9.5214 1.60019C10.7018 0.527151 12.6155 0.527151 13.7957 1.60019" +
                        "C14.9761 2.67321 14.9761 4.41295 13.7957 5.48599L7.3397 11.3552",
                    StrokeCap.Round,
                ),
                stroke(
                    "M7.42871 11.2747L13.7957 5.48643C14.9761 4.41338 16.8898 4.41338 18.0702 5.48643" +
                        "L18.1147 5.52688C19.2951 6.59993 19.2951 8.33966 18.1147 9.4127L10.3831 16.4414" +
                        "C9.98966 16.7991 9.98966 17.379 10.3831 17.7366L11.9707 19.1799",
                    StrokeCap.Round,
                ),
                stroke(
                    "M11.6587 3.54346L5.33619 9.29119C4.15584 10.3642 4.15584 12.1039 5.33619 13.177" +
                        "C6.51649 14.25 8.43019 14.25 9.61054 13.177L15.9331 7.42923",
                    StrokeCap.Round,
                ),
            ),
        )
    }

    /** `cube` - the Extensions panel's Plugins row (`server-panel.tsx:75`). */
    val Cube: ImageVector by lazy {
        build(
            "oc.cube", 16f,
            listOf(stroke("M8 14.5L14.5 11V5L8 1.5L1.5 5V11L8 14.5ZM1.5 5L8 8.5M14.5 5L8 8.5M8 8.5V14.5", StrokeCap.Butt, StrokeJoin.Round)),
        )
    }

    /** `post-skill` - the Extensions panel's Skills row (`server-panel.tsx:76`). */
    val PostSkill: ImageVector by lazy {
        build(
            "oc.post-skill", 16f,
            listOf(
                stroke(
                    "M3 2.5C3.5 2.5 4.5 2.8 4.5 4L4.5 5.5V12C4.5 12.5 4.8 13.5 6 13.5M6 13.5" +
                        "C7.2 13.5 7.5 12.5 7.5 12V10.5H14.5V12C14.5 12.5 14.2 13.5 13 13.5H6ZM4.5 5.5" +
                        "H1.5V4C1.5 3.5 1.8 2.5 3 2.5M12.5 8.5V4.5C12.5 3.39543 11.6046 2.5 10.5 2.5H3",
                    StrokeCap.Round, StrokeJoin.Round,
                ),
                stroke("M6.5 5.5H10.5M6.5 7.5H9", StrokeCap.Butt, StrokeJoin.Round),
            ),
        )
    }

    /** `review` - PC's review-tab glyph: a heading rule with a plus rising off it. */
    val Review: ImageVector by lazy {
        build(
            "oc.review", 16f,
            listOf(
                stroke(
                    "M4.5 6.5H8M8 6.5H11.5M8 6.5V3M8 6.5V10",
                    StrokeCap.Round, StrokeJoin.Round,
                ),
                stroke("M4.5 12.5H11.5", StrokeCap.Round, StrokeJoin.Round),
            ),
        )
    }

    /** `chevron-grabber-vertical` - the review header's Expand all / Collapse all button. */
    val ChevronGrabber: ImageVector by lazy {
        build(
            "oc.chevron-grabber-vertical", 16f,
            listOf(stroke("M5 6L8 3.5L11 6M5 10L8 12.5L11 10", StrokeCap.Round, StrokeJoin.Round)),
        )
    }

    /** `open-file` - the diff summary's "Open file" button. */
    val OpenFile: ImageVector by lazy {
        build(
            "oc.open-file", 20f,
            listOf(
                stroke(
                    "M7.91602 2.91406H2.91602V17.0807H17.0827V12.0807M12.0827 2.91406H17.0827V7.91406" +
                        "M9.58268 10.4141L16.666 3.33073",
                    StrokeCap.Square,
                ),
            ),
        )
    }

    // ---- the Settings sidebar, one glyph per page ------------------------------------------
    //
    // Paths read out of `ui/src/icons/catalog.ts`, the same catalogue the rest of this file
    // came from. A body that mixes `fill="currentColor"` and `stroke="currentColor"` is split
    // into one [Spec] per kind, because a single path cannot be both here.

    /** `sliders` - Preferences. */
    val Sliders: ImageVector by lazy {
        build(
            "oc.sliders", 16f,
            listOf(
                stroke(
                    "M7.5 11.5C7.5 12.6046 6.60457 13.5 5.5 13.5C4.39543 13.5 3.5 12.6046 3.5 11.5" +
                        "C3.5 10.3954 4.39543 9.5 5.5 9.5C6.60457 9.5 7.5 10.3954 7.5 11.5Z",
                ),
                stroke(
                    "M12.5 4.5C12.5 5.60457 11.6046 6.5 10.5 6.5C9.39543 6.5 8.5 5.60457 8.5 4.5" +
                        "C8.5 3.39543 9.39543 2.5 10.5 2.5C11.6046 2.5 12.5 3.39543 12.5 4.5Z",
                ),
                fill(
                    "M8.0498 4C8.01702 4.16154 8 4.32878 8 4.5C8 4.67122 8.01702 4.83846 8.0498 5" +
                        "H1.5C1.22386 5 1 4.77614 1 4.5C1 4.22386 1.22386 4 1.5 4H8.0498ZM14.5 4" +
                        "C14.7761 4 15 4.22386 15 4.5C15 4.77614 14.7761 5 14.5 5H12.9502" +
                        "C12.983 4.83846 13 4.67122 13 4.5C13 4.32878 12.983 4.16154 12.9502 4H14.5Z",
                ),
                fill(
                    "M3.0498 11C3.01702 11.1615 3 11.3288 3 11.5C3 11.6712 3.01702 11.8385 3.0498 12" +
                        "H1.5C1.22386 12 1 11.7761 1 11.5C1 11.2239 1.22386 11 1.5 11H3.0498ZM14.5 11" +
                        "C14.7761 11 15 11.2239 15 11.5C15 11.7761 14.7761 12 14.5 12H7.9502" +
                        "C7.98298 11.8385 8 11.6712 8 11.5C8 11.3288 7.98298 11.1615 7.9502 11H14.5Z",
                ),
            ),
        )
    }

    /** `appearance` - the diagonal-stripe brush. */
    val Appearance: ImageVector by lazy {
        build(
            "oc.appearance", 16f,
            listOf(
                stroke(
                    "M1.5 4.5L4.5 1.5M1.5 14.5L14.5 1.5M11.5 14.5L14.5 11.5M1.5 9.5L9.5 1.5" +
                        "M6.5 14.5L14.5 6.5",
                    StrokeCap.Round,
                ),
            ),
        )
    }

    /** `notifications` - a monitor with a dot, plus the stand. */
    val Notifications: ImageVector by lazy {
        build(
            "oc.notifications", 16f,
            listOf(
                stroke("M4.5 14.5H11.5", StrokeCap.Round),
                stroke("M8 12V14", StrokeCap.Square),
                fill(
                    "M16 3.5C16 4.88071 14.8807 6 13.5 6C12.1193 6 11 4.88071 11 3.5" +
                        "C11 2.11929 12.1193 1 13.5 1C14.8807 1 16 2.11929 16 3.5Z",
                ),
                fill(
                    "M10.3369 2C10.1892 2.31084 10.0866 2.64695 10.0361 3H2V11H14V6.96387" +
                        "C14.3531 6.91336 14.6892 6.81075 15 6.66309V11.5C15 11.7761 14.7761 12 14.5 12" +
                        "H1.5C1.22386 12 1 11.7761 1 11.5V2.5C1 2.22386 1.22386 2 1.5 2H10.3369Z",
                ),
            ),
        )
    }

    /** `keyboard` - the up-arrow in a keycap. */
    val Keyboard: ImageVector by lazy {
        build(
            "oc.keyboard", 16f,
            listOf(stroke("M5.5 13.5H10.5V8.5H13.5L8 2.5L2.5 8.5H5.5V13.5Z", join = StrokeJoin.Round)),
        )
    }

    /** `providers` - the connector grid. */
    val Providers: ImageVector by lazy {
        build(
            "oc.providers", 16f,
            listOf(
                stroke(
                    "M13.5 4.5V2.5H11.5M13.5 4.5H15.5M13.5 4.5V8M13.5 8H15.5M13.5 8V8.5V11.5" +
                        "M13.5 11.5V13.5H11.5M13.5 11.5H15.5M2.5 8H0.5M2.5 8V4.5M2.5 8V11.5" +
                        "M2.5 4.5V2.5H4.5M2.5 4.5H0.5M2.5 11.5V13.5H4.5M2.5 11.5H0.5M4.5 13.5V15.5" +
                        "M4.5 13.5H8M8 13.5V15.5M8 13.5H11.5M11.5 13.5V15.5M11.5 2.5V0.5M11.5 2.5H8" +
                        "M8 2.5V0.5M8 2.5H4.5M4.5 2.5V0.5M5.5 10.5V5.5H10.5V10.5H5.5Z",
                    StrokeCap.Round,
                    StrokeJoin.Round,
                ),
            ),
        )
    }

    /** `models` - the four-point star. */
    val Models: ImageVector by lazy {
        build(
            "oc.models", 16f,
            listOf(
                stroke(
                    "M14 8C9.83336 8 8 9.83336 8 14C8 9.83336 6.16666 8 2 8C6.16666 8 8 6.16666 8 2" +
                        "C8 6.16666 9.83336 8 14 8Z",
                ),
            ),
        )
    }

    /** `extensions` - the panel with three ticks. */
    val Extensions: ImageVector by lazy {
        build(
            "oc.extensions", 16f,
            listOf(
                stroke("M1.5 2.5V13.5H14.5V2.5H1.5Z", StrokeCap.Round, StrokeJoin.Round),
                stroke("M4.5 6.5V9.5M8 6.5V9.5M11.5 6.5V9.5", StrokeCap.Round),
            ),
        )
    }

    /** `server` - the rack. */
    val Server: ImageVector by lazy {
        build(
            "oc.server", 16f,
            listOf(
                stroke(
                    "M1.5 10.5V14.5H14.5V10.5M1.5 10.5H14.5M14.5 10.5V6.5L11.5 1.5H4.5L1.5 6.5V10.5" +
                        "M1.5 6.5H14.5",
                    StrokeCap.Square,
                    StrokeJoin.Round,
                ),
                stroke("M12.5 12.5H11.5M12.5 8.5H11.5", StrokeCap.Round),
            ),
        )
    }

    /** `outline-pair` - a monitor with a phone in front of it. */
    val Pair: ImageVector by lazy {
        build(
            "oc.outline-pair", 16f,
            listOf(
                stroke(
                    "M2.5 9V2.5H13.5V3.5M2.5 9L0.5 12.5H7.5M2.5 9H7.5",
                    StrokeCap.Square,
                    StrokeJoin.Round,
                ),
                stroke(
                    "M11.5 12.5H13.5M9.5 5.5V14.5H15.5V5.5H9.5Z",
                    StrokeCap.Round,
                    StrokeJoin.Round,
                ),
            ),
        )
    }

    /** `flask` - Experimental. */
    val Flask: ImageVector by lazy {
        build(
            "oc.flask", 16f,
            listOf(
                stroke(
                    "M10.5 2.5V6.5L11.3571 8.5M10.5 2.5H5.5M10.5 2.5H12.5M5.5 2.5V6.5L4.21429 9.5" +
                        "M5.5 2.5H3.5M4.21429 9.5L2.5 13.5H13.5L11.3571 8.5M4.21429 9.5L11.3571 8.5",
                    StrokeCap.Round,
                    StrokeJoin.Round,
                ),
            ),
        )
    }

    /**
     * `info` - a circle with an `i`.
     *
     * Theirs is a filled square-with-i inside a `translate(2 2)` group, and [build] has no
     * groups; the circle reads the same at 16px and avoids a coordinate rewrite.
     */
    val Info: ImageVector by lazy {
        build(
            "oc.info", 16f,
            listOf(
                stroke("M14.5 8C14.5 11.5899 11.5899 14.5 8 14.5C4.41015 14.5 1.5 11.5899 1.5 8C1.5 4.41015 4.41015 1.5 8 1.5C11.5899 1.5 14.5 4.41015 14.5 8Z"),
                fill("M8.75 4.5V6H7.25V4.5H8.75ZM8.75 7V11.5H7.25V7H8.75Z"),
            ),
        )
    }

    /** `magnifying-glass` - the settings search field. */
    val Magnifier: ImageVector by lazy {
        build(
            "oc.magnifying-glass", 16f,
            listOf(
                stroke("M10.5 10.5L14.5 14.5", StrokeCap.Round),
                stroke("M11.5 6.5C11.5 9.26142 9.26142 11.5 6.5 11.5C3.73858 11.5 1.5 9.26142 1.5 6.5C1.5 3.73858 3.73858 1.5 6.5 1.5C9.26142 1.5 11.5 3.73858 11.5 6.5Z"),
            ),
        )
    }

    /**
     * `comment` - the tab bar's Chat glyph.
     *
     * the owner, 22 Nov: "THE ICONS". The tab bar was drawing `Icons.AutoMirrored.Filled.Chat`,
     * `Icons.Filled.Shield` and `Icons.Filled.Terminal` - three stock Material glyphs, drawn at
     * Material's own stroke weight and optical size. Next to the hand-built set above they read
     * as a different app, because they are a different app's icons. This is the catalogue's own
     * speech-bubble path, 16-unit viewBox, round caps and joins, exactly as the desktop draws it.
     */
    val Chat: ImageVector by lazy {
        build(
            "oc.comment", 16f,
            listOf(
                stroke("M1.5 2.5V10.5H5.5L8 13.5L10.5 10.5H14.5V2.5H1.5Z", StrokeCap.Round, StrokeJoin.Round),
            ),
        )
    }

    /**
     * `shield` - the Guard glyph, with its tick.
     *
     * This one is a 20-unit viewBox on the desktop, not 16, and it uses `stroke-linecap="square"`
     * with no join override - so a Miter join here is theirs, not a shortcut.
     */
    val Shield: ImageVector by lazy {
        build(
            "oc.shield", 20f,
            listOf(
                stroke(
                    "M7.49935 9.3737L9.16602 11.0404L12.4994 7.70703M9.99935 2.08203L17.0827 4.3737" +
                        "V9.92565C17.0827 14.0694 13.3327 16.2487 9.99935 18.047C6.66602 16.2487" +
                        "2.91602 14.0694 2.91602 9.92565V4.3737L9.99935 2.08203Z",
                    StrokeCap.Square,
                ),
            ),
        )
    }
}
