package com.zapp.net.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapp.net.guard.EdgePins
import com.zapp.net.guard.GuardState
import com.zapp.net.guard.RotationEngine
import com.zapp.net.guard.WarpPool
import com.zapp.net.settings.GuardPrefs
import com.zapp.net.ui.theme.Accent
import com.zapp.net.ui.theme.Bad
import com.zapp.net.ui.theme.Bg
import com.zapp.net.ui.theme.Border
import com.zapp.net.ui.theme.Good
import com.zapp.net.ui.theme.Line
import com.zapp.net.ui.theme.Surface
import com.zapp.net.ui.theme.Surface2
import com.zapp.net.ui.theme.TextDim
import com.zapp.net.ui.theme.TextFaint
import com.zapp.net.ui.theme.TextPrimary
import com.zapp.net.ui.theme.Warn
import com.zapp.net.vpn.ZappVpnService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The cockpit. Every control here is ported from the reference panel
 * (`Opencode-Unlimited-Limit-Bypass/src/ui/index.html`); the layout and skin are ours.
 *
 * Ported one-for-one: overall status pill + Refresh, egress card, pinned edge + Re-roll,
 * Rotate /64 Now (Hop), Connect/Disconnect, Deep Reset, Fix WARP (Reset Keys),
 * health card + Fix Everything, the five protection switches, stats, tabbed logs.
 *
 * Not ported, because the desktop control has no Android counterpart — listed at the
 * bottom of the screen with the reason, never faked:
 *   #chkServiceProxy  the server reads `service.json` itself; no toggle to wire
 *   #chkFirewall      no firewall API without root (strict fail-closed stands in)
 *   Fix Port Conflict our server is Termux-owned, we do not administer its port
 *   Launch Desktop/CLI / PATH shim / Install / Uninstall
 *
 * `#chkStrict` IS ported as the "Strict Fail-Closed" switch: it writes the flag file
 * `zen_proxy._strict_mode():743-746` reads. `#chkAutoResume` is gone on purpose — there
 * is no auto-send left to switch on.
 */
