package com.zapp.net.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.Surface2
import com.zapp.net.ui.theme.TextDim
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * The Compose half of `markdown-image.ts` — it runs [MarkdownImages]' effects against a reader.
 *
 * Their container-wide machine is synced with the set of paths currently in the DOM. A Compose
 * transcript draws one image per block, so the set is always a single path; the transition
 * table, the 15 s read timeout, the 1 s/2 s/4 s backoff and the three-retry cap are theirs
 * unchanged.
 */
@Stable
class MarkdownImageHost internal constructor(
    private val scope: CoroutineScope,
    private val read: suspend (String) -> ByteArray?,
) {

    var state: MarkdownImages.State by mutableStateOf(MarkdownImages.INITIAL)
        private set

    /** `url` in their model is a `blob:`/`data:` string; ours is a decoded bitmap. */
    private val bitmaps = mutableMapOf<String, ImageBitmap>()
    private val jobs = mutableMapOf<Int, Job>()

    fun bitmap(path: String): ImageBitmap? {
        val url = MarkdownImages.selectUrl(state, path) ?: return null
        return bitmaps[url]
    }

    fun failed(path: String): Boolean {
        val active = state as? MarkdownImages.State.Active ?: return true
        return active.entries[path] is MarkdownImages.Entry.Failed
    }

    fun sync(path: String) = dispatch(MarkdownImages.Event.PathsSynced(setOf(path)))

    fun dispose() = dispatch(MarkdownImages.Event.Disposed)

    private fun dispatch(event: MarkdownImages.Event) {
        val next = MarkdownImages.transition(state, event)
        if (next.state == state && next.effects.isEmpty()) return
        state = next.state
        next.effects.forEach(::run)
    }

    private fun run(effect: MarkdownImages.Effect) {
        when (effect) {
            // `:199` — `cancel`
            is MarkdownImages.Effect.Cancel -> {
                jobs.remove(effect.attempt)?.cancel()
            }
            // `:221` — `wait`
            is MarkdownImages.Effect.Wait -> {
                jobs[effect.attempt] = scope.launch {
                    delay(effect.delayMs.toLong())
                    dispatch(MarkdownImages.Event.RetryDue(effect.path, effect.attempt))
                }
            }
            // `:195` — `revoke-url`: a Compose bitmap needs no release, it is garbage collected.
            is MarkdownImages.Effect.RevokeUrl -> {
                bitmaps.remove(effect.url)
            }
            // `:235` — the read itself, with their timeout.
            is MarkdownImages.Effect.Read -> {
                jobs[effect.attempt] = scope.launch {
                    val bytes = try {
                        withTimeout(effect.timeoutMs.toLong()) { read(effect.path) }
                    } catch (_: Exception) {
                        null
                    }
                    if (bytes == null) {
                        // `read.timed-out` and `read.failed` take the same path in their machine.
                        dispatch(MarkdownImages.Event.ReadFailed(effect.path, effect.attempt))
                        return@launch
                    }
                    if (bytes.isEmpty()) {
                        dispatch(MarkdownImages.Event.ReadEmpty(effect.path, effect.attempt))
                        return@launch
                    }
                    val bmp = decode(bytes)
                    if (bmp == null) {
                        // A file that is not an image is a failed read, not an empty one.
                        dispatch(MarkdownImages.Event.ReadFailed(effect.path, effect.attempt))
                        return@launch
                    }
                    bitmaps[effect.path] = bmp
                    dispatch(
                        MarkdownImages.Event.ReadSucceeded(
                            path = effect.path,
                            attempt = effect.attempt,
                            url = effect.path,
                            revoke = false,
                        ),
                    )
                }
            }
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1_600 || bounds.outHeight / sample > 1_600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }.getOrNull()
}

/**
 * `![alt](src)` from the transcript.
 *
 * Their renderer marks the element with `data-local-image` and lets the machine fill `src`; the
 * browser loads an `http(s)` image on its own and the file API serves a local one. Here both
 * go through the same reader, which is what [read] is for — the caller decides how to fetch.
 *
 * On `failed` the alt text stays visible, which is their stated behaviour (`:29`).
 */
@Composable
fun MarkdownImageBlock(
    alt: String,
    src: String,
    read: (suspend (String) -> ByteArray?)?,
) {
    val key = remember(src) {
        MarkdownImage.localImagePath(src)
            ?: src.trim().takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }
    if (key == null || read == null) {
        ImageAlt(alt, src)
        return
    }
    val scope = rememberCoroutineScope()
    val host = remember(key) { MarkdownImageHost(scope, read) }
    LaunchedEffect(key) { host.sync(key) }
    DisposableEffect(host) { onDispose { host.dispose() } }

    val bmp = host.bitmap(key)
    if (bmp != null) {
        Image(
            bmp,
            contentDescription = alt.ifBlank { null },
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .border(0.5.dp, Line, RoundedCornerShape(6.dp)),
        )
        return
    }
    ImageAlt(alt, src, loading = !host.failed(key))
}

/** The alt text row — their `failed` state keeps it, and it is what shows while a read is out. */
@Composable
private fun ImageAlt(alt: String, src: String, loading: Boolean = false) {
    val label = alt.ifBlank { MarkdownImage.localImagePath(src) ?: src }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Surface2)
            .border(0.5.dp, Line, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            if (loading) "Loading image…" else label,
            fontSize = 11.sp,
            color = TextDim,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
