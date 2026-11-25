package com.zapp.net.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zapp.net.ui.theme.TextFaint

/**
 * The file-type mark a path gets.
 *
 * [chooseIconName] decides *which* icon it is (their table, verbatim). [iconResOf] then names the
 * drawable carrying their own SVG for that icon - all 231 of them were lifted out of the desktop
 * build's lazy chunks, so this is their artwork, their brand colours, at their own viewBox.
 *
 * `Document` is the only name with no drawable, because the build has no `document` symbol: the
 * eager fallback glob in `file-icon.tsx:174-183` inlines `folder` and `folder-open` and nothing
 * else. An unknown file therefore gets the plain page drawn below.
 */
@Composable
internal fun FileMarkBadge(
    path: String,
    isDirectory: Boolean = false,
    expanded: Boolean = false,
    box: Dp = 17.dp,
) {
    val icon = remember(path, isDirectory, expanded) {
        chooseIconName(path, isDirectory, expanded)
    }
    val res = remember(icon) { iconResOf(icon) }
    if (res != 0) {
        Image(
            painter = painterResource(res),
            contentDescription = null,
            modifier = Modifier.size(box),
        )
    } else {
        DocumentMark(box)
    }
}

/** The grey their `folder.svg` uses for `currentColor`, so the fallback page matches it. */
private val NeutralMark = TextFaint

/** A page with a folded corner. Only drawn when the build itself has no icon for the path. */
@Composable
private fun DocumentMark(box: Dp) {
    Canvas(Modifier.size(box)) {
        val u = size.minDimension / 16f
        val stroke = Stroke(width = 1.25f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val page = Path().apply {
            moveTo(4f * u, 1.5f * u)
            lineTo(9.6f * u, 1.5f * u)
            lineTo(12.5f * u, 4.4f * u)
            lineTo(12.5f * u, 14.5f * u)
            lineTo(4f * u, 14.5f * u)
            close()
        }
        drawPath(page, NeutralMark, style = stroke)
        drawPath(
            Path().apply {
                moveTo(9.6f * u, 1.5f * u)
                lineTo(9.6f * u, 4.4f * u)
                lineTo(12.5f * u, 4.4f * u)
            },
            NeutralMark,
            style = stroke,
        )
        drawRect(
            color = NeutralMark,
            topLeft = Offset(6f * u, 8f * u),
            size = Size(4.5f * u, 1.1f * u),
        )
        drawRect(
            color = NeutralMark,
            topLeft = Offset(6f * u, 10.6f * u),
            size = Size(3.2f * u, 1.1f * u),
        )
    }
}
