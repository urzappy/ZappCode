package com.zapp.net.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zapp.net.ui.theme.Border
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.Surface
import com.zapp.net.ui.theme.Surface2
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextMid
import com.zapp.net.ui.theme.TextPrimary
import java.net.URI

/**
 * `panel-BydSK99n.js` / `panel-QKwfqHQW.js`: their Browser pane is a real embedded page with a URL
 * bar, back/forward and its own tab strip. On the phone the tab strip is the pane's own strip, so
 * this is the page plus its chrome.
 *
 * Their provider is `{icon: "globe", keybind: "browser.open"}` at `session.panel` order 15. The
 * page is a `WebView`; the process already holds `INTERNET` and
 * `android:usesCleartextTraffic="true"`, so a `localhost` server and a remote page both load.
 *
 * The black pane the owner saw was a `WebView` with nothing in it: a tab is created at `""`, and an
 * empty `WebView` paints the window background, which is our near-black `Surface`. Their own
 * browser canvas is white and their address bar normalises what you type - both are ported below.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserPane(
    startUrl: String,
    onUrl: (String) -> Unit,
) {
    var address by remember { mutableStateOf(startUrl) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(startUrl.isNotBlank()) }

    fun go() {
        val url = browserUrl(address)
        web?.loadUrl(url)
        address = if (url == "about:blank") "" else url
        onUrl(url)
    }

    Column(Modifier.fillMaxWidth().background(Surface)) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            GlyphButton(OcIcons.ArrowLeft, "Back", enabled = canBack) {
                if (web?.canGoBack() == true) web?.goBack()
            }
            GlyphButton(OcIcons.ArrowRight, "Forward", enabled = canForward) {
                if (web?.canGoForward() == true) web?.goForward()
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Surface2)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = address,
                    onValueChange = { address = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 12.sp, color = TextPrimary),
                    cursorBrush = SolidColor(TextMid),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (address.isEmpty()) {
                            Text("Search or enter address", fontSize = 12.sp, color = TextDim)
                        }
                        inner()
                    },
                )
            }
            GlyphButton(OcIcons.ArrowUp, "Go", enabled = address.isNotBlank()) { go() }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        if (loading) {
            // Their pane paints a thin progress line while the page is on the wire. A 1dp band is
            // the whole of it - no spinner over the page.
            Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    // A browser canvas is white, not the app's background. Without this an empty
                    // tab is a black rectangle.
                    setBackgroundColor(AndroidColor.WHITE)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            loading = true
                            url?.let { if (it != "about:blank") address = it }
                            canBack = view?.canGoBack() == true
                            canForward = view?.canGoForward() == true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                            url?.let { if (it != "about:blank") address = it }
                            canBack = view?.canGoBack() == true
                            canForward = view?.canGoForward() == true
                        }
                    }
                    web = this
                    loadUrl(browserUrl(startUrl))
                }
            },
            update = { view ->
                // Only follow a change that came from outside; never reload on every recompose.
                val want = browserUrl(startUrl)
                if (want != "about:blank" && view.url != want && !view.canGoBack()) {
                    view.loadUrl(want)
                }
            },
        )
    }
}

/**
 * Their address-bar normaliser, `panel-BydSK99n.js` @ 1 242:
 *
 * ```
 * var Se = `https://www.google.com/search?q=`;
 * function A(e){ let t=e.trim(); return !t || t.toLowerCase()===`about:blank`
 *   ? `about:blank` : Ce(t) ? `${Se}${encodeURIComponent(t)}` : t }
 * function Ce(e){ ... url scheme -> !URL.canParse(t)
 *                 whitespace   -> true
 *                 host is localhost / [ipv6] / has :port / is dotted -> !xe(`https://${t}`)
 *                 otherwise    -> true }
 * function xe(e){ ... returns origin only for http(s) with no credentials }
 * ```
 *
 * `Ce` answers "is this a search term". A bare `example.com` or `localhost:4096` is a URL;
 * `hello world` and `rust borrow checker` are searches. One addition the phone needs: a WebView
 * will not resolve a scheme-less host, so a URL that survived `Ce` gets `https://` in front.
 */
internal fun browserUrl(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty() || t.equals("about:blank", ignoreCase = true)) return "about:blank"
    if (isSearchTerm(t)) return "https://www.google.com/search?q=" + encodeComponent(t)
    return if (t.contains("://") || t.startsWith("about:")) t else "https://$t"
}

/** `encodeURIComponent`: everything outside `A-Za-z0-9-_.!~*'()` becomes `%XX` of its UTF-8 bytes. */
private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
private const val HEX = "0123456789ABCDEF"

private fun encodeComponent(s: String): String {
    val out = StringBuilder(s.length + 8)
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xFF
        if (c < 0x80 && UNRESERVED.indexOf(c.toChar()) >= 0) {
            out.append(c.toChar())
        } else {
            out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
        }
    }
    return out.toString()
}

private fun isSearchTerm(raw: String): Boolean {
    val t = raw.trim()
    if (t.isEmpty()) return false
    if (Regex("^about:\\S*$", RegexOption.IGNORE_CASE).matches(t)) return false
    if (Regex("^[a-z][a-z\\d+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
        return runCatching { URI(t) }.isFailure
    }
    if (t.any { it.isWhitespace() }) return true
    val host = t.split('/', '?', '#').first()
    val local = Regex("^(?:localhost|\\[[\\da-f:.]+\\])(?::\\d+)?$", RegexOption.IGNORE_CASE)
        .matches(host)
    val port = Regex(":\\d+$").containsMatchIn(host)
    val dotted = Regex("^[^.]+(?:\\.[^.]+)+$").matches(host)
    return !(local || port || dotted) || originOf("https://$t") == null
}

/** `xe`: the origin of an http(s) URL with no credentials, or null. */
private fun originOf(raw: String): String? {
    val u = runCatching { URI(raw) }.getOrNull() ?: return null
    val scheme = u.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    if (!u.userInfo.isNullOrEmpty()) return null
    val host = u.host ?: return null
    val port = if (u.port > 0) ":${u.port}" else ""
    return "$scheme://$host$port"
}

@Composable
private fun GlyphButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            label,
            tint = if (enabled) TextMid else TextDim.copy(alpha = 0.4f),
            modifier = Modifier.size(15.dp),
        )
    }
}
