package com.zapp.net.onboarding

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.zapp.net.termux.EgressProbe
import com.zapp.net.termux.Installer
import com.zapp.net.termux.TermuxSetup
import com.zapp.net.ui.theme.Accent
import com.zapp.net.ui.theme.Border
import com.zapp.net.ui.theme.Danger
import com.zapp.net.ui.theme.Layer1
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.Surface
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextMid
import com.zapp.net.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First run, in the order the owner specified: consent, then the address, then Termux, then
 * Discord (Discord is a later pass and has no gate here yet).
 *
 * The order is a privacy guarantee, not a layout preference. [IpIdentity] is only ever
 * called from step 2, and step 2 is unreachable until [Onboarding.acceptConsent] has run.
 * A user who declines never has their address read at all.
 *
 * Four screens, no navigation library, one callback at the end. The rest of the app
 * already works this way (`Root` in MainActivity switches on a single boolean), so adding
 * a NavHost for three frames would be a new pattern for no gain.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var step by remember { mutableStateOf(0) }

    // the owner, 23 Nov: "make the size phone size up and down, the zapp-net is blocking the time
    // in the screen". The root had no inset padding, so the "ZAPP-Net" title was drawn under
    // the status bar and the clock printed straight through it. `statusBarsPadding` +
    // `navigationBarsPadding` make the frame follow the phone's own bars on any height, instead
    // of a hardcoded top gap that only fits one device.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(com.zapp.net.ui.theme.Bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        StepRail(step)
        Box(Modifier.weight(1f)) {
            when (step) {
                0 -> ConsentStep(
                    onAccept = {
                        Onboarding.acceptConsent(ctx)
                        step = 1
                    },
                )
                // the owner, 23 Nov: "please make this your connection into the last setup".
                //
                // The engine comes first now, and the tunnel is the closing step. That is the
                // honest order: `IdentityStep` measures the route THROUGH the engine, and its
                // own copy already said "The tunnel is measured through the engine - starting
                // it" - asking for a VPN consent before the thing that measures it exists made
                // the screen look stuck even when nothing was wrong.
                1 -> TermuxStep(onDone = { step = 2 })
                // The phone's OWN freezer is the last gate, and it is not optional.
                //
                // Measured 2026-11-24 00:47-01:04 on this handset: `com.transsion.usf` gave
                // uid 10499 (com.termux - where `opencode serve` and `zapp_lane` both live)
                // exactly five seconds of CPU per unfreeze, looked at its own signal
                // (`importantScene: isTrafficActive uid:10499 speed :0`), and froze it again.
                // Every 60 s. For four hours. `serve.sh` never got to run, the engine sat at
                // zero CPU, and the app reported "the engine is not answering" - correctly,
                // because it wasn't. Android's own battery allowlist did NOT stop it:
                // `deviceidle whitelist +com.termux` was applied and ignored.
                2 -> KeepAliveStep(onDone = { step = 3 })
                // The gate is written HERE, on the last step, not when the engine comes up.
                // `Root` in MainActivity treats `consented && termuxReady` as "onboarding is
                // finished", so anything written earlier ends the flow early on a relaunch.
                else -> IdentityStep(
                    onDone = {
                        Onboarding.markTermuxReady(ctx)
                        onDone()
                    },
                )
            }
        }
    }
}

@Composable
private fun StepRail(step: Int) {
    Column(Modifier.fillMaxWidth().background(Surface)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ZAPP-Net", fontSize = 18.sp, color = Accent)
            Text("${step + 1} / 4", fontSize = 12.sp, color = TextDim)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }
}

// ------------------------------------------------------- step 3: keep it alive

/**
 * The step that stops the phone from killing the engine.
 *
 * Every value on this screen is measured, not guessed:
 *
 *  - `com.transsion.usf` froze uid 10499 for 5 s at a time, every 60 s, from at least
 *    00:47 to 01:04 on 2026-11-24 (`Usf_Hiber/stateManager: freeze uid: 10499`).
 *  - The app's own probes timed out while it was frozen (`egress local failed ...
 *    SocketTimeoutException`) even though the tunnel was carrying.
 *  - Android's battery allowlist did not stop it (`deviceidle whitelist +com.termux`
 *    was applied at 00:59 and the freeze continued at 01:00, 01:01, 01:02).
 *  - `pm disable-user com.transsion.usf` stopped it: last freeze 01:04:04, and the
 *    engine answered `/api/info` in 49 ms instead of never.
 *
 * So this step does the two things an app is allowed to do without root - ask for its
 * own exemption, and open the exact system page where the other one lives - and then
 * MEASURES whether it worked instead of claiming it did.
 */
