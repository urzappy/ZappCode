package com.zapp.net.shizuku

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Runs inside a Shizuku helper process (uid 2000 = shell).
 *
 * Shizuku does NOT start this as an Android Service. It loads the class from our APK,
 * instantiates it - with a `(Context)` constructor when one exists - and casts the result
 * to `IBinder`. So the class has to *be* a Binder: `IUserService.Stub` is exactly that.
 *
 * Measured 2026-11-20 03:57:08, the Service-shaped version failed with
 * `ClassCastException: UserServiceHost cannot be cast to android.os.IBinder`.
 *
 * Everything this runs is a child of a uid-2000 process, which is the whole point: no root,
 * no su, and the app keeps working after the adb link is gone.
 */
class UserServiceHost(@Suppress("unused") private val appContext: Context?) : IUserService.Stub() {

    override fun run(cmd: String): String {
        val t0 = System.currentTimeMillis()
        val code: Int
        val text: String
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = StringBuilder()
            val err = StringBuilder()
            val t1 = Thread { drain(p.inputStream, out) }
            val t2 = Thread { drain(p.errorStream, err) }
            t1.start(); t2.start()
            code = p.waitFor()
            t1.join(4_000); t2.join(4_000)
            text = buildString {
                append(out)
                if (err.isNotEmpty()) {
                    if (isNotEmpty() && !endsWith("\n")) append('\n')
                    append(err)
                }
            }
        } catch (t: Throwable) {
            return "-1\n${t.javaClass.simpleName}: ${t.message}"
        }
        android.util.Log.e(
            "ZAPP",
            "usvc.run(${System.currentTimeMillis() - t0}ms rc=$code ${text.length}b): ${cmd.take(100)}",
        )
        return "$code\n$text"
    }

    override fun destroy() {
        // The server calls this when it tears the record down. Nothing persistent is held
        // here - every command is its own child process - so there is nothing to release.
    }

    override fun exit() {
        // Ends the helper process this binder lives in. It is a per-service process, not the
        // server itself, so Shizuku survives it.
        System.exit(0)
    }

    /**
     * Binder allows ~1 MB per transaction, so a runaway stream is cut here rather than
     * failing the whole call. 200 kB is far more than any command we issue needs.
     */
    private fun drain(stream: java.io.InputStream, sink: StringBuilder) = runCatching {
        BufferedReader(InputStreamReader(stream)).use { r ->
            val buf = CharArray(8192)
            var n: Int
            while (r.read(buf).also { n = it } > 0) {
                if (sink.length < 200_000) sink.append(buf, 0, n)
            }
        }
    }
}