@Composable
fun GuardScreen() {
    val st by GuardState.status.collectAsState()
    val events by GuardState.events.collectAsState()
    val pool by GuardState.pool.collectAsState()
    val blacklist by GuardState.blacklist.collectAsState()
    // the one-tap repair publishes its own stage list, so the loader below is driven by the
    // pipeline rather than by a timer that only looks busy
    val repair by GuardState.repair.collectAsState()
    val repairRunning by GuardState.repairRunning.collectAsState()
    val repairNote by GuardState.repairNote.collectAsState()
    val repairStartedMs by GuardState.repairStartedMs.collectAsState()
    val ctx = LocalContext.current
    val prefs by GuardPrefs.state.collectAsState()
    val tor by com.zapp.net.net.TorLane.state.collectAsState()
    val px by com.zapp.net.net.EgressProxy.stats.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // ---- Termux access ----
    // The one thing Android will not let an app grant itself: Termux refuses commands from
    // other apps until `allow-external-apps` sits in its own settings file, and it refuses
    // them outright when ZAPP does not hold its RUN_COMMAND permission. The old build
    // reported both as "check allow-external-apps", which is why users edited a file that
    // was never the problem. This card asks Termux for its own answer instead.
    var termProbe by remember { mutableStateOf<com.zapp.net.termux.TermuxSetup.Probe?>(null) }
    var termBusy by remember { mutableStateOf(false) }
    var termCopied by remember { mutableStateOf(false) }
    fun termCheck() {
        if (termBusy) return
        termBusy = true
        scope.launch {
            termProbe = com.zapp.net.termux.TermuxSetup.probe(ctx)
            termBusy = false
        }
    }
    val askTerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { termCheck() }
    LaunchedEffect(Unit) { termCheck() }

    var busy by remember { mutableStateOf<String?>(null) }
    var doc by remember { mutableStateOf<List<com.zapp.net.guard.Doctor.Check>>(emptyList()) }
    var docAt by remember { mutableStateOf(0L) }
    var keeperLog by remember { mutableStateOf<List<String>>(emptyList()) }
    var logTab by remember { mutableStateOf("guard") }
    // their keeper watch log is a file on the desktop; ours is app-private (ProxyKeeper.log),
    // read when the tab opens instead of on every recomposition
    LaunchedEffect(logTab) {
        if (logTab == "keeper") keeperLog = com.zapp.net.guard.ProxyKeeper.tail(ctx)
    }
    var edgeIp by remember { mutableStateOf(EdgePins.edges().first()) }
    var edgePort by remember { mutableStateOf(EdgePins.ports().first()) }

    // the server's own usage endpoint, read once per visit
    var usage by remember { mutableStateOf<com.zapp.net.api.UsageEnvelope?>(null) }
    var usageLoaded by remember { mutableStateOf(false) }

    // where the usage/sign-in calls go, resolved once so the card can retry on its own
    var baseUrl by remember { mutableStateOf("") }
    var authTok by remember { mutableStateOf("") }

    // device-code sign-in - every new user brings their own OpenCode account, so this is the
    // only credential the app ever asks for, and it is never hardwired to anyone
    var connect by remember { mutableStateOf<com.zapp.net.api.ConnectStart?>(null) }
    var connectMsg by remember { mutableStateOf<String?>(null) }
    var connectBusy by remember { mutableStateOf(false) }
    // the owner, 13:52: "make it the copy link auto not manual" - the sign-in link is put on the
    // clipboard the moment the device code appears, so the button only ever re-copies it.
    var connectCopied by remember { mutableStateOf(false) }

    // their "STATS & SAVED WORK" numbers, from the server's own tables
    var projects by remember { mutableStateOf(-1) }
    var chats by remember { mutableStateOf(-1) }
    var savedWorkLoaded by remember { mutableStateOf(false) }

    // the proxy's own numbers, live: `zen_proxy.proxy_status` served on loopback
    var route by remember { mutableStateOf(com.zapp.net.guard.RouteStatus.Snapshot(false, "…")) }
    // a failed poll must not erase a good reading: keep the last success and its time
    var routeAt by remember { mutableStateOf(0L) }
    var routeErr by remember { mutableStateOf("") }
    var wpFailed by remember { mutableStateOf(0) }
    var wpFree by remember { mutableStateOf(0) }
    val fmtHm = remember { SimpleDateFormat("HH:mm", Locale.US) }

    // The ROM's own killer, and the only honest way to show it: PowerManager is the same
    // source `dumpsys deviceidle whitelist` reads. Measured 21 Nov on his phone: com.termux
    // was on the list, com.zapp.net was not.
    var batteryTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            batteryTick++
            kotlinx.coroutines.delay(2_000)
        }
    }
    val powerMgr = remember {
        ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
    }
    val zappExempt = remember(batteryTick) {
        runCatching { powerMgr?.isIgnoringBatteryOptimizations(ctx.packageName) == true }
            .getOrDefault(false)
    }
    val termuxExempt = remember(batteryTick) {
        runCatching { powerMgr?.isIgnoringBatteryOptimizations("com.termux") == true }
            .getOrDefault(false)
    }

    LaunchedEffect(Unit) {
        // the pool and blacklist live on disk; load them once so the card never opens at
        // "0 identities" when `warppool.json` actually holds an identity
        GuardState.refresh(WarpPool(ctx))
        while (true) {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.zapp.net.guard.RouteStatus.read()
            }
            if (r.ok) {
                route = r
                routeAt = System.currentTimeMillis()
                routeErr = ""
            } else if (route.ok) {
                // keep the last good snapshot; only remember why the newest read failed
                routeErr = r.error.ifBlank { "no response" }
            } else {
                route = r
            }
            wpFailed = com.zapp.net.guard.WallPause.failedHops(ctx)
            wpFree = com.zapp.net.guard.WallPause.freeWallHops(ctx)
            kotlinx.coroutines.delay(5_000L)
        }
    }

    LaunchedEffect(Unit) {
        GuardPrefs.load(ctx)
        val base = com.zapp.net.settings.observe(ctx).first().serverBase
            .ifBlank { com.zapp.net.settings.PHONE_SERVER }
        val tok = com.zapp.net.api.loadToken(ctx)
        baseUrl = base
        authTok = tok
        val c = com.zapp.net.api.OpenCodeClient(base, initialToken = tok.ifBlank { null })
        // their "STATS & SAVED WORK": `project` and `session_v2` counts, read from the
        // server that owns the database instead of Termux's private sqlite file
        runCatching { c.projectCount() }.onSuccess { projects = it }
        runCatching { c.sessionCount() }.onSuccess { chats = it }
        savedWorkLoaded = true
        // the usage call can take much longer than the counters; it must not hold them back
        runCatching { c.usageLimits() }.onSuccess { usage = it }
        usageLoaded = true
    }

    // Device-code polling. The server answers `pending` until the browser confirms, which is
    // exactly what their `provider.connect.console.waiting` line means; every other status is
    // terminal, so we re-read usage and report what actually happened instead of guessing.
    LaunchedEffect(connect?.attemptID, connectMsg) {
        val a = connect ?: return@LaunchedEffect
        var told = false
        while (!told) {
            kotlinx.coroutines.delay(1_500L)
            val c = com.zapp.net.api.OpenCodeClient(baseUrl, initialToken = authTok.ifBlank { null })
            val st = c.connectStatus("opencode", a.attemptID)
            val s = st?.status.orEmpty()
            if (s.isEmpty()) continue
            if (s == "pending") {
                val exp = a.time?.expires ?: 0L
                if (exp > 0L && System.currentTimeMillis() > exp) {
                    connect = null
                    connectMsg = "This sign-in request has expired. Start again to get a new device code."
                    told = true
                }
                continue
            }
            runCatching { c.usageLimits() }.onSuccess { usage = it }
            if (usage?.data != null) {
                connect = null
                connectMsg = null
            } else {
                connect = null
                connectMsg = when {
                    s.contains("den", true) -> "Access was denied in Console. Try again when you're ready to connect."
                    s.contains("expire", true) -> "This sign-in request has expired. Start again to get a new device code."
                    else -> "Couldn't check authorization. Check your server connection and try again."
                }
            }
            told = true
        }
    }

    // BUG FIXED 2026-11-21 (the owner: "when i tap the disconnect i cant tap the coonnect again").
    //
    // This callback used to be `{ }` - the consent result was thrown away. `VpnService.prepare()`
    // returns a non-null Intent whenever the app is not currently authorised (first run after
    // `pm clear`, a reinstall, or a revoked appop), so tapping Connect showed the system dialog,
    // he tapped OK, and NOTHING started the service. The button stayed on "Connect" and every
    // further tap just re-opened the dialog - a dead button.
    //
    // The result is now acted on: granted -> start the tunnel; denied -> drop the intent we
    // persisted in `enableTunnel()` so `ZappApp` does not keep restoring a tunnel he refused.
    val vpnConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            ctx.startService(Intent(ctx, ZappVpnService::class.java).setAction(ZappVpnService.ACTION_START_USER))
            GuardState.log("vpn", "panel: consent granted -> starting the tunnel")
        } else {
            com.zapp.net.settings.GuardPrefs.setVpnWanted(ctx, false)
            GuardState.log("vpn", "panel: consent denied - tunnel not started")
        }
    }

    fun enableTunnel() {
        // Persist the choice before the consent dialog: if the process dies between the tap
        // and the tunnel coming up, `ZappApp` restores it instead of leaving a dead panel.
        com.zapp.net.settings.GuardPrefs.setVpnWanted(ctx, true)
        val intent = VpnService.prepare(ctx)
        if (intent != null) vpnConsent.launch(intent)
        else ctx.startService(Intent(ctx, ZappVpnService::class.java).setAction(ZappVpnService.ACTION_START_USER))
    }

    fun stopTunnel() {
        com.zapp.net.settings.GuardPrefs.setVpnWanted(ctx, false)
        // Flip the panel immediately instead of waiting for the service to report back.
        //
        // The old flow relied entirely on `ZappVpnService` reaching `setVpnRunning(false)`
        // (line 395). When the service was already gone - or the ACTION_STOP start was refused
        // by background-start limits - that line never ran, `vpnRunning` stayed true, the button
        // stayed on "Disconnect", and every tap re-sent a stop that could not change anything.
        // From the user's side: "I cannot tap Connect again". The service still reports the
        // truth when it does stop; this only removes the dead window.
        GuardState.setVpnRunning(false)
        ctx.startService(Intent(ctx, ZappVpnService::class.java).setAction(ZappVpnService.ACTION_STOP))
    }

    fun startGuard() {
        ctx.startForegroundService(Intent(ctx, com.zapp.net.guard.GuardForegroundService::class.java))
    }

    fun stopGuard() {
        ctx.stopService(Intent(ctx, com.zapp.net.guard.GuardForegroundService::class.java))
    }

    /** One background action at a time; the label is what the button shows while it runs. */
    fun run(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        // `GuardState.appScope`, not the composition's scope. Measured 09:20: leaving the panel
        // cancelled a registration three times and a key reset once ("The coroutine scope left
        // the composition"). A rotation is engine work; the screen that started it must not be
        // able to kill it by scrolling away.
        GuardState.appScope.launch {
            busy = label
            runCatching { block() }
                .onFailure { GuardState.log("error", "$label: ${it.message}") }
            busy = null
        }
    }

    val now = System.currentTimeMillis()
    val paused = st.pausedUntilMs > now
    val egress = pool.lastOrNull()?.egress64 ?: WarpPool.prefix64Of(st.currentV6)
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    val issues = buildList {
        if (!st.vpnRunning) add("Tunnel is down — Termux is not riding WARP, so nothing rotates.")
        else if (!st.verified) add("Tunnel is up but no egress has been proven through it yet.")
        if (!prefs.guard) add("Zen Guard is off — a wall will not be rotated.")
        if (paused) add(
            "Rotation paused until " + SimpleDateFormat("HH:mm", Locale.US).format(Date(st.pausedUntilMs)) +
                " (fresh /64s kept walling)."
        )
        if (blacklist.size >= 6) add(
            "${blacklist.size} /64s burned. Cloudflare is handing back the same few prefixes; " +
                "hop now lands on a walled one."
        )
        if (prefs.autoRepair.not() && st.vpnRunning && !st.verified) add(
            "Auto-Repair is off, so an unproven tunnel will be left as it is."
        )
        if (st.solid == 0 && st.vpnRunning) add(
            "No proven reserve yet \u2014 the keeper registers one spare per 45 s tick."
        )
    }

    LazyColumn(
        // The first card used to start at y=0, so "Repair" was drawn underneath the clock and
        // the battery pill. `ChatScreen` and `ShellScreen` both clear the status bar; this one
        // never did, because it had no title row to push the content down.
        modifier = Modifier.fillMaxSize().background(Bg).statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {

        // ---- title ----
        // `ShellScreen` opens with a 22 sp title and a one-line description; Guard opened with a
        // bare card, which is why the tab looked unfinished next to the other two.
        item {
            Column(Modifier.padding(bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        OcIcons.Shield,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.width(22.dp).height(22.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text("Guard", color = TextPrimary, fontSize = 22.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "your own tunnel, your own address",
                    color = TextDim, fontSize = 12.sp,
                )
            }
        }

        // ---- Termux access ----
        item {
            val p = termProbe
            val state = p?.state
            val ok = state == com.zapp.net.termux.TermuxSetup.State.OK
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHead(OcIcons.Terminal, "Termux access")
                    Spacer(Modifier.weight(1f))
                    Text(
                        when {
                            termBusy -> "checking"
                            ok -> "ok"
                            p == null -> "not checked"
                            else -> "blocked"
                        },
                        fontSize = 11.sp,
                        color = if (ok) Good else TextDim,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when (state) {
                        com.zapp.net.termux.TermuxSetup.State.OK ->
                            "Termux answered a real command. The engine can start."
                        com.zapp.net.termux.TermuxSetup.State.NO_PERMISSION ->
                            "ZAPP does not hold Termux's command permission. Nothing can run " +
                                "until it is granted."
                        com.zapp.net.termux.TermuxSetup.State.NEEDS_PROPERTY ->
                            "Termux is installed but its own settings file still forbids " +
                                "commands from other apps."
                        com.zapp.net.termux.TermuxSetup.State.NO_BOOTSTRAP ->
                            "Termux has not finished installing its packages. Open it once and " +
                                "let it print a prompt."
                        com.zapp.net.termux.TermuxSetup.State.NO_TERMUX ->
                            "Termux is not installed."
                        com.zapp.net.termux.TermuxSetup.State.SILENT ->
                            "Termux did not answer. Open it once, or set it to Unrestricted in " +
                                "Android's battery settings."
                        null -> if (termBusy) "Asking Termux for its own answer." else "Not checked yet."
                    },
                    fontSize = 12.sp,
                    color = if (ok) TextDim else TextPrimary,
                    lineHeight = 18.sp,
                )

                if (state == com.zapp.net.termux.TermuxSetup.State.NEEDS_PROPERTY) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        com.zapp.net.termux.TermuxSetup.ENABLE_COMMAND,
                        fontSize = 10.sp,
                        color = Accent,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Surface2, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Copy it, open Termux, long-press the black screen, Paste, Enter. If " +
                            "Termux is still installing its packages, wait for the ~ $ prompt " +
                            "first - pasting before that says \"command not found\". Then tap " +
                            "Check again — no restart is needed.",
                        fontSize = 11.sp, color = TextDim, lineHeight = 17.sp,
                    )
                }

                Spacer(Modifier.height(10.dp))
                ActionButton(
                    when {
                        termBusy -> "Asking Termux…"
                        state == com.zapp.net.termux.TermuxSetup.State.OK -> "Check again"
                        state == com.zapp.net.termux.TermuxSetup.State.NEEDS_PROPERTY ->
                            "Copy the line and open Termux"
                        state == com.zapp.net.termux.TermuxSetup.State.NO_PERMISSION ->
                            "Allow ZAPP to run commands"
                        state == com.zapp.net.termux.TermuxSetup.State.NO_TERMUX -> "Check again"
                        else -> "Open Termux"
                    },
                    primary = !ok,
                    enabled = !termBusy,
                ) {
                    when (state) {
                        com.zapp.net.termux.TermuxSetup.State.NEEDS_PROPERTY -> {
                            com.zapp.net.termux.TermuxSetup.copyCommand(ctx)
                            termCopied = com.zapp.net.termux.TermuxSetup.openTermux(ctx)
                        }
                        com.zapp.net.termux.TermuxSetup.State.NO_PERMISSION -> {
                            runCatching {
                                askTerm.launch("com.termux.permission.RUN_COMMAND")
                            }
                        }
                        com.zapp.net.termux.TermuxSetup.State.NO_BOOTSTRAP,
                        com.zapp.net.termux.TermuxSetup.State.SILENT,
                        -> { com.zapp.net.termux.TermuxSetup.openTermux(ctx) }
                        else -> termCheck()
                    }
                }
                if (state == com.zapp.net.termux.TermuxSetup.State.NO_PERMISSION) {
                    Text(
                        "Still blocked? Open ZAPP's own app info and allow it there.",
                        fontSize = 11.sp,
                        color = TextDim,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(
                                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            android.net.Uri.parse("package:${ctx.packageName}"),
                                        ),
                                    )
                                }
                            }
                            .padding(top = 10.dp, bottom = 2.dp),
                    )
                }
                if (p?.detail?.isNotBlank() == true && !ok) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        p.detail.take(220),
                        fontSize = 10.sp, color = TextDim,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        }

        // ---- one-tap repair + its staged loader ----
        //
        // the owner, 22 Nov: "1 BUTTON LANG YAN PERO MAY PARANG LOADER NA INDICATOR NOT BORING NA
        // INDICATOR". The six actions used to be six buttons that each returned before their
        // work had proven itself, so a second tap landed on a half-built tunnel. This card is
        // the whole chain: disconnect -> keys -> reconnect -> refresh -> rotate -> verify, and
        // every row below is a stage that cannot go green until its own proof landed.
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHead(OcIcons.Shield, "Repair")
                    Spacer(Modifier.weight(1f))
                    if (repairRunning) {
                        val done = repair.count { it.state == GuardState.RepairStep.DONE }
                        val secs = if (repairStartedMs == 0L) 0L
                        else (System.currentTimeMillis() - repairStartedMs) / 1000
                        Text("$done / ${repair.size} \u00b7 ${secs}s", fontSize = 11.sp, color = TextDim)
                    }
                }
                Spacer(Modifier.height(10.dp))
                ActionButton(
                    if (repairRunning) "Repairing\u2026" else "Repair (one tap)",
                    primary = true,
                    enabled = !repairRunning,
                ) { com.zapp.net.guard.Repair.startRepair(ctx) }
                if (repair.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    // completed-steps bar: advances only on a proven stage, never on a timer
                    val done = repair.count { it.state == GuardState.RepairStep.DONE }
                    val frac = if (repair.isEmpty()) 0f else done.toFloat() / repair.size
                    androidx.compose.foundation.Canvas(
                        Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    ) {
                        drawRect(TextDim.copy(alpha = 0.22f))
                        drawRect(Good, size = androidx.compose.ui.geometry.Size(size.width * frac, size.height))
                    }
                    Spacer(Modifier.height(10.dp))
                    repair.forEachIndexed { i, step -> StageRow(i, step) }
                    if (repairRunning && repairNote.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(repairNote, fontSize = 10.5.sp, color = TextDim)
                    }
                } else {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Disconnect, new keys, reconnect, refresh, verify \u2014 each step " +
                            "waits for its own proof before the next one starts.",
                        fontSize = 10.5.sp, color = TextDim,
                    )
                }
            }
        }

        // ---- overall status pill + Refresh (#overallPill, #btnRefresh) ----
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (st.vpnRunning && st.verified) Good else if (st.vpnRunning) Warn else Bad)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                st.vpnRunning && st.verified -> "WARP IPv6 · verified"
                                st.vpnRunning -> "WARP IPv6 · unproven"
                                else -> "WARP IPv6 · down"
                            },
                            fontSize = 15.sp, color = TextPrimary,
                        )
                        Text(
                            if (paused) "rotating paused" else if (busy != null) busy!! else "tap Refresh to re-measure egress",
                            fontSize = 10.5.sp, color = TextDim,
                        )
                    }
                    SmallButton("Refresh", enabled = busy == null) {
                        run("Refreshing") {
                            GuardState.refresh(WarpPool(ctx))
                            // 9 s, not 30: the probe is two concurrent in-process requests
                            // capped at 4 s each, so a dead read cannot legitimately take
                            // longer. The old budget held the whole panel - Rotate, Deep
                            // Reset, Fix keys and Doctor are all gated on `busy` - for half a
                            // minute, which is the "SUBANG BAGAL SA LAHAT NA PIPINDUTIN MO DON
                            // SA GUARD" the owner reported.
                            val m = runCatching {
                                com.zapp.net.termux.EgressProbe.measure(ctx, 9_000)
                            }.getOrNull()
                            if (m != null) GuardState.log("vpn", "egress ${m.ip} warp=${m.warp} /64=${m.prefix64}")
                            else GuardState.log("error", "egress unmeasured")
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                KV("Public /64", egress ?: "unmeasured")
                // `setVpnRunning(false)` keeps the last address, so printing it bare while
                // the tunnel is down would present a dead interface as the live one.
                KV("Interface v6", if (st.vpnRunning) st.currentV6 else "${st.currentV6} (last)")
                // Their card carries both halves of the same reading: `#egressPrefix` (the
                // /64) and `#egressFull` (the address trace answered with). This row used to
                // print `egress64` again, so the two lines were always identical.
                val full = pool.lastOrNull()?.egressIp
                KV("Pool entries", "${pool.size} \u00b7 ${blacklist.size} burned")
                // The reserve, in the owner's own units: addresses parked and tokens left.
                // `spentTokens` is charged from each walled turn, so this number falls as the
                // chat grows instead of waiting for the provider to refuse.
                // The ceiling has to be the pool's own size, not a fixed target. Measured on
                // v39: the card read `11 of 10` and `2.2M of 2.0M`, because the reserve can
                // hold one more than POOL_TARGET while it proves the next identity. A number
                // that contradicts its own limit reads as fake, and the owner called that out:
                // "the solid addresses is zero and all of that is broken".
                val solidCap = maxOf(WarpPool.POOL_TARGET, st.solid)
                val runwayCap = WarpPool.BUDGET * solidCap
                fun tokens(v: Long): String = when {
                    v >= 1_000_000L -> String.format(Locale.US, "%.1fM", v / 1_000_000.0)
                    v >= 1_000L -> String.format(Locale.US, "%.0fk", v / 1_000.0)
                    else -> v.toString()
                }
                KV("Solid addresses", "${st.solid} of $solidCap")
                KV("Token runway", "${tokens(st.runwayTokens)} of ${tokens(runwayCap)}")
                if (full != null) KV("Current egress", full)
            }
        }

        // ---- hero actions (#btnHop, #btnWarpToggle, #btnDeepReset, #btnRepairWarp) ----
        item {
            Card {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(
                        if (busy == "Rotating") "Rotating…" else "Rotate /64 Now",
                        primary = true,
                        enabled = busy == null,
                    ) {
                        run("Rotating") {
                            val reg = RotationEngine.hopNow(ctx, "panel")
                            GuardState.log(
                                "hop",
                                if (reg != null) "panel hop ok -> ${reg.v6.take(14)}…"
                                else "panel hop failed — see log",
                            )
                        }
                    }
                    // NEVER gated by `busy`. A tunnel verify now gets 14 s per read, and the
                    // Refresh button holds the whole panel for that long; gating Connect on the
                    // same flag is why the button looked dead ("cant tap connect"). Starting
                    // and stopping the service is two Intents - it needs no serialisation.
                    ActionButton(if (st.vpnRunning) "Disconnect" else "Connect", enabled = true) {
                        if (st.vpnRunning) stopTunnel() else enableTunnel()
                        GuardState.log(
                            "vpn",
                            if (st.vpnRunning) "panel: disconnect requested"
                            else "panel: connect requested",
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Deep Reset", enabled = busy == null) {
                        run("Deep Reset") {
                            val reg = RotationEngine(ctx).deepReset()
                            GuardState.log("wall", if (reg != null) "deep reset -> ${reg.v6.take(14)}…" else "deep reset: hop failed")
                        }
                    }
                    ActionButton("Fix WARP (keys)", enabled = busy == null) {
                        run("Reset keys") {
                            val reg = RotationEngine(ctx).resetKeys()
                            GuardState.log("vpn", if (reg != null) "keys renewed" else "key reset failed")
                        }
                    }
                }
                if (paused) {
                    Spacer(Modifier.height(8.dp))
                    ActionButton("Try now (clear pause)", enabled = busy == null) {
                        RotationEngine(ctx).clearPause()
                        GuardState.log("wall", "paused cleared from the panel")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // ---- route health: the proxy's own numbers (`/__zapp/status`) ----
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHead(OcIcons.Globe, "Route (proxy)")
                    Spacer(Modifier.weight(1f))
                    Text(
                        when {
                            !route.ok -> "not answering"
                            route.healthy && routeErr.isBlank() -> "healthy"
                            route.healthy -> "degraded"
                            else -> "degraded"
                        },
                        fontSize = 11.sp,
                        color = when {
                            !route.ok -> Bad
                            route.healthy && routeErr.isBlank() -> Good
                            else -> Warn
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (!route.ok) {
                    Text(
                        "127.0.0.1:41988 — ${route.error.ifBlank { "no response" }}" +
                            "\nserve.sh points the server's HTTPS_PROXY at a local proxy (this " +
                            "port, or 41987 in the Tor lane). Nothing listening means provider " +
                            "calls have no route - they do not fall back to the carrier address.",
                        fontSize = 10.5.sp, color = Warn,
                    )
                } else {
                    Kv("edge", route.edge.ifBlank { "auto-random" })
                    Kv("mode", "${route.mode} / ${route.scope}" + if (route.strict) " / strict" else "")
                    Kv("live streams", "${route.activeTunnels}")
                    Kv(
                        "5 min",
                        "resets ${route.streamResets5m} · errors ${route.errors5m} · " +
                            "fallbacks ${route.fallbacks5m}",
                    )
                    route.fallbackAge?.let { Kv("last fallback", humanAge(it.toLong())) }
                    if (route.lastKind.isNotBlank()) {
                        Kv("last event", "${route.lastKind}: ${route.lastDetail.take(70)}")
                    }
                    // One failed poll does not invalidate the numbers above: they came from
                    // a successful read. Say so instead of blanking the card (measured
                    // 11:29 - a single 3.5 s timeout replaced a live panel with "unread").
                    if (routeErr.isNotBlank()) {
                        Text(
                            "these are from ${fmtHm.format(Date(routeAt))} · re-reading: $routeErr",
                            fontSize = 10.sp, color = Warn,
                        )
                    }
                }
            }
        }

        // ---- rotation accounting: `zen_guard._note_wall`, as the panel shows it ----
        item {
            Card {
                SectionHead(OcIcons.Rotate, "Rotation")
                Spacer(Modifier.height(6.dp))
                Kv("failed hops", "${wpFailed} of ${com.zapp.net.guard.WallPause.FAILED_HOPS_MAX}")
                Kv("free-wall hops", "${wpFree} of ${com.zapp.net.guard.WallPause.FREE_WALL_HOPS_MAX}")
                if (paused) {
                    val mins = ((st.pausedUntilMs - now) / 60_000L).coerceAtLeast(0L)
                    Kv("paused until", fmtHm.format(Date(st.pausedUntilMs)) + "  (~$mins min)")
                    Text(
                        "Two confirmed fresh addresses hit the same limit, so the limit is not " +
                            "following the IP. Rotation waits for the allowance boundary - sending " +
                            "is never blocked.",
                        fontSize = 10.sp, color = TextDim,
                    )
                    Spacer(Modifier.height(6.dp))
                } else {
                    Kv("paused", "no")
                }
                Kv("auto-repair", com.zapp.net.guard.AutoRepair.statusText())
                com.zapp.net.guard.AutoRepair.recent().lastOrNull()?.let {
                    Kv("last repair", "${it.action}: ${it.message.take(60)}")
                }
                if (!prefs.autoRepair) {
                    Text(
                        "Auto-Repair is off in Protection — a dead tunnel will be left as it is.",
                        fontSize = 10.sp, color = Warn,
                    )
                }
            }
        }

        // ---- health card (#healthTitle/#healthSub/#healthIssues, #btnFixEverything) ----
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (issues.isEmpty()) "Everything checks out"
                            else if (issues.size == 1) "1 thing to fix"
                            else "${issues.size} things to fix",
                            fontSize = 14.sp, color = if (issues.isEmpty()) TextPrimary else Warn,
                        )
                        Text("Live state of the tunnel, the guard and the prefix pool.", fontSize = 10.5.sp, color = TextDim)
                    }
                    SmallButton("Fix everything", enabled = busy == null) {
                        run("Fixing") {
                            val eng = RotationEngine(ctx)
                            if (!st.vpnRunning) {
                                enableTunnel()
                                GuardState.log("vpn", "fix: tunnel start requested")
                            }
                            if (!prefs.guard) {
                                GuardPrefs.set(ctx, "guard", true)
                                startGuard()
                                GuardState.log("vpn", "fix: guard started")
                            }
                            if (paused) {
                                eng.clearPause()
                                GuardState.log("wall", "fix: pause cleared")
                            }
                            if (!st.verified) eng.resetKeys() else eng.hop("panel-fix")
                        }
                    }
                }
                if (issues.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    issues.forEach { t ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text("•", fontSize = 11.sp, color = Warn)
                            Spacer(Modifier.width(6.dp))
                            Text(t, fontSize = 11.sp, color = TextPrimary)
                        }
                    }
                }
            }
        }

        // ---- protection switches (#chkKeeper, #chkGuard, #chkAutoResume, #chkAutoRepair, #chkAutostart) ----
        item {
            Card {
                SectionHead(OcIcons.Shield, "Protection")
                Spacer(Modifier.height(4.dp))
                SwitchRow(
                    "WARP Keeper", "keep the tunnel up without being asked",
                    prefs.keeper,
                ) { v ->
                    GuardPrefs.set(ctx, "keeper", v)
                    if (v) enableTunnel() else stopTunnel()
                }
                SwitchRow(
                    "Zen Guard", "rotate the /64 when the server reports a wall",
                    prefs.guard,
                ) { v ->
                    GuardPrefs.set(ctx, "guard", v)
                    if (v) startGuard() else stopGuard()
                }
                SwitchRow(
                    "Strict Fail-Closed",
                    "refuse the plain route for a pinned host when the tunnel is down",
                    prefs.strict,
                ) { v -> GuardPrefs.set(ctx, "strict", v) }
                SwitchRow(
                    "Auto-Repair", "re-roll an unproven tunnel instead of leaving it",
                    prefs.autoRepair,
                ) { v -> GuardPrefs.set(ctx, "autoRepair", v) }
                // The continuation switch. Distinct from the auto-send that was removed: this
                // re-sends the prompt he typed, in the chat it died in, once, after a swap.
                SwitchRow(
                    "Resume after swap",
                    "re-send the prompt a wall killed, once, on the reserve address",
                    prefs.resume,
                ) { v -> GuardPrefs.set(ctx, "resume", v) }
                SwitchRow(
                    "Auto-Start", "brings the guard up at boot, and the guard starts the tunnel - needs Zen Guard on",
                    prefs.autostart,
                ) { v -> GuardPrefs.set(ctx, "autostart", v) }
            }
        }

        // ---- keep alive: the ROM's own killer, stated as it is ----
        item {
            Card {
                Text("Keep the engine alive", fontSize = 13.sp, color = TextDim)
                Spacer(Modifier.height(6.dp))
                Text(
                    "XOS freezes background apps, and the engine lives in Termux - a frozen " +
                        "Termux is a dead tunnel. Android exposes one switch for this; the " +
                        "ROM's own Auto-start list is the second and it only exists in its app.",
                    fontSize = 11.sp, color = TextDim, lineHeight = 17.sp,
                )
                Spacer(Modifier.height(10.dp))
                KV("ZAPP exempt from battery saving", if (zappExempt) "yes" else "no")
                KV("Termux exempt from battery saving", if (termuxExempt) "yes" else "no")
                Spacer(Modifier.height(10.dp))
                ActionButton(
                    if (zappExempt) "ZAPP is already exempt" else "Allow ZAPP in the background",
                    primary = !zappExempt,
                    enabled = !zappExempt,
                ) {
                    val req = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                    runCatching { ctx.startActivity(req) }.onFailure {
                        runCatching {
                            ctx.startActivity(
                                Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                            )
                        }
                    }
                    batteryTick++
                }
                Spacer(Modifier.height(8.dp))
                SmallButton("Open ZAPP's system settings", enabled = true) {
                    runCatching {
                        ctx.startActivity(
                            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(android.net.Uri.parse("package:${ctx.packageName}")),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "If this ROM has an Auto-start list, ZAPP has to be in it too. That page has " +
                        "no public name on XOS, so the second button opens the closest thing - " +
                        "the app's own system page.",
                    fontSize = 10.5.sp, color = TextDim, lineHeight = 16.sp,
                )
            }
        }

        // ---- multi-egress: their four mode tabs + sub-panels (#tabModeWarp .. #panelClean) ----
        item {
            Card {
                EgressCard(
                    route = route,
                    tor = tor,
                    px = px,
                    verified = st.verified,
                    busy = busy,
                    edgeIp = edgeIp,
                    edgePort = edgePort,
                    setEdgeIp = { edgeIp = it },
                    setEdgePort = { edgePort = it },
                    onAction = { label, block -> run(label, block) },
                )
            }
        }

        // ---- quota, straight from the server ----
        item {
            Card {
                Text("Quota", fontSize = 13.sp, color = TextDim)
                Spacer(Modifier.height(6.dp))
                val u = usage
                if (!usageLoaded) {
                    Text("reading…", fontSize = 11.sp, color = TextDim)
                } else if (u == null) {
                    Text("Usage limits couldn't be loaded.", fontSize = 11.sp, color = Warn)
                } else if (u.data == null) {
                    Text("Not signed in to OpenCode Console", fontSize = 12.sp, color = TextPrimary)
                    // The workspace budgets need a Console token, but the free window does
                    // not: `Guard` records the server's own `Retry-After` when the free
                    // allowance is spent (`GuardPrefs.freeLimitUntil`). Printing that here
                    // turns a card that could only ever say "not signed in" into the one
                    // number that decides whether a prompt can succeed.
                    val until = com.zapp.net.settings.GuardPrefs.freeLimitUntil(ctx)
                    if (until > now) {
                        KV("Free window", "used up · resets at ${fmtHm.format(Date(until))}")
                        val mins = ((until - now) / 60_000L).coerceAtLeast(0L)
                        Text(
                            "The server refused with its own Retry-After; that is $mins min away. " +
                                "Rotation cannot move this - it is keyed on the account, not the /64.",
                            fontSize = 10.5.sp, color = Warn,
                        )
                    } else {
                        Text(
                            "Connect OpenCode Console to see your workspace's budgets and Go usage.",
                            fontSize = 10.5.sp, color = TextDim,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    // ---- sign in, for anyone, with their own account ----
                    // The app itself has no user list: `Onboarding.ensureToken` mints 32 random
                    // bytes on the device and every install is its own identity. What a new user
                    // does NOT have is a way to attach an OpenCode account, and the free tier is
                    // account-scoped - which is the real reason "only I could get in".
                    val start = connect
                    if (start == null) {
                        Button(
                            onClick = {
                                if (connectBusy) return@Button
                                connectBusy = true
                                connectMsg = null
                                scope.launch {
                                    try {
                                        val c = com.zapp.net.api.OpenCodeClient(
                                            baseUrl, initialToken = authTok.ifBlank { null },
                                        )
                                        val entry = c.integrationGet("opencode")
                                        val m = entry?.methods?.firstOrNull { it.type == "oauth" && it.id == "device" }
                                            ?: entry?.methods?.firstOrNull { it.type == "oauth" }
                                        if (m == null) {
                                            connectMsg = "Couldn't start sign-in. Check your server connection and try again."
                                            return@launch
                                        }
                                        // their connect form carries `server` hidden with the
                                        // console default; send the method's own values
                                        val answer = m.form.orEmpty().mapNotNull { o ->
                                            val k = (o["key"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                                                ?: return@mapNotNull null
                                            val d = (o["default"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                                                ?: return@mapNotNull null
                                            k to d
                                        }.toMap()
                                        val s = c.connectOAuth("opencode", m.id, answer)
                                        if (s == null) {
                                            connectMsg = "Couldn't start sign-in. Check your server connection and try again."
                                            return@launch
                                        }
                                        connect = s
                                        connectCopied = true
                                        connectMsg = null
                                        // auto-copy: the link is already on the clipboard by the
                                        // time he looks at the browser button
                                        runCatching { clipboard.setText(AnnotatedString(s.url)) }
                                        openExternal(ctx, s.url)
                                    } finally {
                                        connectBusy = false
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg),
                        ) {
                            Text(if (connectBusy) "Starting…" else "Sign in with OpenCode", fontSize = 12.sp)
                        }
                    } else {
                        Text("Connect OpenCode Console", fontSize = 12.sp, color = TextPrimary)
                        Text(
                            "Continue in your browser. Confirm the code shown there matches the one below.",
                            fontSize = 10.5.sp, color = TextDim,
                        )
                        Spacer(Modifier.height(6.dp))
                        if (start.code.isNotBlank()) {
                            KV("Device code", start.code)
                        } else if (start.instructions.isNotBlank()) {
                            Text(start.instructions, fontSize = 12.sp, color = TextPrimary)
                        }
                        Text("Waiting for confirmation", fontSize = 10.5.sp, color = Accent)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    clipboard.setText(AnnotatedString(start.url))
                                    connectMsg = "Sign-in link copied"
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Transparent, contentColor = Accent,
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                            ) { Text("Copy sign-in link", fontSize = 11.sp) }
                            Button(
                                onClick = {
                                    if (!openExternal(ctx, start.url)) {
                                        connectMsg = "We couldn't open your browser. Try again or copy the sign-in link to continue."
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Transparent, contentColor = Accent,
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                            ) { Text("Open Console again", fontSize = 11.sp) }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Try again",
                            fontSize = 11.sp,
                            color = TextDim,
                            modifier = Modifier.clickable {
                                scope.launch {
                                    val c = com.zapp.net.api.OpenCodeClient(
                                        baseUrl, initialToken = authTok.ifBlank { null },
                                    )
                                    c.connectCancel("opencode", start.attemptID)
                                }
                                connect = null
                                connectMsg = null
                            },
                        )
                    }
                    connectMsg?.let { Text(it, fontSize = 10.5.sp, color = Warn) }
                } else {
                    val info = u.data
                    info.workspace?.let { w -> KV("Workspace", w.name ?: w.id) }
                    listOf(
                        "Rolling" to info.go?.rolling,
                        "Weekly" to info.go?.weekly,
                        "Monthly" to info.go?.monthly,
                    ).forEach { (label, m) ->
                        if (m != null) KV(label, "%.1f%% used%s".format(m.percent, if (m.limited) " · limited" else ""))
                    }
                    info.budgets.forEach { b -> KV("Budget", "$%.2f of $%.2f".format(b.spent, b.limit)) }
                }
            }
        }

        // ---- stats (#hopsTag, #blacklistSummary, #lastHopText) ----
        item {
            Card {
                KV("Hops", st.hops.toString())
                KV("Walls seen", st.walls.toString())
                KV("Blacklisted /64s", blacklist.size.toString())
                KV(
                    "Last hop",
                    if (st.lastHopMs == 0L) "never" else SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(st.lastHopMs)),
                )
                // their `STATS & SAVED WORK`: "17 projects · 40 chats"
                KV(
                    "Saved work",
                    when {
                        !savedWorkLoaded -> "reading…"
                        projects < 0 && chats < 0 -> "server did not answer"
                        else -> "${if (projects < 0) "?" else projects} projects · " +
                            "${if (chats < 0) "?" else chats} chats"
                    },
                )
            }
        }

        // ---- tabbed logs (#logBox tabs + Copy / Clear) ----
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(
                        "guard" to "Guard",
                        "tunnel" to "Tunnel",
                        // theirs: a "Proxy" tab fed by `proxy_status()["recent"]`
                        "proxy" to "Proxy",
                        "blacklist" to "Blacklist",
                        // their keeper's watch log; ours is app-private, see ProxyKeeper.log
                        "keeper" to "Keeper",
                    ).forEach { (id, label) ->
                        val sel = logTab == id
                        Text(
                            label,
                            fontSize = 11.sp,
                            color = if (sel) Color.Black else TextDim,
                            modifier = Modifier
                                .background(if (sel) Accent else Color.Transparent, RoundedCornerShape(6.dp))
                                .clickable { logTab = id }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    SmallButton("Copy", enabled = true) {
                        val text = when (logTab) {
                            "blacklist" -> blacklist.joinToString("\n") { "${it.prefix64}  ${it.reason}" }
                            "keeper" -> keeperLog.joinToString("\n")
                            "proxy" -> route.recent.joinToString("\n") {
                                "${fmt.format(Date(it.ts * 1000))} ${it.kind} ${it.detail}" +
                                    (if (it.host.isNotBlank()) " host=${it.host}" else "") +
                                    (if (it.edge.isNotBlank()) " edge=${it.edge}" else "")
                            }
                            else -> events.filter { if (logTab == "guard") it.kind != "vpn" else it.kind == "vpn" }
                                .joinToString("\n") { "[${fmt.format(Date(it.tsMs))}] ${it.text}" }
                        }
                        clipboard.setText(AnnotatedString(text))
                    }
                    if (logTab == "blacklist" && blacklist.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        SmallButton("Clear", enabled = busy == null) {
                            run("Clear blacklist") {
                                WarpPool(ctx).clearBlacklist()
                                GuardState.refresh(WarpPool(ctx))
                                GuardState.log("wall", "blacklist cleared from the panel")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                when (logTab) {
                    "keeper" -> {
                        if (keeperLog.isEmpty()) {
                            Text(
                                "no keeper lines yet - the watchdog writes one line per probe " +
                                    "only when something changes",
                                fontSize = 11.sp, color = TextDim,
                            )
                        } else {
                            keeperLog.forEach { line ->
                                Text(line, fontSize = 10.sp, color = TextDim, modifier = Modifier.padding(vertical = 1.dp))
                            }
                        }
                    }
                    "blacklist" -> if (blacklist.isEmpty()) {
                        Text("no burned /64s", fontSize = 11.sp, color = TextDim)
                    } else {
                        blacklist.forEach { b ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(b.prefix64, fontSize = 10.5.sp, color = TextPrimary)
                                Text(b.reason, fontSize = 9.5.sp, color = TextDim)
                            }
                        }
                    }
                    "proxy" -> {
                        // Their Proxy tab renders `proxy_status()["recent"]`: ts, kind,
                        // detail, host, edge. Newest first, same fields.
                        if (route.recent.isEmpty()) {
                            Text(
                                if (route.ok) "no proxy events in the last 5 min"
                                else "proxy status unread - ${route.error.ifBlank { "no response" }}",
                                fontSize = 11.sp, color = TextDim,
                            )
                        }
                        route.recent.asReversed().take(30).forEach { e ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                Text("[${fmt.format(Date(e.ts * 1000))}]", fontSize = 9.5.sp, color = TextDim)
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    Text(
                                        "${e.kind}: ${e.detail.take(110)}",
                                        fontSize = 10.5.sp,
                                        color = when (e.kind) {
                                            "fallback", "error", "connect_fail", "edge_fail" -> Warn
                                            "stream_reset", "reset" -> Warn
                                            "hop", "edge" -> Accent
                                            else -> TextPrimary
                                        },
                                    )
                                    if (e.host.isNotBlank() || e.edge.isNotBlank()) {
                                        Text(
                                            listOfNotNull(
                                                e.host.takeIf { it.isNotBlank() }?.let { "host=$it" },
                                                e.edge.takeIf { it.isNotBlank() }?.let { "edge=$it" },
                                            ).joinToString("  "),
                                            fontSize = 9.sp, color = TextDim,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        val rows = events.filter { if (logTab == "guard") it.kind != "vpn" else it.kind == "vpn" }
                        if (rows.isEmpty()) Text("nothing yet", fontSize = 11.sp, color = TextDim)
                        rows.take(80).forEach { e ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                Text("[${fmt.format(Date(e.tsMs))}]", fontSize = 9.5.sp, color = TextDim)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    e.text, fontSize = 10.5.sp,
                                    color = when (e.kind) {
                                        "wall" -> Warn; "error" -> Warn; "hop" -> Accent; else -> TextPrimary
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- doctor: their `doctor.py`, on the phone (see `Doctor.kt`) ----
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Doctor", fontSize = 13.sp, color = TextDim)
                    Spacer(Modifier.weight(1f))
                    val worst = com.zapp.net.guard.Doctor.worst(doc)
                    Text(
                        when {
                            doc.isEmpty() -> "not run"
                            worst == com.zapp.net.guard.Doctor.Level.BAD -> "problems"
                            worst == com.zapp.net.guard.Doctor.Level.WARN -> "warnings"
                            else -> "clean"
                        },
                        fontSize = 11.sp,
                        color = when {
                            doc.isEmpty() -> TextDim
                            worst == com.zapp.net.guard.Doctor.Level.BAD -> Bad
                            worst == com.zapp.net.guard.Doctor.Level.WARN -> Warn
                            else -> Good
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (doc.isEmpty()) {
                    Text(
                        "Their `doctor.py` asks eight questions. Every answer here comes from this " +
                            "device: the lane's own status, the guard's counters, the tunnel's egress " +
                            "and the server's port.",
                        fontSize = 10.sp, color = TextDim,
                    )
                } else {
                    doc.forEach { c ->
                        Row(Modifier.padding(top = 4.dp)) {
                            Text(
                                when (c.level) {
                                    com.zapp.net.guard.Doctor.Level.OK -> "ok"
                                    com.zapp.net.guard.Doctor.Level.WARN -> "warn"
                                    com.zapp.net.guard.Doctor.Level.BAD -> "fail"
                                    else -> "info"
                                },
                                fontSize = 9.5.sp,
                                color = when (c.level) {
                                    com.zapp.net.guard.Doctor.Level.OK -> Good
                                    com.zapp.net.guard.Doctor.Level.WARN -> Warn
                                    com.zapp.net.guard.Doctor.Level.BAD -> Bad
                                    else -> TextDim
                                },
                                modifier = Modifier.width(30.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(c.name, fontSize = 11.sp, color = TextPrimary)
                                Text(c.detail, fontSize = 10.sp, color = TextDim)
                            }
                        }
                    }
                    if (docAt > 0) {
                        Text(
                            "measured ${fmtHm.format(Date(docAt))}",
                            fontSize = 9.5.sp, color = TextDim,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                ActionButton("Run doctor", primary = true, enabled = busy == null) {
                    run("Doctor") {
                        doc = com.zapp.net.guard.Doctor.run(ctx)
                        docAt = System.currentTimeMillis()
                        GuardState.log("doctor", "ran ${doc.size} checks")
                    }
                }
            }
        }

        // ---- what could not be ported, stated plainly ----
        item {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("Not ported (desktop-only controls)", fontSize = 11.sp, color = TextDim)
                listOf(
                    // their "Desktop App Proxy / Wired :41987" needs no switch here: the
                    // server reads `~/.config/opencode/service.json` by itself (verified on
                    // this phone), so there is nothing for a toggle to wire.
                    "Firewall kill-switch" to "no firewall API without root; Doctor reports the " +
                        "proxy's strict fail-closed switch, which is the rootless substitute",
                    "Fix Port Conflict" to "the server's port belongs to Termux, not to us",
                    "Launch Desktop / CLI, PATH shim, Install / Uninstall" to "no desktop app on the phone",
                ).forEach { (name, why) ->
                    Row(Modifier.padding(top = 3.dp)) {
                        Text("•", fontSize = 10.sp, color = TextDim)
                        Spacer(Modifier.width(6.dp))
                        Text("$name — $why", fontSize = 10.sp, color = TextDim)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHead(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    // Every card header was bare text, so the Guard page had no glyphs at all - the one screen
    // that is supposed to look like a piece of hardware read as a settings dump. These are the
    // catalogue icons, tinted to `TextDim` so they sit at the same weight as the label.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = TextDim,
            modifier = Modifier.width(13.dp).height(13.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(title, fontSize = 13.sp, color = TextDim)
    }
}

/**
 * A raw second count is not something a person reads.
 *
 * The panel used to print `last fallback 101082s ago`, which is 28 hours expressed so that
 * nobody can tell it is 28 hours. Anything past a minute is rounded to the unit that matters.
 */
private fun humanAge(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s ago"
    seconds < 3_600 -> "${seconds / 60}m ago"
    seconds < 86_400 -> "${seconds / 3_600}h ago"
    else -> "${seconds / 86_400}d ago"
}

@Composable
private fun Kv(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(key, fontSize = 10.5.sp, color = TextDim, modifier = Modifier.width(86.dp))
        Text(value, fontSize = 10.5.sp, color = TextPrimary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    // Same card recipe as `ShellScreen` and `SettingsScreen`: `Surface` fill plus one hairline.
    // Without the hairline a `#0D0D0D` card on a `#0A0A0A` page is three steps of black, which
    // is invisible in daylight and reads as "no card at all".
    Column(
        Modifier.fillMaxWidth()
            .background(Surface, RoundedCornerShape(12.dp))
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        content()
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.width(10.dp).height(10.dp).background(color, RoundedCornerShape(5.dp)))
}

@Composable
private fun ActionButton(label: String, primary: Boolean = false, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Accent else Surface2,
            contentColor = if (primary) Color.Black else TextPrimary,
        ),
    ) { Text(label, fontSize = 11.5.sp) }
}

@Composable
private fun SmallButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    // "Fix everything" sat on `Surface2` with no rim and read as a disabled control on a
    // `Surface` card - two steps of near-black apart, with nothing between them. The hairline
    // and the brighter label make it look like the one action on the card that does something.
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(7.dp),
        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) Border else Line),
        colors = ButtonDefaults.buttonColors(
            containerColor = Surface2,
            contentColor = if (enabled) TextPrimary else TextDim,
            disabledContainerColor = Surface2,
            disabledContentColor = TextFaint,
        ),
    ) { Text(label, fontSize = 10.5.sp) }
}

/**
 * One stage of the one-tap repair.
 *
 * The running glyph is a **stepped** pulse, not `rememberInfiniteTransition`. Measured 22 Nov
 * on this phone: an infinite transition asks Compose for a frame at the display's own rate and
 * never stops, which held the app at 119 fps and 115% CPU with nothing streaming - the taps
 * then landed seconds late. Eight steps a second reads as motion and costs a fraction.
 */
@Composable
private fun StageRow(index: Int, step: GuardState.RepairStep) {
    val running = step.state == GuardState.RepairStep.RUNNING
    var tick by remember(running) { mutableStateOf(0) }
    if (running) {
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(120)
                tick++
            }
        }
    }
    val color = when (step.state) {
        GuardState.RepairStep.DONE -> Good
        GuardState.RepairStep.FAILED -> Bad
        GuardState.RepairStep.RUNNING -> TextPrimary
        GuardState.RepairStep.SKIPPED -> TextDim
        else -> TextDim.copy(alpha = 0.6f)
    }
    val glyph = when (step.state) {
        GuardState.RepairStep.DONE -> "\u2713"
        GuardState.RepairStep.FAILED -> "\u2715"
        GuardState.RepairStep.RUNNING -> if (tick % 3 == 0) "\u25cf" else "\u25cb"
        else -> "\u25cb"
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph, fontSize = 11.sp, color = color, modifier = Modifier.width(16.dp))
        Text("${index + 1}. ${step.name}", fontSize = 11.5.sp, color = color)
        Spacer(Modifier.weight(1f))
        if (step.detail.isNotBlank()) Text(step.detail, fontSize = 10.sp, color = TextDim)
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.5.sp, color = TextPrimary)
            Text(desc, fontSize = 10.sp, color = TextDim)
        }
        ZappToggle(checked = checked, onChange = onChange)
    }
}

/**
 * Our switch, not Material's.
 *
 * The stock `Switch` is a 52 x 32 pill with a filled track - on this palette it renders as a
 * solid white lozenge and a black dot, which is the single most "stock Android" thing on the
 * screen and the reason the Guard page stopped looking like ours. the owner, 22 Nov: "FIX THE UI
 * DESIGN THEY ARE NOT THE AS OUR STYLE ... THE ICONS".
 *
 * This is a 40 x 22 rounded *square* with no fill, a hairline that goes white when armed, and a
 * small square thumb that slides. It reads as a hardware toggle rather than a phone widget, and
 * it costs nothing: one finite `animateDpAsState`, no infinite transition.
 */
@Composable
private fun ZappToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val travel by animateDpAsState(if (checked) 22.dp else 3.dp, label = "thumb")
    val rim by animateColorAsState(if (checked) Accent else Border, label = "rim")
    Box(
        Modifier
            .width(40.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (checked) Accent.copy(alpha = 0.10f) else Color.Transparent)
            .border(1.dp, rim, RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onChange(!checked) },
    ) {
        Box(
            Modifier
                .padding(start = travel, top = 3.dp)
                .width(14.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (checked) Accent else TextFaint),
        )
    }
}

@Composable
private fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, fontSize = 11.5.sp, color = TextDim)
        Text(v, fontSize = 11.5.sp, color = TextPrimary)
    }
}

/**
 * Multi-egress: their four mode tabs (`#tabModeWarp` .. `#tabModeClean`) and the four
 * sub-panels behind them, in our skin.
 *
 * Every tap writes `egress_mode.json` through `LaneConfig` - the same file
 * `zapp_proxy.load_cfg()` reads per connection - and every value shown here comes back
 * from `proxy_status`. That is the whole point: a tab cannot claim a mode the lane is
 * not using, and this card cannot show an endpoint the tunnel is not dialling.
 *
 * Their `set_egress_mode` only *starts* Tor when it is picked; switching away leaves the
 * daemon running. Same here.
 */
@Composable
private fun EgressCard(
    route: com.zapp.net.guard.RouteStatus.Snapshot,
    tor: com.zapp.net.net.TorLane.State,
    px: com.zapp.net.net.EgressProxy.Stats,
    verified: Boolean,
    busy: String?,
    edgeIp: String,
    edgePort: Int,
    setEdgeIp: (String) -> Unit,
    setEdgePort: (Int) -> Unit,
    onAction: (String, suspend () -> Unit) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // their `let activeModeTab = null; if (!activeModeTab) activeModeTab = curMode;`
    var tab by remember { mutableStateOf("") }
    LaunchedEffect(route.mode) { if (tab.isBlank() && route.mode.isNotBlank()) tab = route.mode }
    val mode = tab.ifBlank { route.mode.ifBlank { "warp" } }

    val scopeNow = route.scope.ifBlank { "isolated" }
    val typeNow = route.customType.ifBlank { "socks5" }
    val proxyNow = route.customProxy.ifBlank { "127.0.0.1:10808" }

    var addr by remember { mutableStateOf("") }
    var addrEdited by remember { mutableStateOf(false) }
    LaunchedEffect(route.customProxy) {
        if (!addrEdited && route.customProxy.isNotBlank()) addr = route.customProxy
    }
    var ov by remember { mutableStateOf(GuardPrefs.endpointOverride(ctx)) }
    var pings by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }

    fun write(next: String, scopeTo: String = scopeNow, proxy: String = proxyNow, type: String = typeNow) {
        tab = next
        com.zapp.net.net.LaneConfig.write(ctx, next, scopeTo, proxy, type)
        GuardState.log(
            "lane",
            "mode=$next scope=$scopeTo" + if (next == "custom") " proxy=$proxy ($type)" else "",
        )
        // Their `set_egress_mode` returns `ok=False, error="..."` when the lane refuses and
        // the page then re-reads the real mode instead of leaving the tab lit
        // (`avatar_ui.py:1320-1368`, `1651-1694`). Ours writes through Termux's relay,
        // which can refuse silently, so the tab must not be allowed to keep claiming a mode
        // the lane never took. Re-read once, shortly, and put the true mode back.
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(1_500L)
            val real = com.zapp.net.guard.RouteStatus.read()
            if (!real.ok) {
                GuardState.log("lane", "the lane is not answering - cannot confirm $next")
                tab = route.mode.ifBlank { tab }
                return@launch
            }
            if (real.mode.isNotBlank() && real.mode != next) {
                GuardState.log(
                    "lane",
                    "the lane did not take $next - still ${real.mode}; the relay may have " +
                        "refused the write",
                )
                tab = real.mode
                return@launch
            }
            // Their `set_warp_scope` returns ok=False, "Scope saved as X, but WARP did not
            // reconnect", instead of claiming success (`avatar_ui.py:1307-1316`). Ours is
            // the same promise: saved is not the same as working.
            if (real.scope.isNotBlank() && real.scope != scopeTo) {
                GuardState.log(
                    "lane",
                    "scope saved as $scopeTo, but the lane still reads ${real.scope}",
                )
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Egress", fontSize = 13.sp, color = TextDim)
            Spacer(Modifier.weight(1f))
            // their `setTag(egressTag, w.is_cf ? "CF Verified" : "Unverified")`
            Text(
                if (verified) "CF Verified" else "Unverified",
                fontSize = 10.sp,
                color = if (verified) Good else Warn,
            )
        }
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("warp" to "WARP", "tor" to "Tor", "custom" to "Custom", "clean" to "Clean")
                .forEach { (m, label) ->
                    val on = m == mode
                    Text(
                        label,
                        fontSize = 11.sp,
                        color = if (on) Color.Black else TextDim,
                        modifier = Modifier
                            .weight(1f)
                            .background(if (on) Accent else Surface2, RoundedCornerShape(7.dp))
                            .clickable {
                                write(m, proxy = if (m == "custom") addr.ifBlank { proxyNow } else proxyNow)
                                if (m == "tor") {
                                    if (!tor.running) {
                                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            val s = runCatching { com.zapp.net.settings.observe(ctx).first() }
                                                .getOrNull()
                                            val base = s?.serverBase?.ifBlank { com.zapp.net.settings.PHONE_SERVER }
                                                ?: com.zapp.net.settings.PHONE_SERVER
                                            val pair = "opencode:" + (s?.authToken.orEmpty())
                                            GuardPrefs.set(ctx, "tor", true)
                                            com.zapp.net.net.TorLane.ensure(ctx, base, pair)
                                            com.zapp.net.net.TorLane.exitIp()
                                        }
                                    }
                                } else {
                                    // the in-app lane's own switch follows this one, or the two
                                    // disagree about which route the server is on
                                    GuardPrefs.set(ctx, "tor", false)
                                }
                            }
                            .padding(vertical = 6.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
        }
        Spacer(Modifier.height(10.dp))

        when (mode) {
            "warp" -> {
                val iso = scopeNow == "isolated"
                Text("Scope", fontSize = 11.5.sp, color = TextPrimary)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("App-Only (Isolated)", primary = iso, enabled = busy == null) {
                        write("warp", scopeTo = "isolated")
                    }
                    ActionButton("Full System TUN", primary = !iso, enabled = busy == null) {
                        write("warp", scopeTo = "tun")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (iso) {
                        "App-Only: only the provider calls ride WARP; the phone stays on its normal " +
                            "path. There is no warp-cli here, so the lane tries WARP's own SOCKS port " +
                            "(40000) and falls through - which is what theirs does too."
                    } else {
                        "Full System TUN: the tunnel carries the provider calls."
                    },
                    fontSize = 10.sp, color = TextDim,
                )
            }

            "tor" -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (tor.running) Good else Bad)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (tor.running) "Active Tor node: ${tor.exitIp.ifBlank { "measuring…" }}"
                        else "Tor inactive (ports 9050 / 9150 closed)",
                        fontSize = 11.5.sp, color = TextPrimary,
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (tor.running) Kv("Bootstrap", "${tor.bootstrap}% · ${tor.summary}")
                Kv("Routes", "tor ${px.tor} · direct ${px.direct} · failed ${px.failed}")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("New Exit Identity", enabled = busy == null) {
                        onAction("New exit identity") {
                            val hit = com.zapp.net.net.TorLane.newnym()
                            GuardState.log("tor", if (hit) "NEWNYM sent" else "NEWNYM refused")
                        }
                    }
                    ActionButton(
                        if (tor.running) "Stop Tor Daemon" else "Start Tor Daemon",
                        primary = !tor.running,
                        enabled = busy == null,
                    ) {
                        val start = !tor.running
                        onAction(if (start) "Starting Tor" else "Stopping Tor") {
                            val s = runCatching { com.zapp.net.settings.observe(ctx).first() }.getOrNull()
                            val base = s?.serverBase?.ifBlank { com.zapp.net.settings.PHONE_SERVER }
                                ?: com.zapp.net.settings.PHONE_SERVER
                            val pair = "opencode:" + (s?.authToken.orEmpty())
                            if (start) com.zapp.net.net.TorLane.ensure(ctx, base, pair)
                            else com.zapp.net.net.TorLane.stop(ctx, base, pair)
                            GuardState.log("tor", if (start) "daemon start requested" else "daemon stop requested")
                        }
                    }
                }
            }

            "custom" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("socks5" to "SOCKS5", "http" to "HTTP").forEach { (t, label) ->
                        val on = typeNow == t
                        Text(
                            label, fontSize = 11.sp,
                            color = if (on) Color.Black else TextDim,
                            modifier = Modifier
                                .background(if (on) Accent else Surface2, RoundedCornerShape(7.dp))
                                .clickable {
                                    write("custom", proxy = addr.ifBlank { proxyNow }, type = t)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = addr.ifBlank { proxyNow },
                    onValueChange = { addr = it; addrEdited = true },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.5.sp, color = TextPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Apply & Route", primary = true, enabled = busy == null) {
                        val chosen = addr.ifBlank { proxyNow }
                        write("custom", proxy = chosen, type = typeNow)
                        GuardState.log("lane", "custom proxy $chosen ($typeNow)")
                    }
                    ActionButton("Next Node", enabled = busy == null) {
                        com.zapp.net.net.LaneConfig.nextCustomNode(ctx)
                        GuardState.log("lane", "custom pool -> next node")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (route.customPoolCount > 1) {
                        "Pool: ${route.customPoolCount} nodes configured | Active: " +
                            route.customNode.ifBlank { proxyNow }
                    } else {
                        "Single proxy mode. Paste multiple proxies (comma-separated) to enable auto-cycling."
                    },
                    fontSize = 10.sp, color = TextDim,
                )
            }

            else -> {
                // Their `#panelClean`: pick an endpoint, measure it, apply it, or let
                // auto-heal take the fastest. Desktop runs `warp-cli tunnel endpoint set`;
                // here the choice is persisted and the service dials it (GuardPrefs).
                Text("Clean endpoint (tunnel peer)", fontSize = 11.5.sp, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (ov.isBlank()) "no override - the registration's own endpoint"
                    else "pinned: $ov",
                    fontSize = 10.5.sp, color = if (ov.isBlank()) TextDim else Accent,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Ping Test", enabled = busy == null) {
                        onAction("Ping test") {
                            // their `test_clean_endpoints` measures each *entry*; latency is a
                            // TCP:80 connect, so entries that share an IP share a number and
                            // only the first is shown instead of the same IP five times
                            val hits = EdgePins.endpoints()
                                .distinctBy { it.substringBefore(':') }
                                .mapNotNull { ep ->
                                    EdgePins.latencyMs(ep.substringBefore(':'))?.let { ep to it }
                                }
                                .sortedBy { it.second }
                            pings = hits
                            GuardState.log(
                                "edge",
                                if (hits.isEmpty()) "ping test: none answered on :80"
                                else "ping test: ${hits.size} answered, best ${hits.first().first} " +
                                    "${hits.first().second}ms",
                            )
                        }
                    }
                    ActionButton("Apply Endpoint", primary = true, enabled = busy == null) {
                        val ep = "$edgeIp:$edgePort"
                        GuardPrefs.setEndpointOverride(ctx, ep)
                        ov = ep
                        ZappVpnService.requestEdge(ep)
                        ctx.startService(
                            Intent(ctx, ZappVpnService::class.java).setAction(ZappVpnService.ACTION_START),
                        )
                        GuardState.log("edge", "clean endpoint $ep (persisted)")
                    }
                    ActionButton("Reset Default", enabled = busy == null) {
                        GuardPrefs.setEndpointOverride(ctx, null)
                        ov = ""
                        ctx.startService(
                            Intent(ctx, ZappVpnService::class.java).setAction(ZappVpnService.ACTION_START),
                        )
                        GuardState.log("edge", "clean endpoint reset to the registration's own")
                    }
                }
                Spacer(Modifier.height(6.dp))
                ActionButton("Auto-Heal (Fastest)", enabled = busy == null) {
                    onAction("Auto-heal endpoint") {
                        val best = EdgePins.fastestEdge()
                        if (best == null) {
                            GuardState.log("edge", "auto-heal: no endpoint answered on :80")
                        } else {
                            val (ep, ms) = best
                            GuardPrefs.setEndpointOverride(ctx, ep)
                            ov = ep
                            ZappVpnService.requestEdge(ep)
                            ctx.startService(
                                Intent(ctx, ZappVpnService::class.java).setAction(ZappVpnService.ACTION_START),
                            )
                            GuardState.log("edge", "auto-heal -> $ep (${ms}ms, persisted)")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Their `#selCleanEndpoint` is a dropdown of the whole table, so the only
                // pairs a user can pick are pairs their file ships. Two chip rows (IP +
                // port) can be combined into an endpoint that does not exist - e.g.
                // `188.114.99.1:500` - so the picker lists the 23 entries instead.
                EdgePins.endpoints().chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { ep ->
                            val sel = ep == "$edgeIp:$edgePort"
                            Text(
                                ep,
                                fontSize = 9.5.sp,
                                color = if (sel) Color.Black else TextDim,
                                modifier = Modifier
                                    .background(if (sel) Accent else Surface2, RoundedCornerShape(6.dp))
                                    .clickable {
                                        setEdgeIp(ep.substringBefore(':'))
                                        setEdgePort(ep.substringAfter(':').toIntOrNull() ?: edgePort)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (pings.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    pings.take(6).forEach { (ep, ms) -> Kv(ep.substringBefore(':'), "${ms}ms") }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Latency is a TCP connect on :80, the same fallback their Ping Test uses; " +
                        "ICMP needs root.",
                    fontSize = 10.sp, color = TextDim,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // Measured, not assumed: 2026-11-19, :880, :500 and :1701 all failed the
                    // tunnel verify on this carrier while :2408 answered in 2.1 s
                    // (ZappVpnService.kt:482-488). Their table ships the alternate ports
                    // because ISPs elsewhere filter UDP 2408, so the picker keeps them -
                    // but a pick the verify rejects is reversed by the re-roll, not hidden.
                    "On this carrier only :2408 has passed the tunnel verify (19 Nov: :880, " +
                        ":500, :1701 all failed it). The other ports are there for a network " +
                        "that filters 2408 - if one does not carry, the verify re-rolls it.",
                    fontSize = 10.sp, color = TextDim,
                )
                Spacer(Modifier.height(8.dp))
                // Their `#btnRerollEdge`: a different object from the tunnel peer - it moves
                // the address the *proxy* pins opencode.ai to. Kept here because it belongs
                // to the same panel on their side.
                ActionButton("Re-roll (proxy edge)", enabled = busy == null) {
                    val cmd = "L=\"\$HOME/.zapp/lane\"; mkdir -p \"\$L\"; date +%s > \"\$L/edge_reroll\""
                    val ok = com.zapp.net.termux.RunCommand.run(ctx, cmd, "/sdcard/zapp_edge_reroll.log")
                    GuardState.log(
                        "edge",
                        if (ok) "proxy re-roll requested (edge_reroll)" else "proxy re-roll refused by the relay",
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    // The old card claimed one registration answers a different /64 per edge
                    // (.192.9 fresh, .192.10 burned). Our own measurement says otherwise:
                    // 06:17-06:19, the 27-row table of that time driven one row at a time,
                    // Cloudflare handed back the same few egress /48s (EdgePins.kt:9-29).
                    // The table is 23 rows now - their literal set - so the count is stated
                    // as it was measured rather than as it is today.
                    "Edge choice buys latency and liveness, not address diversity - measured " +
                        "06:17 on 27 rows, every one landed on the same few /48s. The proxy is " +
                        "pinned to ${route.edge.ifBlank { "auto-random" }} right now.",
                    fontSize = 10.sp, color = TextDim,
                )
            }
        }
    }
}

/**
 * Open a sign-in link in whatever browser the phone has.
 *
 * Their copy for a failed launch is `provider.connect.console.browserFailed`; the caller prints
 * that, so this only has to report whether an activity actually started.
 */
private fun openExternal(ctx: android.content.Context, url: String): Boolean {
    if (url.isBlank()) return false
    return runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
}
