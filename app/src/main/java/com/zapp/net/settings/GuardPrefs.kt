package com.zapp.net.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The dashboard's protection switches, ported from the reference tool's panel
 * (`src/ui/index.html`, ids `chkKeeper`, `chkGuard`, `chkAutoResume`, `chkAutoRepair`,
 * `chkAutostart`). Same meaning, same defaults — every one of theirs defaults to ON
 * except logon auto-start, which is opt-in there and here.
 *
 * What is deliberately NOT ported, because the desktop control has no Android
 * counterpart: `chkServiceProxy` (there is no opencode.exe daemon to wire through a
 * loopback proxy — the server runs in Termux and the tunnel is per-app) and
 * `chkFirewall` (Android's always-on/lockdown VPN is a system setting an app cannot
 * toggle, and there is no firewall API without root).
 *
 * `chkStrict` IS ported — as [STRICT_FLAG], the file `zen_proxy._strict_mode():743-746`
 * already reads. The comment that used to sit here claimed there was nothing to fail
 * closed from; that stopped being true when `open_warp_direct` step 4 began refusing the
 * plain route for a pinned host.
 *
 * `chkAutoResume` exists in their panel because their tool re-sends a refused prompt.
 * the owner's standing order removes auto-send ("AND ALSO REMOVE AUTO SEND"), so the switch is
 * gone from our panel rather than left as dead state.
 */
object GuardPrefs {

    /** `$HOME/.zapp/lane/strict_proxy.flag` — their `_strict_mode()` reads this exact path. */
    const val STRICT_FLAG = "strict_proxy.flag"

    data class State(
        /** `chkKeeper` — keep the WARP tunnel up without being asked. */
        val keeper: Boolean = true,
        /** `chkGuard` — subscribe to the server's event feed and rotate on a wall. */
        val guard: Boolean = true,
        /** kept for prefs compatibility only; there is no auto-send to switch on. */
        val autoResume: Boolean = false,
        /** `chkAutoRepair` — an unproven tunnel is re-rolled instead of left in place. */
        val autoRepair: Boolean = true,
        /** `chkAutostart` — bring the tunnel + guard up at boot. */
        val autostart: Boolean = true,
        /**
         * `chkStrict` — refuse the plain route for a pinned host when the tunnel is down.
         *
         * OFF by default here while theirs ships it ON: measured today, both WARP edges
         * dropped for minutes at a time, and with strict on that is a total outage rather
         * than a degraded one. The switch is real either way — it writes the same flag file
         * `zen_proxy._strict_mode()` reads.
         */
        val strict: Boolean = false,
        /** Tor lane (`chkProxy` in the reference UI, which wires opencode through the
         * loopback proxy).
         *
         * OFF by default, and that is not a preference — it is measured. The reference's
         * own proxy defaults to `mode = "warp"`; Tor is a separate mode you choose. On this
         * phone the server is `com.termux`, the one package inside ZAPP's tun, so with no
         * proxy its provider traffic leaves through Cloudflare and its public /64 is the
         * thing a hop changes. The proxy dials from the app uid, which is outside the tun
         * (`49.149.65.25 warp=off`, measured 2026-11-20 01:14), so attaching it silently
         * takes the server out of WARP and turns every rotation into a no-op.
         */
        val tor: Boolean = false,
        /**
         * Re-send the exact prompt that a wall killed, once, on the fresh address.
         *
         * This is NOT the auto-send the owner removed on 2026-11-20. That one fired the literal
         * word `continue` into every chat the wall had touched, spending an address per chat and
         * sending text he never typed. This one re-issues **the prompt he typed**, in **the chat
         * it died in**, **once**, after the swap that replaced the dead address - which is the
         * only way "the AI continues after out of token" can be true: the old socket died with
         * the TUN, so the server-side turn is gone and only a fresh request can carry on.
         */
        val resume: Boolean = true,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Volatile private var loaded = false

    private fun sp(ctx: Context) = ctx.applicationContext
        .getSharedPreferences("zapp_guard", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val p = sp(ctx)
            val d = State()
            _state.value = State(
                keeper = p.getBoolean("keeper", d.keeper),
                guard = p.getBoolean("guard", d.guard),
                autoResume = p.getBoolean("autoResume", d.autoResume),
                autoRepair = p.getBoolean("autoRepair", d.autoRepair),
                autostart = p.getBoolean("autostart", d.autostart),
                tor = p.getBoolean("tor", d.tor),
                strict = p.getBoolean("strict", d.strict),
                resume = p.getBoolean("resume", d.resume),
            )
            loaded = true
        }
    }

    fun set(ctx: Context, key: String, value: Boolean) {
        load(ctx)
        sp(ctx).edit().putBoolean(key, value).apply()
        _state.value = when (key) {
            "keeper" -> _state.value.copy(keeper = value)
            "guard" -> _state.value.copy(guard = value)
            "autoResume" -> _state.value.copy(autoResume = value)
            "autoRepair" -> _state.value.copy(autoRepair = value)
            "autostart" -> _state.value.copy(autostart = value)
            "tor" -> _state.value.copy(tor = value)
            "resume" -> _state.value.copy(resume = value)
            "strict" -> {
                applyStrict(ctx, value)
                _state.value.copy(strict = value)
            }
            else -> _state.value
        }
    }

