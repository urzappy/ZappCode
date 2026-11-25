package com.zapp.net

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * "Is the app on screen", which is the phone spelling of desktop's window focus.
 *
 * `shell/notifications/notification.tsx` only notifies a session that has an open tab
 * (`sessionIDHasOpenTab`, `:641`); it does not check whether that tab is the visible one.
 * On a phone a banner over the chat the user is already reading is pure noise, so the rule
 * here is the same rule with one extra term: an open tab that is **not** the session on
 * screen. `MainActivity` supplies the on-screen half; this object supplies the foreground half.
 *
 * A resumed-activity counter, not a boolean, because a second activity (the VPN consent
 * screen) resumes before the first one pauses and a boolean would flicker to false.
 */
object Foreground {
    @Volatile
    private var resumed = 0

    val isForeground: Boolean get() = resumed > 0

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed++
            }

            override fun onActivityPaused(activity: Activity) {
                resumed = (resumed - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

/**
 * Restores the last working state on **every** process start, not just on activity start.
 *
 * Evidence this exists for (2026-11-20 13:30):
 *
 *     relay: sh /sdcard/zapp_torF.sh              13:30:13.529
 *     freeze uid: 10447 com.zapp.net              13:30:18.452   last unfreeze 3476s ago
 *     Proc #19: cch+ 5 b/ /CEM  (cch-empty)       cached=true empty=true
 *
 * The process was frozen by the vendor hibernator, then replaced by a new process that
 * held no service at all. `am broadcast` still reported success, the panel still showed
 * the last tunnel address, and nothing inside the app executed for 58 minutes. A
 * cached-empty process is not allowed to run receivers, so every command and every
 * background step the user expects silently stopped.
 *
 * Android guarantees that a process start caused by a broadcast, an activity or a
 * service may start a foreground service immediately, so this is the correct place to
 * re-assert the two things the user already asked for:
 *
 *  - the tunnel, if they had pressed Connect and never pressed Disconnect
 *  - the guard, if `GuardPrefs.guardOn` is true (same rule `MainActivity` uses)
 *
 * Nothing here prompts. `VpnService.prepare` consent already exists when `vpnWanted` was
 * written, and a FGS that is not allowed fails silently inside `runCatching` rather than
 * taking the process down.
 */
class ZappApp : Application() {

    override fun onCreate() {
        super.onCreate()
        com.zapp.net.settings.GuardPrefs.load(this)
        com.zapp.net.settings.SessionPrefs.load(this)
        com.zapp.net.settings.DisplayPrefs.load(this)
        // The Settings surface's own store: every key in `settings/model.tsx` that the phone
        // keeps but does not act on, plus the model enable/disable list the picker filter reads.
        com.zapp.net.settings.SettingsPrefs.load(this)
        com.zapp.net.notify.Notifier.ensure(this)
        Foreground.install(this)

        if (com.zapp.net.settings.GuardPrefs.guardOn(this)) {
            runCatching {
                startForegroundService(
                    Intent(this, com.zapp.net.guard.GuardForegroundService::class.java),
                )
            }.onFailure { Log.e("ZAPP", "app start: guard not started: $it") }
        }

        // The lane lives in this uid: without the VPN service the tunnel is gone even
        // though Termux and the proxy are still running fine.
        if (com.zapp.net.settings.GuardPrefs.vpnWanted(this)) {
            val i = Intent(this, com.zapp.net.vpn.ZappVpnService::class.java)
                .setAction(com.zapp.net.vpn.ZappVpnService.ACTION_START)
            // Android 14 refuses a background FGS start for some origins; a plain
            // `startService` is still accepted when the service foregrounds itself.
            val ok = runCatching { startForegroundService(i); true }
                .getOrElse { runCatching { startService(i); true }.getOrElse { false } }
            Log.e("ZAPP", "app start: restore tunnel vpnWanted=true -> $ok")
        }
    }
}
