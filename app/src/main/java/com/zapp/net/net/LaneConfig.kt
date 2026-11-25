package com.zapp.net.net

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import org.json.JSONObject

/**
 * Writes the lane config the proxy obeys.
 *
 * Their desktop panel edits `state.egress` and their Python lane reads the same dict.
 * On Android the lane is Termux's `$HOME/.zapp/lane/egress_mode.json`, which the app
 * cannot open, so the file is written through Termux's RUN_COMMAND service - the same
 * relay `BootRelay` already uses for `zapp_proxy.py`.
 *
 * `load_cfg()` in `zapp_proxy.py` is called per connection, so a write here takes effect
 * on the next request. No restart, no stale copy: that is why the mode tabs can be honest.
 *
 * Only the four keys their `proxy_chain.get_egress_config()` owns are written. The
 * custom-proxy round-robin counter lives in its own file (`custom_proxy_index.txt`)
 * because the lane reads it per connection too.
 */
object LaneConfig {

    private const val TAG = "ZAPP"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val SH = "/data/data/com.termux/files/usr/bin/sh"

    const val MODE_WARP = "warp"
    const val MODE_TOR = "tor"
    const val MODE_CUSTOM = "custom"
    /** Their panel has a fourth tab; the lane only needs the endpoint, see [cleanApply]. */
    const val MODE_CLEAN = "clean"

    const val SCOPE_ISOLATED = "isolated"
    const val SCOPE_TUN = "tun"

    /**
     * Write the whole config object.
     *
     * A full write, not a merge: `proxy_status` publishes every key this object holds
     * (`mode`, `warp_scope`, `custom_proxy`, `custom_type`), so the caller always has the
     * current values and a partial merge would be a second source of truth.
     */
    fun write(
        ctx: Context,
        mode: String,
        scope: String,
        customProxy: String,
        customType: String,
    ) {
        val json = JSONObject()
            .put("mode", mode)
            .put("warp_scope", scope)
            .put("custom_proxy", customProxy)
            .put("custom_type", customType)
        val body = json.toString()

        // Keep our own copy first.
        //
        // The lane is no longer Termux's python process - it is `EgressProxy` in this app
        // (`serve.sh` measured `E: Unable to locate package python`, so the python lane could
        // never be relied on). A file this app cannot open cannot drive a proxy this app
        // runs, so the tab tap is stored where the proxy can read it, and the Termux copy is
        // still written below for the standalone case.
        prefs(ctx).edit()
            .putString("mode", mode)
            .putString("scope", scope)
            .putString("proxy", customProxy)
            .putString("type", customType)
            .apply()
        com.zapp.net.net.EgressProxy.applyConfig(mode, scope, customProxy, customType)

        val script = buildString {
            append("mkdir -p \$HOME/.zapp/lane\n")
            append("cat > \$HOME/.zapp/lane/egress_mode.json.tmp <<'ZAPPEOF'\n")
            append(body).append('\n')
            append("ZAPPEOF\n")
            append("mv \$HOME/.zapp/lane/egress_mode.json.tmp \$HOME/.zapp/lane/egress_mode.json\n")
            append("echo \"lane: ").append(mode).append('/').append(scope).append("\"\n")
        }
        relay(ctx, script)
        Log.e(TAG, "lane config -> $body")
    }

    data class Config(
        val mode: String = MODE_WARP,
        val scope: String = SCOPE_ISOLATED,
        val proxy: String = "",
        val type: String = "socks5",
    )

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("zapp_lane", Context.MODE_PRIVATE)

    /** What the tabs last chose, read by the in-app lane on every dial. */
    fun read(ctx: Context): Config {
        val p = prefs(ctx)
        return Config(
            mode = p.getString("mode", MODE_WARP) ?: MODE_WARP,
            scope = p.getString("scope", SCOPE_ISOLATED) ?: SCOPE_ISOLATED,
            proxy = p.getString("proxy", "") ?: "",
            type = p.getString("type", "socks5") ?: "socks5",
        )
    }

    /** `btnRotateCustomProxy` / their `next_custom_proxy`: advance the round-robin counter. */
    fun nextCustomNode(ctx: Context) {
        relay(
            ctx,
            "d=\$HOME/.zapp/lane; mkdir -p \$d; " +
                "n=\$(cat \$d/custom_proxy_index.txt 2>/dev/null || echo 0); " +
                "echo \$((n+1)) > \$d/custom_proxy_index.txt; " +
                "echo \"lane: custom node -> \$(cat \$d/custom_proxy_index.txt)\"\n",
        )
    }

    /** Their `reset_custom_proxy_pool`: back to the first node. */
    fun resetCustomIndex(ctx: Context) {
        relay(
            ctx,
            "d=\$HOME/.zapp/lane; mkdir -p \$d; echo 0 > \$d/custom_proxy_index.txt\n",
        )
    }

    private fun relay(ctx: Context, script: String) {
        val b64 = Base64.encodeToString(script.toByteArray(), Base64.NO_WRAP)
        val i = Intent("com.termux.RUN_COMMAND").setClassName("com.termux", SERVICE)
        i.putExtra("com.termux.RUN_COMMAND_PATH", SH)
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", "echo $b64 | base64 -d | sh"))
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        runCatching { ctx.startForegroundService(i) }
            .onFailure { Log.e(TAG, "lane relay failed: $it") }
    }
}