    /**
     * Their `chkStrict` / `zen_proxy._strict_mode():743-746`.
     *
     * The proxy decides from a file, so the switch has to put a file there. Theirs writes
     * `LANE_DIR / "strict_proxy.flag"`; ours is the same path under Termux's `$HOME`, so
     * the flag survives app reinstalls the way theirs survives restarts. Written through
     * `RunCommandService` because the lane directory belongs to Termux's uid.
     */
    fun applyStrict(ctx: Context, on: Boolean) {
        val cmd = if (on) {
            "L=\"\$HOME/.zapp/lane\"; mkdir -p \"\$L\"; date +%s > \"\$L/strict_proxy.flag\""
        } else {
            "rm -f \"\$HOME/.zapp/lane/strict_proxy.flag\""
        }
        runCatching {
            com.zapp.net.termux.RunCommand.run(ctx, cmd, "/sdcard/zapp_strict.log")
        }
        com.zapp.net.guard.GuardState.log(
            "vpn",
            if (on) "strict fail-closed ON - a pinned host with no tunnel edge is refused"
            else "strict fail-closed OFF - a pinned host may fall back to the plain route",
        )
    }

    /**
     * Until this moment WARP was measured to carry nothing, so the tunnel is left down and
     * Termux keeps the direct path. Persisted because the release must survive a swipe-up:
     * measured 05:56:17 (`reason=13 subreason=101 Swipe-up clean`), the process died mid-cycle
     * and the restart at 05:56:18 rebuilt the dead tunnel 0.6 s later, cutting the server's
     * DNS again (`EAI_AGAIN opencode.ai`).
     */
    fun carryBlockedUntil(ctx: Context): Long = sp(ctx).getLong("carryBlockedUntilMs", 0L)

    fun setCarryBlockedUntil(ctx: Context, ms: Long) {
        sp(ctx).edit().putLong("carryBlockedUntilMs", ms).apply()
    }

    /**
     * When the free tier's daily budget reopens.
     *
     * Persisted, not held in memory, because it is the one deadline that outlives the
     * process by design: measured 2026-11-20 06:33, the budget reopens at 00:00 UTC, which
     * is ~1.5 h away on this phone and up to 24 h away on a fresh burn-in. A guard that
     * forgot it across a swipe-up would start hopping again on the next cold start, which
     * is exactly the waste this replaced.
     *
     * `0` means "no free limit is in force".
     */
    fun freeLimitUntil(ctx: Context): Long = sp(ctx).getLong("freeLimitUntilMs", 0L)

    fun setFreeLimitUntil(ctx: Context, ms: Long) {
        sp(ctx).edit().putLong("freeLimitUntilMs", ms).apply()
    }

    /** True while the server's own budget is still counting down. */
    fun freeLimitActive(ctx: Context): Boolean = freeLimitUntil(ctx) > System.currentTimeMillis()

    /**
     * The tunnel peer endpoint the user pinned in the Clean Endpoint panel.
     *
     * Their `set_custom_endpoint` runs `warp-cli tunnel endpoint set <ip:port>`, which
     * persists in the WARP config; the phone has no warp-cli, so the same choice is
     * persisted here and read by `ZappVpnService` when it builds the peer.
     *
     * `""` means "no override - let the registration decide" (`reset_custom_endpoint`).
     */
    fun endpointOverride(ctx: Context): String =
        sp(ctx).getString("endpointOverride", "").orEmpty()

    fun setEndpointOverride(ctx: Context, endpoint: String?) {
        val v = endpoint?.trim().orEmpty()
        sp(ctx).edit().putString("endpointOverride", v).apply()
    }

    /**
     * The user's last Connect/Disconnect choice, persisted across process death.
     *
     * Measured 2026-11-20 13:30:18: `Usf_Hiber` froze uid 10447 five seconds after the last
     * relay ran, the process was killed and restarted as a bare cached-empty process
     * (`Proc #19: cch+ 5 b/ /CEM ... cached=true empty=true`), and nothing inside it ever
     * started the tunnel again. The panel still showed the last good address, so the app
     * looked connected while every prompt left through the carrier.
     *
     * A process start is the one moment an app may legally start a foreground service
     * without user action, so the last choice has to survive the process, not just the
     * activity.
     */
    fun vpnWanted(ctx: Context): Boolean = sp(ctx).getBoolean("vpnWanted", false)

    fun setVpnWanted(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("vpnWanted", on).apply()
    }

    fun autoRepair(ctx: Context): Boolean { load(ctx); return _state.value.autoRepair }
    fun autoResume(ctx: Context): Boolean { load(ctx); return _state.value.autoResume }
    fun guardOn(ctx: Context): Boolean { load(ctx); return _state.value.guard }
    fun keeperOn(ctx: Context): Boolean { load(ctx); return _state.value.keeper }
    fun autostartOn(ctx: Context): Boolean { load(ctx); return _state.value.autostart }
    fun torOn(ctx: Context): Boolean { load(ctx); return _state.value.tor }
}
