package com.zapp.net.termux

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Installs Termux from inside ZAPP, with no browser and no store.
 *
 * Why this exists. The engine runs inside Termux, so a phone without Termux cannot get past
 * onboarding. The old step opened the F-Droid page in a browser and left the rest to the user:
 * leave the app, find the right package among search results, avoid the abandoned Play Store
 * build, install, come back. the owner, 22 Nov: "if yung termux wala sa app natin na pwede
 * madownload ilagay mo bro mahihirapan sila mag install ng termux nyan."
 *
 * So the APK ships inside ZAPP (`assets/termux/termux.apk`, F-Droid 0.118.3). Nothing is fetched
 * to get the user started - no carrier blocking github, no mirror going stale, no "which Termux
 * is the real one". The network copy exists only as a fallback for a build that somehow lost
 * the asset.
 *
 * The file is verified against a pinned SHA-256 before it is offered to the installer, and a
 * mismatch is deleted and reported rather than installed.
 *
 * Android still asks the user to allow installs from ZAPP. That switch is the OS's; the screen
 * sends the user straight to it instead of letting the installer close with no explanation.
 */
object Installer {
    private const val TAG = "ZAPP"

    /** F-Droid 0.118.3: versionCode 1002, minSdk 24, the newest stable build. */
    private const val ASSET = "termux/termux.apk"
    private const val NAME = "termux-0.118.3.apk"
    private const val SHA = "e6265a57eb5ca363808488e3b01955958bed93bc0c8a0d281849b363b11027ec"

    /** Only used when the bundled asset is missing. Same bytes, same hash. */
    private val URLS = listOf(
        "https://github.com/urzappy/ZappCode/releases/download/tools-1/Termux-0.118.3.apk",
        "https://f-droid.org/repo/com.termux_1002.apk",
    )

    /**
     * The bundled APK's exact size, measured from the built asset.
     *
     * Needed because `InputStream.available()` on an asset is not the entry length - it reports
     * whatever the decompressor has buffered, so the old counter had no denominator and could
     * only ever say "N MB unpacked". `AssetManager.openFd` gives the real length when the entry
     * is stored, and this constant is the fallback when it is compressed.
     */
    private const val ASSET_BYTES = 113_880_067L

    /** Decimal MB with one place - the same unit the setup screen uses everywhere else. */
    private fun mb(bytes: Long): String {
        val whole = bytes / 1_000_000L
        val tenth = (bytes % 1_000_000L) / 100_000L
        return "$whole.$tenth MB"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Observed directly by the onboarding screen; Compose re-reads them on change.
    var busy by mutableStateOf(false); private set
    var progress by mutableStateOf(0); private set
    var note by mutableStateOf(""); private set
    var error by mutableStateOf(""); private set
    var apk by mutableStateOf<File?>(null); private set

    fun reset() {
        busy = false
        progress = 0
        note = ""
        error = ""
        apk = null
    }

    /** True when Android has not yet allowed this app to launch an installer. */
    fun needsSourcePermission(ctx: Context): Boolean = runCatching {
        !ctx.packageManager.canRequestPackageInstalls()
    }.getOrDefault(false)

    fun openSourceSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.packageName),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.e(TAG, "installer: no unknown-sources screen: $it") }
    }

    /**
     * Unpack the bundled APK, verify it, expose it. Safe to call twice: a verified file on
     * disk short-circuits, so a second tap costs nothing.
     */
    suspend fun fetch(ctx: Context) = withContext(Dispatchers.IO) {
        if (busy) return@withContext
        busy = true
        error = ""
        note = ""
        progress = 0
        try {
            val dir = File(ctx.cacheDir, "termux").apply { mkdirs() }
            val out = File(dir, NAME)

            if (out.isFile && out.length() > 0 && sha256(out) == SHA) {
                apk = out
                progress = 100
                note = "${mb(out.length())} ready"
                return@withContext
            }

            var last = copyBundled(ctx, out)
            if (last.isNotEmpty()) {
                Log.e(TAG, "installer: bundled asset unusable ($last) - trying the network")
                for (url in URLS) {
                    last = runCatching { pull(url, out) }.getOrElse { it.message ?: it.toString() }
                    if (last.isEmpty()) break
                    Log.e(TAG, "installer: $url failed: $last")
                }
            }
            if (last.isNotEmpty()) {
                error = last
                return@withContext
            }

            val got = sha256(out)
            if (got != SHA) {
                out.delete()
                error = "the file did not match the pinned hash - discarded"
                Log.e(TAG, "installer: hash $got != $SHA")
                return@withContext
            }
            apk = out
            progress = 100
            note = "${mb(out.length())} verified"
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
            Log.e(TAG, "installer: fetch failed: $t")
        } finally {
            busy = false
        }
    }

    /** Streams the bundled APK to disk. Returns "" on success, a reason otherwise. */
    private fun copyBundled(ctx: Context, out: File): String {
        val part = File(out.parentFile, out.name + ".part")
        part.delete()
        val stream = runCatching { ctx.assets.open(ASSET) }.getOrElse {
            return "no bundled Termux: ${it.message}"
        }
        stream.use { input ->
            // The real length, not `available()`. Preferences are in order: the zip entry's
            // own length (openFd only works for stored entries), then the measured constant,
            // then whatever the stream claims as a last resort.
            val total = runCatching { ctx.assets.openFd(ASSET).use { it.length } }
                .getOrElse { ASSET_BYTES }
            var done = 0L
            var mark = 0L
            part.outputStream().use { sink ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    sink.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - mark > 120) {
                        mark = now
                        progress = if (total > 0) ((done * 100) / total).toInt() else 0
                        note = if (total > 0) "${mb(done)} of ${mb(total)}"
                        else "${mb(done)} unpacked"
                    }
                }
            }
            // The last chunk may land inside the 120 ms window; close on the real number so the
            // line does not stop at "112.9 of 113.9 MB" while the file is already complete.
            progress = 100
            note = "${mb(done)} of ${mb(total)}"
        }
        if (!part.renameTo(out)) {
            part.copyTo(out, overwrite = true)
            part.delete()
        }
        return ""
    }

    /** Fallback only. Streams to disk with a progress readout. */
    private fun pull(url: String, out: File): String {
        val part = File(out.parentFile, out.name + ".part")
        part.delete()
        http.newCall(Request.Builder().url(url).header("User-Agent", "ZappCode").build())
            .execute().use { r ->
                if (!r.isSuccessful) return "HTTP ${r.code}"
                val body = r.body ?: return "empty body"
                val total = body.contentLength()
                var done = 0L
                var mark = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { sink ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            sink.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - mark > 120) {
                                mark = now
                                progress = if (total > 0) ((done * 100) / total).toInt() else 0
                                note = if (total > 0) "${mb(done)} of ${mb(total)}"
                                else "${mb(done)} downloaded"
                            }
                        }
                    }
                }
                if (total > 0 && done != total) return "short download ($done of $total)"
            }
        if (!part.renameTo(out)) {
            part.copyTo(out, overwrite = true)
            part.delete()
        }
        return ""
    }

    /**
     * Hand the verified APK to the system installer. `REQUEST_INSTALL_PACKAGES` is declared in
     * the manifest, so the only gate left is the per-app switch, which the caller checks first.
     */
    fun install(ctx: Context): Boolean {
        val f = apk ?: return false
        return runCatching {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.onFailure { Log.e(TAG, "installer: could not launch installer: $it") }
            .getOrDefault(false)
    }

    private fun sha256(f: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")
}
