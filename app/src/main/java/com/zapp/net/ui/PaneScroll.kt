package com.zapp.net.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

/**
 * One horizontal scroll owner for a whole code body.
 *
 * `Modifier.horizontalScroll` put a scrollable on *every* row, so a finger that drifted sideways
 * was handed to the line under it: that line slid and the list stopped dead. the owner, 2026-11-24:
 * *"HINDI AKO MAKA SCROLL NG MAAYOS DAHIL DON SA LEFT AND RIGHT WHILE HOLDING"*, about Files,
 * Browser, Terminal, Open file and Review.
 *
 * Here the body owns a single offset. [codeHorizontalDrag] is the only node that listens for a
 * horizontal drag - and `detectHorizontalDragGestures` only starts after horizontal touch slop,
 * so a mostly-vertical drag is never consumed and the list keeps it. [row] shifts each line by
 * that one offset at draw time, which costs no layout pass.
 *
 * This is a plain drag rather than a `ScrollState` because `ScrollState.maxValue` is internal in
 * this Compose version: with `horizontalScroll` gone there is no measurement left to publish the
 * range, so the range is passed in and the offset is clamped here.
 */
internal class CodeScroll(contentPx: Float, viewportPx: Float) {

    private var max by mutableFloatStateOf(0f)
    var offset by mutableFloatStateOf(0f)
        private set

    /** Apply to every line instead of giving it a scroller of its own. */
    val row: Modifier
        get() = Modifier.graphicsLayer { translationX = -offset }

    fun setRange(content: Float, viewport: Float) {
        max = (content - viewport).coerceAtLeast(0f)
        if (offset > max) offset = max
    }

    fun dragBy(dx: Float) {
        offset = (offset - dx).coerceIn(0f, max)
    }

    init {
        setRange(contentPx, viewportPx)
    }
}

/**
 * [contentPx] is how wide the widest line is and [viewportPx] how much of that fits, so the
 * scroll range is their difference. Both are pixels: the caller already knows its density.
 */
@Composable
internal fun rememberCodeScroll(contentPx: Float, viewportPx: Float): CodeScroll {
    val scroll = remember { CodeScroll(contentPx, viewportPx) }
    // A new file, a new pane width or a re-measure moves the end of the content.
    LaunchedEffect(contentPx, viewportPx) { scroll.setRange(contentPx, viewportPx) }
    return scroll
}

/** The body's single horizontal gesture. Vertical deltas are left for the list to take. */
internal fun Modifier.codeHorizontalDrag(scroll: CodeScroll): Modifier =
    this.pointerInput(scroll) {
        detectHorizontalDragGestures { change, dragAmount ->
            change.consume()
            scroll.dragBy(dragAmount)
        }
    }
