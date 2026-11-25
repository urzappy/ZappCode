package com.zapp.net.guard

import android.content.Context
import java.net.InetSocketAddress
import java.net.Socket

/**
 * `warp_keeper._proxy_watchdog:167-187`, ported.
 *
 * Their keeper owns the local proxy and rebuilds it inside its own process. On Android the
 * proxy cannot live in this process at all, and that is not a preference:
 *
 *   Measured 23 Nov 15:15 with the lane inside the app - every verify read came back
 *   `ip=2001:4455:59c:4700:3854:9d68:56ce:15a warp=off` (the carrier) while the tunnel was
 *   demonstrably UP. Android never routes a VpnService's own sockets into the tunnel it
 *   built, and `ZappVpnService` captures `com.termux` only. So a proxy running as this app
 *   can never see WARP, however correct its code is. `warp=off` means "not verified", which
 *   empties `WarpPool.solid()` - which is what made the card read `Solid addresses 0 of 10`
 *   and `Token runway 0 of 2.0M`.
 *
 * So the lane on [PORT] is `zapp_lane`, a static arm64 binary started by `serve.sh` inside
 * Termux, where connect() does enter the tun. This keeper's job is therefore three things:
 *
 *   1. keep the in-process **fallback** lane alive on [ALT_PORT] (41987), which is honest
 *      about being outside the tunnel;
 *   2. watch [PORT] and, when it is quiet, relay `$HOME/.zapp/lane/up.sh` into Termux so the
 *      lane comes back without a reinstall - with backoff, so a permanent failure cannot
 *      spawn a shell every three seconds;
 *   3. log every decision, because the one log the panel could never show was the one about
 *      the thing that keeps the panel alive.
 *
 * The decision logic stays theirs:
 *
 *   * every [WATCH_MS] (their `PROXY_WATCH_SECONDS = 3.0`) check whether the port answers;
 *   * if it does, do nothing - a healthy proxy is never touched;
 *   * if it does not, start it;
 *   * when a start keeps failing, log the fact **once**, not every tick
 *     (their `bind_failing` flag), so a permanent bind error cannot flood the log.
 */
object ProxyKeeper {

    /** their `PROXY_WATCH_SECONDS` */
    const val WATCH_MS = 3_000L

    /** their `_port_open(timeout=0.6)` */
    private const val CONNECT_TIMEOUT_MS = 600

    /** The lane the guard reads and the engine is pointed at. Lives in Termux. */
    const val PORT = com.zapp.net.net.EgressProxy.PORT

    /** The fallback lane this process owns. */
    private const val ALT_PORT = com.zapp.net.net.EgressProxy.ALT_PORT

    /**
     * The lane binary this app expects, byte for byte.
     *
     * A running lane answers the port, so "is 41988 open" can never tell a good build from a
     * stale one. Measured 23 Nov: v31 shipped a lane that tried Tor under the WARP tab and
     * logged a false fallback on every connection; the only way that build ever leaves a
     * phone is a size check that runs *before* the port check.
     */
    private const val LANE_SIZE = 403_072

    private const val LANE_URL =
        "https://github.com/urzappy/ZappCode/releases/download/tools-1/zapp_lane"