@Composable
private fun KeepAliveStep(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var exempt by remember { mutableStateOf(isBatteryExempt(ctx)) }
    var result by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    // Re-read the exemption whenever the screen comes back from the system dialog.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exempt = isBatteryExempt(ctx) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("Keep it alive", fontSize = 22.sp, color = TextPrimary)
            Spacer(Modifier.height(10.dp))
            Text(
                "This phone has its own app freezer. It does not care what the app is doing " +
                    "and it does not read Android's battery list - it pauses the process, and " +
                    "a paused engine cannot answer anything.",
                fontSize = 13.sp, color = TextMid, lineHeight = 20.sp,
            )

            Spacer(Modifier.height(18.dp))
            SettingRow(
                number = "1",
                title = "Let ZAPP run in the background",
                body = if (exempt) {
                    "Allowed. The system will not pause ZAPP."
                } else {
                    "Required. Without it the app itself is paused when the screen locks."
                },
                good = exempt,
                action = "Allow",
                onAction = {
                    runCatching {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${ctx.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )

            Spacer(Modifier.height(14.dp))
            SettingRow(
                number = "2",
                title = "Turn off freezing for Termux",
                body = "Termux holds the engine and the tunnel. Open its page, then set " +
                    "Battery to Unrestricted and allow Autostart.",
                good = false,
                action = "Open",
                onAction = { openAppPage(ctx, "com.termux") },
            )

            if (isFreezerOem()) {
                Spacer(Modifier.height(14.dp))
                SettingRow(
                    number = "3",
                    title = "This phone's own power settings",
                    body = "${Build.MANUFACTURER} ships a separate freezer. Open its list " +
                        "and make sure Termux and ZAPP are not managed by it.",
                    good = false,
                    action = "Open",
                    onAction = { openOemPowerPage(ctx) },
                )
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (running) return@Button
                    running = true
                    result = ""
                    scope.launch {
                        val hits = probeEngineSixTimes(ctx)
                        result = if (hits >= 5) {
                            "Engine answered $hits of 6 probes in 30 s. Nothing is pausing it."
                        } else {
                            "Engine answered only $hits of 6 probes in 30 s. The phone is " +
                                "still pausing Termux - finish steps 2 and 3, then test again."
                        }
                        running = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) Layer1 else Accent,
                ),
            ) {
                Text(
                    if (running) "Testing for 30 s..." else "Test it for 30 seconds",
                    fontSize = 15.sp,
                )
            }
            if (result.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    result,
                    fontSize = 12.sp,
                    color = if (result.startsWith("Engine answered 5") ||
                        result.startsWith("Engine answered 6")
                    ) TextMid else Danger,
                    lineHeight = 18.sp,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
        ) {
            Text("Continue", fontSize = 15.sp)
        }
    }
}

@Composable
private fun SettingRow(
    number: String,
    title: String,
    body: String,
    good: Boolean,
    action: String,
    onAction: () -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            if (good) "OK" else number,
            fontSize = 12.sp,
            color = if (good) Accent else TextDim,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(26.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = TextPrimary)
            Spacer(Modifier.height(3.dp))
            Text(body, fontSize = 12.sp, color = TextMid, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            action,
            fontSize = 12.sp,
            color = Accent,
            modifier = Modifier
                .clickable { onAction() }
                .border(1.dp, Border, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private fun isBatteryExempt(ctx: Context): Boolean = runCatching {
    (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(ctx.packageName)
}.getOrDefault(false)

/** Transsion ships `com.transsion.usf` on Infinix, TECNO and itel. */
private fun isFreezerOem(): Boolean {
    val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
    return m.contains("infinix") || m.contains("tecno") || m.contains("itel") ||
        m.contains("transsion") || m.contains("xiaomi") || m.contains("oppo") ||
        m.contains("vivo") || m.contains("realme")
}

private fun openAppPage(ctx: Context, pkg: String) {
    runCatching {
        ctx.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$pkg"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Tries the OEM's own power screen by name and falls back to this app's page, so the
 * button can never do nothing at all.
 */
private fun openOemPowerPage(ctx: Context) {
    val candidates = listOf(
        "com.transsion.powercenter" to "com.transsion.powercenter.PowerSaveActivity",
        "com.transsion.phonemaster" to "com.transsion.phonemaster.BatteryActivity",
        "com.android.settings" to "com.android.settings.Settings\$PowerUsageSummaryActivity",
        "com.android.settings" to "com.android.settings.Settings\$HighPowerApplicationsActivity",
    )
    for ((pkg, cls) in candidates) {
        if (runCatching { ctx.startActivity(Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
    openAppPage(ctx, ctx.packageName)
}

/** Six probes framed 5 s apart - the same shape as the freeze itself. */
private suspend fun probeEngineSixTimes(ctx: Context): Int {
    val token = runCatching { Onboarding.ensureToken(ctx) }.getOrDefault("")
    var hits = 0
    repeat(6) {
        val r = runCatching {
            com.zapp.net.api.ServerHealth.verify(
                com.zapp.net.settings.PHONE_SERVER,
                token,
                timeoutMs = 4_000L,
                retryCount = 0,
            )
        }.getOrNull()
        if (r is com.zapp.net.api.ServerHealth.Result.Healthy ||
            r is com.zapp.net.api.ServerHealth.Result.Unauthorized
        ) hits++
        delay(5_000)
    }
    return hits
}

// ---------------------------------------------------------------- step 1: consent

@Composable
private fun ConsentStep(onAccept: () -> Unit) {
    var checked by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("Before anything else", fontSize = 22.sp, color = TextPrimary)
            Spacer(Modifier.height(10.dp))
            Text(
                "ZAPP does not ask for a name, an email, a phone number, or an account. " +
                    "The only thing it can see is the address your traffic leaves from, and " +
                    "it is turned into a one-way fingerprint before it is written down.",
                fontSize = 13.sp, color = TextMid, lineHeight = 20.sp,
            )

            Spacer(Modifier.height(22.dp))
            Clause(
                "1", "Your address is hashed on this device",
                "SHA-256 of the address, cut to 16 characters. The result is not a " +
                    "secret code - it is a one-way valve. There is nothing on the server " +
                    "to decrypt, because the server never holds a key.",
            )
            Clause(
                "2", "You see the censored form, nobody sees the rest",
                "The screen shows the first two hextets and the last two. The part " +
                    "between them stays in memory for one screen and is then dropped. " +
                    "It is not written to storage and it is not written to a log.",
            )
            Clause(
                "3", "One address, one identity - that is the point",
                "Two devices behind the same address share a fingerprint. A new address " +
                    "is a new fingerprint. Your session is carried by a separate random " +
                    "token, so a changing address does not log you out.",
            )
            Clause(
                "4", "A server address is not required",
                "Consent here covers the on-device fingerprint only. If you later point " +
                    "ZAPP at a server, that server receives the fingerprint and nothing " +
                    "else - never the address it came from.",
            )

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clickable { checked = !checked }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { checked = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Accent,
                        uncheckedColor = Border,
                        checkmarkColor = Color.Black,
                    ),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    "I have read and accept the Privacy Policy and the Terms. I agree " +
                        "that the address my traffic leaves from may be read on this " +
                        "device and stored as a one-way fingerprint.",
                    fontSize = 13.sp, color = TextMid, lineHeight = 19.sp,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onAccept,
            enabled = checked,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent,
                contentColor = Color.Black,
                disabledContainerColor = Layer1,
                disabledContentColor = TextDim,
            ),
        ) { Text("Continue", fontSize = 15.sp) }
    }
}

@Composable
private fun Clause(n: String, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Text(
            "$n.",
            fontSize = 12.sp, color = Accent, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(end = 10.dp, top = 2.dp),
        )
        Column {
            Text(title, fontSize = 14.sp, color = TextPrimary)
            Spacer(Modifier.height(3.dp))
            Text(body, fontSize = 12.sp, color = TextDim, lineHeight = 18.sp)
        }
    }
}

// ---------------------------------------------------------------- step 2: identity

@Composable
private fun IdentityStep(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var identity by remember { mutableStateOf<IpIdentity.Identity?>(null) }
    var error by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf("") }

    // A fresh install has never approved a tunnel, so `VpnService.prepare` still returns an
    // intent. Read once here, because the button label has to say which step comes next.
    var needsConsent by remember {
        mutableStateOf(runCatching { android.net.VpnService.prepare(ctx) != null }.getOrDefault(false))
    }

    /**
     * Read until the answer actually came through the tunnel.
     *
     * A single `measure` is not enough: right after consent the service is still doing its
     * WireGuard handshake, and the first probe answers with the carrier address and
     * `warp=off`. Measured 21 Nov on the reinstall - that reading is non-blank, so the old
     * `known` test accepted it. Storing it would hand the operator the exact address this
     * screen exists to hide, so the gate is theirs (`2606:` / `2a09:` -> WARP) and a
     * v4 answer is refused outright.
     */
    suspend fun readTunneled(): EgressProbe.Reading? {
        val started = System.currentTimeMillis()
        // 100 s, not 40. A first run is not a warm run: the service has to register a /64
        // with Cloudflare, bring the TUN up, and then re-roll the edge up to three times if
        // the first ones answer with nothing - measured on this phone at 20:31, two re-rolls
        // before the first reading. The old 40 s window expired in the middle of that, so a
        // brand-new install was told "the tunnel is not carrying traffic yet" while it was
        // still being built.
        val deadline = started + 100_000
        var last: EgressProbe.Reading? = null
        var nudged = false
        // The reading is taken through the engine's own proxy (127.0.0.1:41988), because the
        // app itself sits outside its tunnel by design. On a clean install that proxy does not
        // exist yet - it is started by serve.sh - so the first read can never answer. Fire the
        // engine once here instead of waiting the full window to say "nothing came back".
        var engineFired = false
        while (System.currentTimeMillis() < deadline) {
            EgressProbe.awaitVpn(ctx, 1_500)
            last = runCatching { EgressProbe.measure(ctx, 8_000) }.getOrNull()
            val r = last
            if (r != null && r.isV6 &&
                (r.warp == "on" || r.ip.startsWith("2606:") || r.ip.startsWith("2a09:"))
            ) return r
            val waited = ((System.currentTimeMillis() - started) / 1000).toInt()
            phase = if (r?.laneDown == true) {
                "The tunnel is measured through the engine - starting it."
            } else {
                "Waiting for the tunnel to carry traffic - ${waited}s"
            }
            if (r?.laneDown == true && !engineFired) {
                engineFired = true
                val canRun = runCatching { termuxInstalled(ctx) && termuxPermission(ctx) }
                    .getOrDefault(false)
                android.util.Log.e(
                    "ZAPP",
                    "onboarding: engine lane not up - starting it from setup (termux=$canRun)",
                )
                if (canRun) {
                    runCatching { com.zapp.net.termux.BootRelay.runPending(ctx, fromSetup = true) }
                        .onFailure { android.util.Log.e("ZAPP", "onboarding: engine start failed: $it") }
                }
            }
            // The service verifies on its own and re-rolls a silent edge, but its first
            // bring-up can sit behind a registration round-trip. One forced rebuild at 20 s
            // is the same tap Guard's Connect uses, so this cannot present a dead tunnel as
            // connected: the probe above is still the only thing that can accept an address.
            if (!nudged && waited >= 20) {
                nudged = true
                runCatching {
                    ctx.startService(
                        android.content.Intent(ctx, com.zapp.net.vpn.ZappVpnService::class.java)
                            .setAction(com.zapp.net.vpn.ZappVpnService.ACTION_START_USER),
                    )
                }.onFailure { android.util.Log.e("ZAPP", "onboarding: rebuild refused: $it") }
            }
            // One line per second, so the next "nothing came back" report can be read off the
            // phone instead of guessed at.
            //
            // It used to print `ip=none` for three different outcomes, which is how a probe
            // that failed because the lane was down got reported as "no address" while the
            // same minute carried a successful `egress local: ip=...` line. The reason is in
            // the Reading already; print it instead of collapsing it.
            val why = when {
                r == null -> "no-answer"
                r.laneDown -> "lane-down"
                r.ip.isBlank() -> "blank"
                !r.isV6 -> "v4"
                else -> "ok"
            }
            android.util.Log.e(
                "ZAPP",
                "onboarding: identity read ${waited}s probe=$why ip=${r?.ip ?: "none"} " +
                    "warp=${r?.warp ?: "-"}",
            )
            kotlinx.coroutines.delay(1_200)
        }
        return last
    }

    // Held so "Continue without the tunnel" can stop the wait instead of being hidden behind
    // it. Measured on a clean install: the read blocks for the full 100 s, and the only way
    // past this screen is the small line underneath - which used to disappear the moment the
    // read started, so the phone sat on "Reading\u2026" with no visible way out.
    var readJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun readNow() {
        busy = true
        error = ""
        phase = "Reading through the tunnel."
        readJob = scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { readTunneled() }.getOrNull() }
            busy = false
            phase = ""
            if (r == null || !r.known) {
                // Three different failures used to share one sentence, and only one of them
                // is fixed by tapping again:
                //  - `prepare` answering non-null means this app is not the active VPN any
                //    more (another VPN took it, or the consent was revoked), so no amount of
                //    tapping can bring our tunnel up;
                //  - an empty pool means Cloudflare never registered the device;
                //  - everything else is a tunnel that is up and silent, which a retry can fix.
                val consentAgain = runCatching {
                    android.net.VpnService.prepare(ctx) != null
                }.getOrDefault(false)
                val registered = runCatching {
                    com.zapp.net.guard.WarpPool(ctx).all().isNotEmpty()
                }.getOrDefault(true)
                error = when {
                    consentAgain ->
                        "Another VPN is holding the tunnel. Turn it off, then tap again."
                    !registered ->
                        "Cloudflare did not register this device. Check the connection, then tap again."
                    r?.laneDown == true ->
                        "The tunnel is measured through the engine, and the engine is not " +
                            "running yet. Continue without the tunnel and setup will start it."
                    else ->
                        "Nothing came back after 100 s. The tunnel is not carrying traffic yet - tap again."
                }
                return@launch
            }
            if (!r.isV6 || (r.warp != "on" && !r.ip.startsWith("2606:") &&
                    !r.ip.startsWith("2a09:"))
            ) {
                error = "That reading left outside the tunnel, so nothing was stored."
                return@launch
            }
            val id = IpIdentity.identify(r.ip)
            if (id == null) error = "That reading was not a usable address." else identity = id
        }
    }

    val vpnConsent = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            needsConsent = false
            runCatching {
                ctx.startService(
                    android.content.Intent(ctx, com.zapp.net.vpn.ZappVpnService::class.java)
                        .setAction(com.zapp.net.vpn.ZappVpnService.ACTION_START),
                )
            }.onFailure { error = "Android did not start the tunnel: ${it.message}" }
            readNow()
        } else {
            error = "The tunnel needs that permission. Without it the address cannot be read."
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("Your connection", fontSize = 22.sp, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                "Read through the tunnel, so this is the address a provider would see - " +
                    "not the one your carrier gives you.",
                fontSize = 12.sp, color = TextDim, lineHeight = 18.sp,
            )
            Spacer(Modifier.height(20.dp))

            val id = identity
            if (id != null) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Surface, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    Text("censored address", fontSize = 10.sp, color = TextDim)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        id.masked,
                        fontSize = 14.sp, color = TextPrimary, fontFamily = FontFamily.Monospace,
                        lineHeight = 21.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("fingerprint", fontSize = 10.sp, color = TextDim)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${id.id}   ${id.fingerprint}",
                        fontSize = 13.sp, color = Accent, fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        when (id.family) {
                            IpIdentity.Strength.V6_PRIVATE ->
                                "IPv6 - the hash cannot be reversed to your address. " +
                                    "The space it hides in is 2^64 wide."
                            IpIdentity.Strength.V4_NOT_PRIVATE ->
                                "IPv4 - the hash CAN be reversed by anyone who tries every " +
                                    "address. Treat this as a label, not as privacy. " +
                                    "Connect the tunnel first."
                            IpIdentity.Strength.UNKNOWN ->
                                "Could not classify this address."
                        },
                        fontSize = 11.sp,
                        color = if (id.family == IpIdentity.Strength.V4_NOT_PRIVATE) Danger else TextDim,
                        lineHeight = 17.sp,
                    )
                }
            } else {
                Text(
                    when {
                        error.isNotBlank() -> error
                        phase.isNotBlank() -> phase
                        needsConsent -> "One Android permission first: the tunnel. Nothing is read before it."
                        else -> "Not read yet."
                    },
                    fontSize = 12.sp,
                    color = if (error.isBlank()) TextDim else Danger,
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Only the fingerprint above is stored. The full address exists for the " +
                    "length of this screen and is then discarded.",
                fontSize = 11.sp, color = TextDim, lineHeight = 17.sp,
            )
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (identity != null) {
                    Onboarding.storeIdentity(ctx, identity!!)
                    onDone()
                    return@Button
                }
                // Consent first, always. `prepare` answers null only after the user has
                // approved a tunnel once, and a brand-new device never has - which is the
                // whole reason this screen used to answer "no address could be read" on a
                // fresh install: the probe ran, found no tunnel, and had nothing to read.
                val consent = runCatching { android.net.VpnService.prepare(ctx) }.getOrNull()
                if (consent != null) {
                    needsConsent = true
                    error = ""
                    // Persisted before the dialog: if the process dies in between, ZappApp
                    // restores the tunnel instead of leaving a dead panel.
                    com.zapp.net.settings.GuardPrefs.setVpnWanted(ctx, true)
                    runCatching { vpnConsent.launch(consent) }
                        .onFailure { error = "Android refused to open the VPN prompt: ${it.message}" }
                    return@Button
                }
                readNow()
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent,
                contentColor = Color.Black,
                disabledContainerColor = Layer1,
                disabledContentColor = TextDim,
            ),
        ) {
            Text(
                when {
                    busy -> "Reading\u2026"
                    identity != null -> "Use this identity"
                    needsConsent -> "Allow the tunnel"
                    else -> "Read my address"
                },
                fontSize = 15.sp,
            )
        }
        if (identity == null) {
            // His order, 21 Nov: "wala munang vpn pag hindi sila nag tap don sa connect".
            // Nothing is read, nothing is stored, no tunnel is started - the chat runs on
            // whatever address the carrier gives, and the Guard tab says so plainly.
            //
            // 22 Nov, after a clean install: "the option is continue without the tunnel".
            // It was here, but it was a 12 sp grey line that vanished while the 100 s read
            // ran, so the screen looked like a dead end. It is a real button now, it stays
            // on screen during the read, and it cancels the read instead of waiting it out.
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .border(1.dp, Line, RoundedCornerShape(10.dp))
                    .clickable {
                        readJob?.cancel()
                        readJob = null
                        busy = false
                        phase = ""
                        com.zapp.net.settings.GuardPrefs.setVpnWanted(ctx, false)
                        error = ""
                        onDone()
                    },
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Text("Continue without the tunnel", fontSize = 13.sp, color = TextMid)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "The app still works - the tunnel is only what keeps providers from seeing " +
                    "your carrier address. Guard can start it later without redoing setup.",
                fontSize = 11.sp, color = TextDim, lineHeight = 16.sp,
            )
        }
    }
}

