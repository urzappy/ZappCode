package com.zapp.net.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Shell with the server's rights - the only way an unrooted app gets them.
 *
 * The Shizuku server runs as uid `shell` (measured 2026-11-20: `shell 32682 1 ... shizuku_server`,
 * PPID 1, so it is owned by init and outlives the adb link). It hosts [UserServiceHost] for us,
 * and that host's children inherit uid 2000. So `pm`, `am`, `input`, `screencap`, `dumpsys`,
 * `settings`, `logcat` and `cmd` behave exactly as they do in an adb session.
 *
 * The grant is per-package and survives reboots; only the server process needs restarting.
 */
object Shell {

    /** One command's answer. `code` is the exit status, `text` is stdout followed by stderr. */
    data class Out(val code: Int, val text: String)

    private const val PERMISSION_REQUEST = 0x5A99

    private val lock = Mutex()

    @Volatile private var service: IUserService? = null
    @Volatile private var connecting = false
    @Volatile private var connectingSince = 0L

    /** Held so the listener can be removed; the API wants the same instance back. */
    @Volatile private var permissionListener: Shizuku.OnRequestPermissionResultListener? = null

    /** The binder's own state. True means `adbd`-equivalent rights are reachable. */
    fun alive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** Has he tapped Allow for this package? */
    fun granted(): Boolean = runCatching {
        if (!alive()) false else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Ask for the grant. The dialog belongs to the Shizuku app; call this while visible. */
    fun request(onResult: (Boolean) -> Unit) {
        if (!alive()) {
            onResult(false)
            return
        }
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PERMISSION_REQUEST) {
                permissionListener?.let {
                    runCatching { Shizuku.removeRequestPermissionResultListener(it) }
                }
                permissionListener = null
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        permissionListener = listener
        Shizuku.addRequestPermissionResultListener(listener)
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST) }
            .onFailure {
                Log.e("ZAPP", "shizuku permission request failed: $it")
                onResult(false)
            }
    }

    fun forget() {
        permissionListener?.let {
            runCatching { Shizuku.removeRequestPermissionResultListener(it) }
        }
        permissionListener = null
    }

    /** The one-liner he can paste on a PC after a reboot killed the server. */
    fun startCommand(ctx: Context): String = runCatching {
        val info = ctx.packageManager.getApplicationInfo("moe.shizuku.privileged.api", 0)
        val abi = if (android.os.Process.is64Bit()) "arm64" else "arm"
        "adb shell ${info.nativeLibraryDir}/libshizuku.so"
    }.getOrDefault("adb shell sh /data/data/com.termux/files/usr/bin/shizuku-start")

    /**
     * Bind the hosted service. Idempotent and safe to call from a poll loop: a failed bind
     * clears `connecting` so the next tick retries instead of waiting forever.
     */
    fun ensureBound(ctx: Context) {
        if (service != null || !alive() || !granted()) return
        // A bind that never answers must not lock us out of retrying. Measured: the first
        // attempt after an install can land before the server has finished starting, and
        // Shizuku then never calls back. Expire the claim instead of waiting forever.
        if (connecting && System.currentTimeMillis() - connectingSince < 5_000) return
        connecting = true
        connectingSince = System.currentTimeMillis()
        val args = args(ctx)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IUserService.Stub.asInterface(binder)
                connecting = false
                Log.e("ZAPP", "shizuku usvc connected")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                connecting = false
                Log.e("ZAPP", "shizuku usvc disconnected")
            }
        }
        runCatching { Shizuku.bindUserService(args, conn) }
            .onFailure {
                Log.e("ZAPP", "bindUserService failed: $it")
                connecting = false
            }
    }

    private fun args(ctx: Context) = Shizuku.UserServiceArgs(
        ComponentName(ctx.packageName, UserServiceHost::class.java.name),
    )
        // A daemon service is restarted by the server on death; we do not want a stuck ghost.
        .daemon(false)
        .processNameSuffix("zapp_shell")
        // Must be false: a debuggable service makes Shizuku demand a debug build of the server.
        .debuggable(false)
        // Bump on every change to the service class: a record with the same version can be
        // reused, and a stale record points at an APK path that no longer exists after an
        // install. Measured 03:53:10: an old record sat "not started in 30000 ms" forever.
        .version(2)

    /**
     * Run one command as uid `shell`.
     *
     * Null means "no hands": binder down, permission missing, or the service not bound yet.
     * The caller shows that as-is. A fabricated exit code 0 here would be read as success by
     * everything downstream, which is exactly the class of lie this project refuses.
     */
    suspend fun exec(ctx: Context, cmd: String, timeoutMs: Long = 60_000): Out? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                ensureBound(ctx)
                // The first call usually arrives before the server has answered the bind
                // (measured 03:51:44: `no hands (binder=true granted=true)` on the very first
                // command). Binding is asynchronous by contract, so wait for it instead of
                // reporting a false "no hands" that a caller would read as a broken install.
                var waited = 0L
                while (service == null && waited < 5_000) {
                    kotlinx.coroutines.delay(100)
                    waited += 100
                    if (service == null) ensureBound(ctx)
                }
                val svc = service ?: return@withLock null
                val raw = runCatching {
                    kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                            svc.run(cmd)
                        }
                    }
                }.onFailure { Log.e("ZAPP", "usvc.run failed: $it") }.getOrNull()
                    ?: return@withLock null
                val nl = raw.indexOf('\n')
                if (nl <= 0) return@withLock Out(-1, raw)
                Out(raw.substring(0, nl).toIntOrNull() ?: -1, raw.substring(nl + 1))
            }
        }

    /** Drop the binding and the hosted process; used when auto-repair is disabled. */
    fun shutdown(ctx: Context) {
        runCatching { service?.exit() }
        service = null
        connecting = false
    }
}