    /**
     * The one command the keeper is allowed to send: the whole launcher, inline.
     *
     * It used to be `sh $HOME/.zapp/lane/up.sh` - a path to a file that only `serve.sh` wrote,
     * and `serve.sh` only rewrites it when the engine restarts. Measured 23 Nov on v33: the
     * engine was already up, so the relay ran the *previous* launcher, which still carried
     * `WANT=401216`, replaced nothing, and left the bad build running. The fix is to stop
     * trusting anything already on the phone: every relay writes the version it wants and
     * then runs the launcher it just wrote.
     *
     * Nothing user-supplied reaches this string. The URL and the size are compile-time
     * constants, and `$HOME` is Termux's own.
     */
    private val UP_SCRIPT: String = """
        mkdir -p ${'$'}HOME/.zapp/lane 2>/dev/null
        printf '%s\n%s\n' $LANE_SIZE $LANE_URL > ${'$'}HOME/.zapp/lane/want
        cat > ${'$'}HOME/.zapp/lane/up.sh <<'ZAPPUP'
        #!/data/data/com.termux/files/usr/bin/sh
        BIN=${'$'}HOME/zapp_lane
        LOG=${'$'}HOME/zapp_proxy.log
        LK=${'$'}HOME/.zapp/lane/lock
        WANT=${'$'}(sed -n 1p ${'$'}HOME/.zapp/lane/want 2>/dev/null)
        URL=${'$'}(sed -n 2p ${'$'}HOME/.zapp/lane/want 2>/dev/null)
        [ -n "${'$'}WANT" ] || WANT=$LANE_SIZE
        [ -n "${'$'}URL" ] || URL=$LANE_URL
        up() { curl -s --max-time 2 -o /dev/null http://127.0.0.1:41988/__zapp/status; }

        HAVE=${'$'}(stat -c %s "${'$'}BIN" 2>/dev/null || echo 0)

        # A healthy lane is never touched - but "healthy" means the build this launcher wants.
        #
        # Measured 24 Nov: `up && exit 0` ran BEFORE the size check, so a running 401 808 B
        # lane was never replaced by the 403 072 B one, and every lane fix after v57 silently
        # did nothing on a phone whose old lane still answered. A port that answers is not a
        # version check.
        up && [ "${'$'}HAVE" = "${'$'}WANT" ] && exit 0

        # One runner only.
        #
        # Three things can ask for the lane in the same second - the app at startup, the app
        # watchdog, and serve.sh. Measured 23 Nov 15:58 on v34: that race left zapp_lane at
        # 0 bytes with three `setsid: failed to execute ...: Text file busy` lines, because
        # one run was writing the file while another tried to exec it. `mkdir` is the only
        # atomic primitive POSIX sh guarantees, so the lock is a directory.
        mkdir -p ${'$'}HOME/.zapp/lane 2>/dev/null
        if ! mkdir "${'$'}LK" 2>/dev/null; then
          NOW=${'$'}(date +%s)
          MT=${'$'}(stat -c %Y "${'$'}LK" 2>/dev/null || echo 0)
          if [ ${'$'}((NOW - MT)) -gt 180 ]; then
            rmdir "${'$'}LK" 2>/dev/null
            mkdir "${'$'}LK" 2>/dev/null || exit 0
          else
            exit 0
          fi
        fi
        trap 'rmdir "${'$'}LK" 2>/dev/null' EXIT

        HAVE=${'$'}(stat -c %s "${'$'}BIN" 2>/dev/null || echo 0)
        if [ "${'$'}HAVE" = "${'$'}WANT" ]; then
          up && exit 0
        fi
        if [ "${'$'}HAVE" != "0" ]; then
          echo "lane is ${'$'}HAVE B, want ${'$'}WANT B - replacing ${'$'}(date +%T)" >>"${'$'}LOG"
        fi
        pkill -f zapp_lane 2>/dev/null
        sleep 1
        rm -f "${'$'}BIN"

        echo "lane missing - downloading ${'$'}(date +%T)" >>"${'$'}LOG"
        TMP="${'$'}BIN.new"
        t=0
        GOT=0
        while [ ${'$'}t -lt 5 ]; do
          t=${'$'}((t+1))
          rm -f "${'$'}TMP"
          curl -L --retry 3 --retry-all-errors --noproxy '*' -o "${'$'}TMP" "${'$'}URL" >>"${'$'}LOG" 2>&1
          GOT=${'$'}(stat -c %s "${'$'}TMP" 2>/dev/null || echo 0)
          [ "${'$'}GOT" = "${'$'}WANT" ] && break
          echo "lane download wrong (${'$'}GOT B) attempt ${'$'}t - discarding ${'$'}(date +%T)" >>"${'$'}LOG"
          sleep 5
        done
        if [ "${'$'}GOT" != "${'$'}WANT" ]; then
          rm -f "${'$'}TMP"
          echo "lane download gave up at ${'$'}GOT B ${'$'}(date +%T)" >>"${'$'}LOG"
          exit 3
        fi
        chmod 700 "${'$'}TMP" 2>/dev/null
        # Rename is atomic on the same filesystem, so no run can ever exec a half-written
        # binary - which is exactly what "Text file busy" was.
        mv -f "${'$'}TMP" "${'$'}BIN"
        setsid "${'$'}BIN" 41988 >>"${'$'}LOG" 2>&1 </dev/null &
        sleep 2
        up
        ZAPPUP
        chmod 700 ${'$'}HOME/.zapp/lane/up.sh 2>/dev/null
        sh ${'$'}HOME/.zapp/lane/up.sh
    """.trimIndent()

    @Volatile private var running = false

    /** their `bind_failing`: only the transition into failure is logged. */
    @Volatile private var bindFailing = false