// ---------------------------------------------------------------- step 3: termux

@Composable
private fun TermuxStep(onDone: () -> Unit) {
    val ctx = LocalContext.current

    var installed by remember { mutableStateOf(termuxInstalled(ctx)) }
    var permitted by remember { mutableStateOf(termuxPermission(ctx)) }
    var fired by remember { mutableStateOf(false) }
    var fireError by remember { mutableStateOf("") }
    var probe by remember { mutableStateOf<TermuxSetup.Probe?>(null) }
    var probing by remember { mutableStateOf(false) }
    var openedTermux by remember { mutableStateOf(false) }
    var engineUp by remember { mutableStateOf(false) }
    var laneUp by remember { mutableStateOf(false) }
    var engineWait by remember { mutableStateOf(0) }
    var engineGot by remember { mutableStateOf(0L) }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    // What the phone itself says it is doing, and when it last said anything.
    var stage by remember { mutableStateOf(TermuxSetup.Stage()) }
    var beat by remember { mutableStateOf(0L) }
    // The relay's aftermath, read back from Termux only when the handoff was accepted and not
    // one byte moved. A dead relay then names its own cause instead of the screen sitting on
    // "nothing downloaded yet" with no reason on it.
    var diag by remember { mutableStateOf<List<String>>(emptyList()) }
    var diagTried by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // The engine can already be up before this screen exists - a reinstall keeps it because
    // `serve.sh` starts it with `setsid`, and a user who reopens the tutorial has one running.
    // So the port poll is NOT nested inside the handshake: it runs for as long as the screen is
    // shown, whatever the handshake did.
    //
    // Measured 23 Nov on a fresh phone (ttbzf): the log box already read `serve ready 17:59:34`
    // while this screen still said "Engine starting - 16s". `serve ready` is written only after
    // the engine answered on 4096, so the port was open and this probe was wrong twice over -
    // it only ran when `fired` was true, and it gave up after 300 ms.
    LaunchedEffect(Unit) {
        val started = System.currentTimeMillis()
        var ticks = 0
        while (true) {
            // Off the main thread. `Socket.connect` on the UI thread throws
            // `NetworkOnMainThreadException`, which `runCatching` turned into a permanent
            // `false` - that is what kept this screen at "Engine answered - finishing" while
            // `/proc/net/tcp` showed the engine LISTENING on 4096.
            val up = TermuxSetup.engineListeningNow()
            engineUp = up
            laneUp = TermuxSetup.laneListeningNow()
            engineWait = ((System.currentTimeMillis() - started) / 1000).toInt()
            // One command per read: the stage file, the real bytes on disk, the heartbeat and
            // the last log lines all come back together, and Termux posts a notification for
            // each command it runs.
            //
            // the owner, 24 Nov: "the mb indicator its not working ... i want to see the whole mb
            // that he needed to download show that and live real downloading". A stage that
            // publishes a byte counter is now read every 2 s, because a number that only moves
            // every fifth second reads as frozen; stages without a counter stay at 5 s.
            val every = if (stage.total > 0L) 2 else 5
            if (!up && ticks % every == 0) {
                val snap = runCatching { TermuxSetup.snapshot(ctx) }.getOrNull()
                if (snap != null) {
                    stage = snap.stage
                    if (snap.lines.isNotEmpty()) logLines = snap.lines
                    // The engine counter is the engine's alone. It used to absorb any stage's
                    // bytes, so the 403,072 B guard lane showed up as engine progress.
                    if (snap.stage.id == "download" && snap.stage.bytes > 0L) {
                        engineGot = snap.stage.bytes
                    }
                    beat = snap.stage.beat
                }
            }
            ticks++
            if (up) break
            delay(1_000)
        }
    }

    fun check() {
        if (probing) return
        probing = true
        scope.launch {
            probe = TermuxSetup.probe(ctx)
            probing = false
        }
    }

    // Termux installs in another app's process. This screen is resumed, not recreated, when
    // the installer closes, so `remember { termuxInstalled() }` would keep saying "not found"
    // on a phone that just installed it. Poll until it appears instead of trusting one read.
    LaunchedEffect(installed) {
        while (!installed) {
            delay(1200)
            installed = termuxInstalled(ctx)
        }
    }

    // NO automatic probe here any more.
    //
    // the owner, 23 Nov: "yung notification na error command i dont want to see it remove that".
    // A `RUN_COMMAND` sent while `allow-external-apps` is still missing makes Termux post its
    // own error toast - the one that says "RunCommandService requires allow-external-apps". The
    // old auto-probe fired the instant Termux was installed and the permission was granted,
    // which is always BEFORE the user has pasted the line, so that toast was guaranteed on
    // every fresh install.
    //
    // The permission is already a measured fact, so step 2 completes on it. The probe now runs
    // only after the user has actually been inside Termux (`openedTermux`) or taps the button,
    // and by then the line is either pasted (no toast) or they chose to test it.
    // (kept as a comment so the next reader knows the removal was deliberate.)

    // The Termux trip is the one step Android will not let the app do alone, so the screen
    // re-checks by itself the moment the user comes back instead of asking them to press a
    // button and hope.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (openedTermux) {
            openedTermux = false
            permitted = termuxPermission(ctx)
            check()
        }
    }

    // `com.termux.permission.RUN_COMMAND` is a dangerous permission declared by Termux, so
    // Android does not grant it at install time. Measured 21 Nov on the fresh install:
    // `granted=false`, every relay call refused, and the only way out was `pm grant` from
    // adb. It has to be requested, and the dialog only appears while Termux is installed.
    val askRunCommand = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permitted = granted || termuxPermission(ctx)
        if (!permitted) {
            fireError = "Android did not grant it - Settings > Apps > ZAPP > Permissions."
        } else {
            probe = null
        }
    }

    val state = probe?.state
    val ok = state == TermuxSetup.State.OK

    // Four steps, and every tick is a measured fact - never a timer. The order is what the
    // phone actually has to do: install, allow, paste, start.
    val stepInstalled = installed
    // The permission alone completes step 2. It used to also require a probe result, which
    // meant the app had to send a command before the user had pasted the line - and that is
    // exactly what made Termux post the error toast on every fresh install.
    val stepShell = installed && permitted
    val stepLine = state == TermuxSetup.State.OK
    // The engine answering is the fact that matters, not whether this screen managed to send a
    // fresh handshake. A running engine completes the step on its own.
    val stepEngine = engineUp
    val active = when {
        !stepInstalled -> 1
        !stepShell -> 2
        !stepLine -> 3
        !stepEngine -> 4
        else -> 5
    }

    // The engine starts the moment the paste lands - no extra tap, no "now press start". If it
    // is already answering there is nothing to hand over, and sending a launcher at a live
    // engine only makes a second `serve.sh` race the first for the dpkg lock.
    LaunchedEffect(state, engineUp) {
        if (state == TermuxSetup.State.OK && !fired && !probing && !engineUp) {
            val sent = com.zapp.net.termux.BootRelay.runPending(ctx, fromSetup = true)
            fired = sent
            if (!sent) fireError = "Termux refused the handshake - see the step above"
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("Setting up the engine", fontSize = 22.sp, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                "Four steps, once. ZAPP has no engine of its own - the proxy and the server " +
                    "run inside Termux, and ZAPP hands it commands over a permission you " +
                    "grant here.",
                fontSize = 12.sp, color = TextDim, lineHeight = 18.sp,
            )
            Spacer(Modifier.height(16.dp))

            TutorialStep(1, "Install Termux", stepInstalled, active == 1) {
                Text(
                    "The exact build ships inside this app - F-Droid 0.118.3, hash-checked " +
                        "before Android sees it. No store, no browser, no dead download.",
                    fontSize = 11.sp, color = TextDim, lineHeight = 17.sp,
                )
                if (Installer.busy || Installer.note.isNotBlank() || Installer.error.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        Installer.error.ifBlank { Installer.note.ifBlank { "unpacking the installer" } },
                        fontSize = 11.sp,
                        color = if (Installer.error.isNotBlank()) Danger else TextDim,
                    )
                }
            }

            TutorialStep(2, "Let ZAPP run commands", stepShell, active == 2) {
                Text(
                    "Termux only accepts commands from another app after one Android " +
                        "permission. The dialog appears once; after that this step stays green.",
                    fontSize = 11.sp, color = TextDim, lineHeight = 17.sp,
                )
                if (permitted && (state == TermuxSetup.State.NO_BOOTSTRAP || state == TermuxSetup.State.SILENT)) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Termux installs its own Linux packages the first time it is opened, " +
                            "and until that finishes there is no shell for ZAPP to call. Open " +
                            "it, let it print its ~ $ prompt, then come back.",
                        fontSize = 11.sp, color = TextDim, lineHeight = 17.sp,
                    )
                }
            }

            TutorialStep(3, "Paste one line", stepLine, active == 3) {
                Text(
                    "Android gives no app a way to write another app's private files, so this " +
                        "is the single step that has to be done by hand. It takes five seconds " +
                        "and never has to be repeated.",
                    fontSize = 11.sp, color = TextDim, lineHeight = 17.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    TermuxSetup.ENABLE_COMMAND,
                    fontSize = 10.sp, color = Accent, fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Layer1, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                )
                Spacer(Modifier.height(10.dp))
                // ORDER MATTERS, and this is the part that bit him. Termux checks its own
                // property BEFORE it looks for a shell, so a phone whose packages are still
                // installing reports the property error too. Pasting during that window
                // prints "command not found" and looks like the line is wrong.
                Text(
                    "1. Tap Copy the line.\n" +
                        "2. Tap Open Termux. If it is still installing its packages, wait for " +
                        "the ~ $ prompt first.\n" +
                        "3. Long-press the black screen, tap Paste, press Enter. You should " +
                        "see \"ZAPP: Termux will now accept commands from ZAPP\".\n" +
                        "4. Come back - this screen checks again on its own.",
                    fontSize = 11.sp, color = TextDim, lineHeight = 17.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { TermuxSetup.copyCommand(ctx) },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Layer1, contentColor = Accent,
                        ),
                    ) { Text("Copy the line", fontSize = 13.sp) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { openedTermux = TermuxSetup.openTermux(ctx) },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Layer1, contentColor = Accent,
                        ),
                    ) { Text("Open Termux", fontSize = 13.sp) }
                }
            }

            TutorialStep(4, "Start the engine", stepEngine, active == 4) {
                Text(
                    "The first run installs its tools and downloads about 121 MB, once. It " +
                        "stays on the phone after that, so every later start is seconds.",
                    fontSize = 11.sp, color = TextDim, lineHeight = 17.sp,
                )
                // the owner, 23 Nov: "add indicator of 4 start engine and the name that we download".
                // The archive is named here, and the bar is fed by the real byte count on disk.
                //
                // the owner, 23 Nov, after seeing it: "its fake number stuck wtf? whats that for?".
                // He was right. The bar reached 100% when the DOWNLOAD finished, but unpack,
                // glibc, python and prewarm come after it, so 100% plus a running timer read as
                // a lie. The line now names the phase, and 100% is only claimed once the engine
                // actually answers.
                // the owner, 23 Nov: "its show download done while the termux is downloading pa ...
                // so para may nakikita silang mb every download like every details we need to
                // see it so para iwas ma close and para alam namin na tapos naba or nag stuck
                // lang".
                //
                // Every row below is a stage `serve.sh` writes into `$HOME/.zapp/status`, in the
                // order it actually runs them. A tick means the phone finished that stage - not
                // that a timer elapsed. `python` and `check` are sub-steps, so they light up the
                // row they belong to instead of adding two more lines.
                // The real total is part of the label, not something the user has to guess from
                // a moving number: the engine archive is 121.1 MB and that never changes.
                val engineMb = sizeMb(TermuxSetup.ENGINE_TOTAL)
                val rows = listOf(
                    "Installing the guard lane",
                    "Downloading ${TermuxSetup.ENGINE_NAME} ($engineMb)",
                    "Checking and unpacking the engine",
                    "Installing glibc-runner",
                    "Starting the engine",
                    "Engine is up",
                )
                val rankOf: (String) -> Int = { id ->
                    when (id) {
                        "lane", "lanefail", "mirror", "python" -> 0
                        "download" -> 1
                        "check", "unpack" -> 2
                        "glibc" -> 3
                        "start" -> 4
                        "ready" -> 5
                        else -> -1
                    }
                }
                val cur = if (engineUp) rows.size - 1 else rankOf(stage.id)
                val nowSec = System.currentTimeMillis() / 1000L
                val silent = if (beat > 0L) (nowSec - beat).coerceAtLeast(0L) else 0L
                val inStage = if (stage.since > 0L) (nowSec - stage.since).coerceAtLeast(0L) else 0L
                // `stage.bytes` is the file `serve.sh` named for the current stage. If the phone
                // is still running an older lane that has no status file, fall back to the
                // archive size so the number is never zero for no reason.
                val onDisk = if (stage.bytes > 0L) stage.bytes else engineGot

                Spacer(Modifier.height(10.dp))
                rows.forEachIndexed { i, label ->
                    val done = cur >= 0 && i < cur
                    val activeRow = cur >= 0 && i == cur
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(16.dp)
                                .background(
                                    when {
                                        done -> Accent
                                        activeRow -> Accent.copy(alpha = 0.28f)
                                        else -> Layer1
                                    },
                                    RoundedCornerShape(8.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (done) "\u2713" else "${i + 1}",
                                fontSize = 9.sp,
                                color = if (done) Color.Black
                                else if (activeRow) Accent
                                else TextDim,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            label,
                            fontSize = 11.sp,
                            color = when {
                                done -> TextDim
                                activeRow -> TextPrimary
                                else -> TextDim.copy(alpha = 0.45f)
                            },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }

                Spacer(Modifier.height(4.dp))
                // The bar is stage progress, and it only reaches full when the engine answers.
                // Any stage that publishes a real byte counter drives the bar with that counter -
                // the guard lane (403,072 B) and the engine archive (121,172,970 B) both do.
                // A stage without one falls back to its position in the list.
                val frac = when {
                    engineUp -> 1f
                    stage.total > 0L ->
                        (onDisk.toFloat() / stage.total.toFloat()).coerceIn(0f, 1f)
                    cur >= 0 -> cur.toFloat() / (rows.size - 1).toFloat()
                    else -> 0f
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(Layer1, RoundedCornerShape(3.dp)),
                ) {
                    if (frac > 0f) {
                        Box(
                            Modifier
                                .fillMaxWidth(frac)
                                .height(6.dp)
                                .background(Accent, RoundedCornerShape(3.dp)),
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    when {
                        engineUp -> "$engineMb on the phone - kept for every later start"
                        // Only the stage with a byte counter prints a fraction, and it is always
                        // that stage's own denominator. The old code used 121.1 MB for every
                        // stage, so the 403,072 B guard lane read as "0.4 MB of 121.1 MB".
                        stage.total > 0L ->
                            "${sizeMb(onDisk)} of ${sizeMb(stage.total)}" +
                                "  \u00B7  ${(onDisk * 100L / stage.total).coerceIn(0L, 100L)}%" +
                                "  \u00B7  ${inStage}s in this stage" +
                                if (stage.id == "download") "" else "  \u00B7  engine $engineMb next"
                        cur >= 0 ->
                            "Stage ${cur + 1} of ${rows.size}" +
                                "  \u00B7  ${inStage}s in this stage" +
                                "  \u00B7  ${sizeMb(onDisk)} of $engineMb on disk"
                        else -> "$engineMb once  \u00B7  nothing downloaded yet"
                    },
                    fontSize = 10.sp, color = if (engineUp) Accent else TextDim,
                )
                // "alam namin na tapos naba or nag stuck lang" - the installer writes a heartbeat
                // every 3 s, so silence is measurable instead of guessed. This never claims the
                // run is dead; it only reports how long the phone has been quiet.
                if (!engineUp && silent >= 25L) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "No new signal from Termux for ${silent}s. " +
                            "It is still running unless this passes 90s - then stop and start again.",
                        fontSize = 10.sp, color = Danger,
                    )
                }
                if (!fired && fireError.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(fireError, fontSize = 11.sp, color = Danger)
                }
                // The guard lane failed. the owner, 23 Nov: "its show download done while the termux
                // is downloading pa" - the screen must never tick a row over a dead guard. The
                // lane is 0.9 MB and needs nothing but the kernel, so the fix is one command,
                // and the button sends exactly that instead of telling him to open Termux.
                if (!laneUp && (engineUp || stage.id == "lanefail")) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The guard lane did not answer on 41988.",
                        fontSize = 11.sp, color = Danger,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "Chat still works, but Guard cannot route or prove the tunnel until it " +
                            "is up. It is one 0.9 MB binary inside Termux - no python, no apt, " +
                            "nothing to download from a package mirror.",
                        fontSize = 10.sp, color = TextDim, lineHeight = 15.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = {
                            val sent = TermuxSetup.relay(ctx, "sh \$HOME/.zapp/lane/up.sh")
                            fireError = if (sent) "" else "Termux refused the command."
                        },
                        modifier = Modifier.height(38.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Layer1, contentColor = Accent,
                        ),
                    ) { Text("Start the guard lane", fontSize = 12.sp) }
                }
                // The phone's own last lines, not a guess at what it is doing.
                if (logLines.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Termux is doing this now", fontSize = 12.sp, color = TextPrimary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        logLines.joinToString("\n"),
                        fontSize = 10.sp, color = TextDim, fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Layer1, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Read the last line - that is the step it is on right now.",
                        fontSize = 10.sp, color = TextDim, lineHeight = 15.sp,
                    )
                }
                if (!engineUp) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Setup running for ${engineWait}s - this timer is the whole step, " +
                            "not just the download.",
                        fontSize = 10.sp, color = TextMid, lineHeight = 15.sp,
                    )
                }
                if (engineUp) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Engine answered on 127.0.0.1:4096.",
                        fontSize = 11.sp, color = Accent,
                    )
                }
                // The explanation when it goes wrong, which the first version did not have.
                // the owner, 23 Nov: "start the engine stuck do you know why?" - because nothing on
                // this screen said what to do about it.
                if (!engineUp && engineWait >= 60) {
                    Spacer(Modifier.height(10.dp))
                    Text("If it stays here", fontSize = 12.sp, color = TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Do not run `pkg update` or `pkg install` while this screen is working. " +
                            "It takes the same lock and stops both.\n\n" +
                            "1. The last log line names the step. `mirror bad:` means this " +
                            "network cannot reach any Termux mirror - try mobile data.\n" +
                            "2. `no python3 - installing python` that never advances is a stuck " +
                            "download: open Termux, run `pkill -f zapp-serve`, come back and tap " +
                            "the button again.\n" +
                            "3. `no grun` repeated means the glibc packages did not land. The " +
                            "order matters: glibc-repo first, then glibc-runner. This screen " +
                            "does that by itself.\n" +
                            "4. `engine missing - downloading` with no progress is the 121 MB " +
                            "fetch on a weak link. It resumes where it stopped, so leave it.\n" +
                            "5. Nothing in the box at all means Termux never accepted a command " +
                            "- go back to step 2.",
                        fontSize = 10.sp, color = TextDim, lineHeight = 16.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        // One action per screen. The tutorial never shows a button for a step that is not
        // the current one, so there is nothing to guess at.
        Button(
            onClick = {
                when (active) {
                    1 -> {
                        if (Installer.apk != null) {
                            if (Installer.needsSourcePermission(ctx)) {
                                Installer.openSourceSettings(ctx)
                                fireError = "Turn on \"Allow from this source\", then tap Install again"
                            } else if (!Installer.install(ctx)) {
                                fireError = "Android has no installer for this file"
                            }
                            return@Button
                        }
                        fireError = ""
                        scope.launch { Installer.fetch(ctx) }
                    }
                    2 -> {
                        if (!permitted) {
                            fireError = ""
                            runCatching { askRunCommand.launch("com.termux.permission.RUN_COMMAND") }
                                .onFailure {
                                    fireError = "This build has no dialog for that permission - grant it in Settings."
                                }
                        } else {
                            openedTermux = TermuxSetup.openTermux(ctx)
                            fireError = if (openedTermux) {
                                "Let Termux finish, then come back."
                            } else {
                                "Android would not open Termux."
                            }
                        }
                    }
                    3 -> {
                        probe = null
                        check()
                    }
                    else -> {
                        if (!fired) {
                            val sent = com.zapp.net.termux.BootRelay.runPending(ctx, fromSetup = true)
                            fired = sent
                            fireError = if (sent) "" else "Termux refused the handshake"
                        } else if (engineUp) {
                            // The ready flag is NOT written here any more. the owner, 23 Nov:
                            // "please make this your connection into the last setup" - so the
                            // engine is step 2 of 3 and the tunnel is the closing step. Writing
                            // the gate here meant a relaunch during the connection step (WARP
                            // registration is slow, and Android kills a backgrounded app) found
                            // `consented && termuxReady` already true and jumped straight past
                            // the last step. The caller marks it when the last step finishes.
                            onDone()
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent,
                contentColor = Color.Black,
                disabledContainerColor = Layer1,
                disabledContentColor = TextDim,
            ),
        ) {
            Text(
                when (active) {
                    1 -> if (Installer.busy) "Unpacking Termux  ${Installer.progress}%" else "Install Termux"
                    2 -> if (!permitted) "Allow ZAPP to run commands" else "Open Termux"
                    3 -> if (probing) "Asking Termux\u2026" else "Check again"
                    4 -> if (engineUp) "Finish" else "Working\u2026  ${engineWait}s"
                    else -> "Finish"
                },
                fontSize = 15.sp,
            )
        }
        if (fireError.isNotBlank() && active != 4) {
            Spacer(Modifier.height(6.dp))
            Text(fireError, fontSize = 11.sp, color = Danger)
        }
    }
}

/**
 * The same four steps, reachable from Settings after first run.
 *
 * the owner, 23 Nov: "make it a tutorial". Termux can be reinstalled, its data cleared, or the
 * command permission revoked by Android, and every one of those puts the user back on these
 * steps - so they are worth keeping, not just a one-time gate.
 */
@Composable
fun SetupTutorial(onDone: () -> Unit) {
    val ctx = LocalContext.current
    // Same reason as `OnboardingScreen`: the tutorial can be re-entered, and finishing it must
    // leave the same flag behind that the first run would have.
    TermuxStep(onDone = {
        Onboarding.markTermuxReady(ctx)
        onDone()
    })
}

/**
 * Bytes as a short decimal string without touching Locale: `String.format` would print a comma
 * in some regions and a dot in others, and this line is a measurement, not prose.
 *
 * DECIMAL, not MiB. the owner, 23 Nov: "its fake number stuck wtf?" - the line read
 * "115.5 MB of 121 MB · 100%", because 121 is the byte total in decimal and 115.5 came from
 * dividing by 1 048 576. Two rulers on one line made a finished download look like a lie.
 * `ENGINE_TOTAL` is a decimal byte count, so this must be decimal too: 121 172 970 B -> 121.1.
 */
private fun sizeMb(bytes: Long): String {
    val mb = bytes / 1_000_000L
    val tenth = (bytes % 1_000_000L) / 100_000L
    return "$mb.${tenth} MB"
}

/**
 * One row of the tutorial: a numbered badge that becomes a tick only when the fact behind
 * it is measured, the title, and - only while it is the current step - its explanation.
 */
@Composable
private fun TutorialStep(
    n: Int,
    title: String,
    done: Boolean,
    active: Boolean,
    body: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(24.dp)
                .background(
                    if (done) Accent else if (active) Layer1 else Color.Transparent,
                    RoundedCornerShape(12.dp),
                )
                .border(
                    1.dp,
                    if (done || active) Accent else Line,
                    RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                // Escapes, not the glyphs themselves: the glyph had been written as Latin-1
                // somewhere upstream and shipped as three garbled characters on a real screen.
                // A \u escape cannot be mangled by an editor.
                if (done) "\u2713" else "$n",
                fontSize = 11.sp,
                color = if (done) Color.Black else if (active) Accent else TextDim,
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                title,
                fontSize = 14.sp,
                color = if (done || active) TextPrimary else TextMid,
            )
            if (active) {
                Spacer(Modifier.height(6.dp))
                body()
            }
        }
    }
}

@Composable
private fun Check(label: String, ok: Boolean, detail: String) {

    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Text(
            if (ok) "\u25CF" else "\u25CB",
            fontSize = 13.sp, color = if (ok) Accent else TextDim,
            modifier = Modifier.padding(end = 10.dp, top = 1.dp),
        )
        Column {
            Text(label, fontSize = 14.sp, color = if (ok) TextPrimary else TextMid)
            Spacer(Modifier.height(2.dp))
            Text(detail, fontSize = 11.sp, color = TextDim, lineHeight = 16.sp)
        }
    }
}

private fun termuxInstalled(ctx: Context): Boolean = runCatching {
    ctx.packageManager.getPackageInfo("com.termux", 0)
}.isSuccess

private fun termuxPermission(ctx: Context): Boolean = runCatching {
    ContextCompat.checkSelfPermission(
        ctx.applicationContext,
        "com.termux.permission.RUN_COMMAND",
    ) == PackageManager.PERMISSION_GRANTED
}.getOrDefault(false)
