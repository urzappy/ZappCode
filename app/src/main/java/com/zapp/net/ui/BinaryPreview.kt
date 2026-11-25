package com.zapp.net.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.ui.theme.Surface
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextMid

/**
 * A file whose bytes are not text.
 *
 * `review/model.ts:784-798` reads such a file only to preview it:
 *
 * ```
 * server.client.file.read({ path, location: { directory } })
 *   .then(bytes => ({ type: "binary", content: bytesToBase64(bytes),
 *                     encoding: "base64", mimeType: artifactMime(path) }))
 * ```
 *
 * So the desktop hands its file view raw bytes plus [Artifact.mimeOf] and lets it render by
 * `artifactKind`. The **kind** comes from the extension table, never from sniffing the bytes.
 *
 * Images decode. Everything else prints its kind and MIME instead of pretending to play: audio
 * and video have no player on this surface, and a PDF or a font is not drawable.
 */
@Composable
fun BinaryPreview(path: String, bytes: ByteArray, modifier: Modifier = Modifier) {
    val kind = remember(path) { Artifact.kindOf(path) }
    val mime = remember(path) { Artifact.mimeOf(path) }

    if (!Artifact.isImage(kind)) {
        Row(
            modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(kindLabel(kind), fontSize = 12.sp, color = TextMid)
            if (mime != null) {
                Spacer(Modifier.width(8.dp))
                Text(mime, fontSize = 11.sp, color = TextDim, fontFamily = FontFamily.Monospace)
            }
        }
        return
    }

    val bmp = remember(path, bytes) { decode(bytes) }
    Column(modifier.fillMaxSize().padding(14.dp)) {
        if (bmp != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Surface),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bmp,
                    contentDescription = path,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                )
            }
        } else {
            Text("Couldn't decode this image", fontSize = 11.5.sp, color = TextDim)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
            Text(path.replace('\\', '/').substringAfterLast('/'), fontSize = 11.sp, color = TextDim)
            if (mime != null) {
                Spacer(Modifier.width(8.dp))
                Text(mime, fontSize = 11.sp, color = TextDim, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** The same downsample the markdown image host uses: never decode past 1 600 px a side. */
private fun decode(bytes: ByteArray) = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 1_600 || bounds.outHeight / sample > 1_600) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}.getOrNull()

private fun kindLabel(kind: Artifact.Kind): String = when (kind) {
    Artifact.Kind.Audio -> "Audio"
    Artifact.Kind.Video -> "Video"
    Artifact.Kind.Pdf -> "PDF"
    Artifact.Kind.Font -> "Font"
    Artifact.Kind.Document -> "Document"
    Artifact.Kind.Spreadsheet -> "Spreadsheet"
    Artifact.Kind.Presentation -> "Presentation"
    Artifact.Kind.Table -> "Table"
    Artifact.Kind.Html -> "HTML"
    Artifact.Kind.Markdown -> "Markdown"
    Artifact.Kind.Mermaid -> "Mermaid"
    Artifact.Kind.Image, Artifact.Kind.Svg -> "Image"
    Artifact.Kind.Text -> "Binary file"
}