    @Volatile private var restarts = 0

    /** Consecutive failed starts, so the retry interval can grow instead of hammering. */
    @Volatile private var misses = 0

    fun restarts(): Int = restarts

    private fun portOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS) }
        true
    } catch (_: Exception) {
        false
    }

    fun start(ctx: Context) {
        if (running) return
        running = true
        Thread({
            GuardState.log("vpn", "lane watchdog up (every ${WATCH_MS / 1000}s on 127.0.0.1:$PORT)")
            log(ctx, "watchdog up · every ${WATCH_MS / 1000}s on 127.0.0.1:$PORT")
            // Restore the tab that was chosen last session before the first dial.
            runCatching {
                val c = com.zapp.net.net.LaneConfig.read(ctx)
                com.zapp.net.net.EgressProxy.applyConfig(c.mode, c.scope, c.proxy, c.type)
            }
            // One shot at start.
            //
            // `up.sh` is the only thing that knows how the lane comes up, and it replaces a
            // stale binary by size. Without this, an app update would leave the previous lane
            // running forever: this keeper only reacts to a *closed* port, and an old binary
            // keeps the port open, so nothing would ever replace it.
            runCatching { com.zapp.net.termux.TermuxSetup.relay(ctx, UP_SCRIPT) }
                .onSuccess { log(ctx, "startup up.sh relay sent=$it") }
            var nextRelayAt = 0L
            while (running) {
                try {
                    // 1. The fallback lane. Cheap, idempotent, and it must not depend on
                    //    Termux being awake.
                    if (!portOpen(ALT_PORT)) {
                        val ok = com.zapp.net.net.EgressProxy.start()
                        log(ctx, "fallback lane $ALT_PORT closed -> start " + (if (ok) "ok" else "failed"))
                    }

                    // 2. The real lane, inside Termux.
                    if (!portOpen(PORT)) {
                        misses++
                        val now = System.currentTimeMillis()
                        if (now >= nextRelayAt) {
                            val sent = com.zapp.net.termux.TermuxSetup.relay(ctx, UP_SCRIPT)
                            nextRelayAt = now + when {
                                misses <= 2 -> 8_000L
                                misses <= 5 -> 30_000L
                                else -> 120_000L
                            }
                            log(
                                ctx,
                                "lane $PORT closed (miss $misses) -> up.sh relay " +
                                    (if (sent) "sent" else "refused"),
                            )
                            if (!bindFailing) {
                                bindFailing = true
                                GuardState.log(
                                    "vpn",
                                    "lane is not listening on $PORT; asking Termux to start it",
                                )
                            }
                        }
                    } else {
                        misses = 0
                        nextRelayAt = 0L
                        if (bindFailing) {
                            bindFailing = false
                            restarts++
                            log(ctx, "lane $PORT answered · failure cleared · restarts=$restarts")
                            GuardState.log("vpn", "lane is back on 127.0.0.1:$PORT")
                        }
                        // The panel switch is the truth for the proxy, same as before.
                        com.zapp.net.net.EgressProxy.torEnabled =
                            runCatching { com.zapp.net.settings.GuardPrefs.torOn(ctx) }
                                .getOrDefault(true)
                    }
                } catch (e: Throwable) {
                    log(ctx, "watchdog error: ${e.javaClass.simpleName}: ${e.message}")
                    // their `except Exception: pass` - a watchdog must never die on its own error
                }
                try {
                    Thread.sleep(WATCH_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "zapp-lane-keeper").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
    }

    /**
     * The keeper's own trail.
     *
     * Their watch log lives at `/sdcard/zapp_proxy_watch.log`, and this app has no storage
     * permission - so the one log the panel could never show was the one about the thing
     * that keeps the panel alive. The decisions are recorded here instead, in app-private
     * storage, which needs no permission and cannot be blocked by scoped storage.
     */
    fun log(ctx: Context, line: String) {
        runCatching {
            val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            val f = java.io.File(ctx.filesDir, "keeper.log")
            if (f.isFile && f.length() > 48_000) f.writeText("")
            f.appendText("[$stamp] $line\n")
        }
    }

    /** Newest first, for the panel. */
    fun tail(ctx: Context, maxLines: Int = 60): List<String> = runCatching {
        val f = java.io.File(ctx.filesDir, "keeper.log")
        if (!f.isFile) emptyList() else f.readLines().takeLast(maxLines).reversed()
    }.getOrDefault(emptyList())
}
