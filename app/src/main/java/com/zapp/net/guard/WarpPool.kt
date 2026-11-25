package com.zapp.net.guard

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom

/**
 * Pool of WARP registrations. One registration = one WireGuard identity = one /64.
 * Rotation = new keypair + fresh register call -> Cloudflare hands us a new /64.
 * Hit a quota wall on a /64 -> it goes to the blacklist with a cooldown.
 */
@Serializable
data class WarpReg(
    val id: String,
    val privHex: String,       // wireguard private key
    val peerPubHex: String,    // server peer public key from register response
    val endpoint: String,      // e.g. engage.cloudflareclient.com:2408
    val v6: String,            // our /128 inside our fresh /64
    val v4: String? = null,
    val createdAtMs: Long,
    val note: String = "",
    /** The /64 Cloudflare actually reports as our egress. The interface address above
        lives in 2606:4700:110::/48 and never appears on the wire, so blacklisting it
        bans a prefix no request ever used. Filled in by EgressProbe after a swap. */
    val egress64: String? = null,
    /** The full address `cdn-cgi/trace` answered with, same reading as [egress64]. Their
        panel shows both (`egressPrefix` and `egressFull`); without this the card printed
        the /64 twice under two labels. */
    val egressIp: String? = null,
    /**
     * How much of this address's free budget has already been spent, in tokens.
     *
     * the owner, 22 Nov: "ITS 200K TOKEN EACH" - the free lane gives ~200k of context per egress
     * address, so an address is a token, and ten verified addresses are two million tokens of
     * runway. Nothing tracked this before: the app only learned an address was finished when the
     * provider refused it, which is the wall he keeps hitting. Charged from the context the next
     * turn would carry (`Guard.contextOf`).
     */
    val spentTokens: Long = 0,
    /** When this identity last produced a real egress reading. 0 = never proven. */
    val verifiedAtMs: Long = 0,
)

@Serializable
data class BlacklistEntry(
    val prefix64: String,
    val untilMs: Long,
    val reason: String,
)

class WarpPool(ctx: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    /**
     * ONE pool per process.
     *
     * This used to be a per-instance snapshot of `warppool.json`: the tunnel service, the
     * rotation engine and the guard each constructed their own copy and each `persist()`
     * wrote its whole view back. Measured 2026-11-19 15:45: the service registered a working
     * identity through the engine's instance, then persisted its own (empty) list - `regs: []`
     * while a verified tunnel was carrying traffic. Every instance now shares one state object.
     */
    private class Shared(val store: File) {
        val mutex = Mutex()
        var regs: MutableList<WarpReg> = mutableListOf()
        var blacklist: MutableMap<String, BlacklistEntry> = linkedMapOf()

        /**
         * Identity explicitly chosen for the next bring-up.
         *
         * `usable()` used to be "the last entry", so the only way to install a specific
         * registration was to move it to the end of the list - and any later registration
         * silently displaced it. Measured 22 Nov 10:42: a hop promoted a proven address, the
         * keeper registered a spare in the same window, and the rebuild wore the spare.
         */
        var preferredId: String? = null
    }

    private val st: Shared = synchronized(HOLDER) {
        HOLDER.pool ?: Shared(File(ctx.applicationContext.filesDir, "warppool.json")).also { HOLDER.pool = it }
    }

    private val store: File get() = st.store
    private val mutex get() = st.mutex
    private var regs: MutableList<WarpReg>
        get() = st.regs
        set(v) { st.regs = v }
    private var blacklist: MutableMap<String, BlacklistEntry>
        get() = st.blacklist
        set(v) { st.blacklist = v }

    init { loadOnce() }

    private fun loadOnce() {
        synchronized(HOLDER) {
            if (HOLDER.loaded) return
            HOLDER.loaded = true
            if (!store.exists()) return
            runCatching {
                val data = json.decodeFromString<PoolData>(store.readText())
                // sanitize legacy entries: peerPubHex may have been persisted as base64
                regs = data.regs.map { it.copy(peerPubHex = sanitizeHexKey(it.peerPubHex)) }.toMutableList()
                blacklist = data.blacklist.associateBy { it.prefix64 }.toMutableMap()
                st.preferredId = data.preferred?.takeIf { id -> regs.any { it.id == id } }
            }
        }
    }

    private object HOLDER {
        var pool: Shared? = null
        var loaded = false
    }

    private fun sanitizeHexKey(s: String): String {
        val t = s.trim()
        if (t.matches(Regex("^[0-9a-fA-F]{64}$"))) return t.lowercase()
        return runCatching {
            val bytes = java.util.Base64.getDecoder().decode(t)
            require(bytes.size == 32)
            bytes.joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
    }

    @Serializable
    private data class PoolData(
        val regs: List<WarpReg>,
        val blacklist: List<BlacklistEntry>,
        /** Explicitly chosen identity; see [promote]. Null on older files. */
        val preferred: String? = null,
    )

    /**
     * The only writer.
     *
     * Eleven call sites each rebuilt `PoolData(regs, blacklist)` by hand, so any field added
     * later was silently dropped by ten of them - which is exactly how `preferred` would have
     * been lost on the next `setEgress`. One writer cannot forget a field.
     */
    private suspend fun writeLocked() {
        withContext(Dispatchers.IO) {
            store.writeText(
                json.encodeToString(
                    PoolData(regs.toList(), blacklist.values.toList(), st.preferredId),
                ),
            )
        }
    }

    suspend fun persist() = mutex.withLock { writeLocked() }

    /**
     * The identity the tunnel should wear.
     *
     * This used to be `regs.lastOrNull` - the **newest registration** - which is always the
     * freshest unproven spare. Measured 22 Nov 10:42: the pool held 7 proven addresses and the
     * tunnel wore `8b332803`, an unverified spare, on every rebuild. the owner: "I SEE THAT I HAVE 5
     * SOLID ADDRESS AND STILL NOT CONNECTED TO THE SOLID AND STILL NOT VERIFIED EVEN THO I HAVE
     * A LOT OF VERIFIED ADDRESS". The reserve existed and was never used.
     *
     * Order now:
     *  1. an explicitly promoted identity (a hop's chosen address, or a spare being proven);
     *  2. the newest **freshly proven** identity - measured within [STALE_MS];
     *  3. the newest proven identity of any age - stale proof still beats no proof, and the
     *     verify loop re-measures it on the next bring-up anyway;
     *  4. any usable spare, so a fresh install with no proof still connects.
     */
    fun usable(): WarpReg? {
        val now = System.currentTimeMillis()
        blacklist.entries.removeIf { it.value.untilMs != 0L && it.value.untilMs <= now }
        // skip entries with unusable keys (empty sanitize result) too
        val ok: (WarpReg) -> Boolean = { r ->
            r.peerPubHex.length == 64 &&
                !isBannedPrefix(r.egress64) &&
                !isBannedPrefix(prefix64Of(r.v6)) &&
                r.spentTokens < BUDGET
        }
        st.preferredId?.let { id ->
            regs.firstOrNull { it.id == id && ok(it) }?.let { return it }
        }
        regs.lastOrNull { ok(it) && it.verifiedAtMs > 0L && (now - it.verifiedAtMs) < STALE_MS }
            ?.let { return it }
        regs.lastOrNull { ok(it) && it.verifiedAtMs > 0L }?.let { return it }
        return regs.lastOrNull(ok)
    }

    private fun isBannedPrefix(p: String?): Boolean {
        if (p.isNullOrBlank()) return false
        val e = blacklist[p] ?: return false
        return e.untilMs == 0L || e.untilMs > System.currentTimeMillis()
    }

    /** Is this measured egress /64 already known to answer a wall? */
    fun isBanned(prefix: String): Boolean = isBannedPrefix(prefix)

    fun all(): List<WarpReg> = regs.toList()

    fun blacklistView(): List<BlacklistEntry> = blacklist.values.toList()

    /**
     * Retire a /64, the way their guard's state file does: a prefix that reported a quota
     * wall is never offered again, so a hop can never land back on one it already burned.
     *
     * Bounded, not permanent. Measured 2026-11-19 21:55: fifteen *permanent* entries covered
     * every prefix this location's WARP hands out (`2a09:bac1:5a*0:40::/64`,
     * `2a09:bac5:4ff*:*::/64`), so `usable()` returned null, `hop()` logged "rotation paused
     * (fresh /64s kept walling)" and the guard refused to rotate at all - which reads exactly
     * like "nothing works". Clearing the list (Deep reset) made the next prompt go through.
     * The server's address bucket is not provably permanent, so the ban must expire and let
     * the prefix be re-presented; their own control panel ships `clear_blacklist` for this.
     */
    suspend fun ban(prefix: String, reason: String, ttlMs: Long? = null) = mutex.withLock {
        if (prefix.isBlank()) return@withLock
        // Honour the server's own retry window when it gave one; clamp so a bad value can
        // neither lock a prefix out for days nor re-present it after seconds.
        //
        // Corrected 2026-11-20 00:05: the same egress answered the same prompt four minutes
        // after refusing it, so `retryAfterMs` (hours) is the ACCOUNT's deadline, not this
        // prefix's. Banning a /64 for hours starved the whole pool - the measured address
        // budget here is ~6 distinct /64s - and produced "no IPv6 egress - nothing to
        // rotate". A refused prefix gets a short rest, 5 min by default, never hours.
        val ttl = (ttlMs ?: BAN_TTL_MS).coerceIn(60_000L, 5 * 60_000L)
        blacklist[prefix] = BlacklistEntry(
            prefix,
            System.currentTimeMillis() + ttl,
            reason,
        )
        while (blacklist.size > BLACKLIST_MAX) blacklist.remove(blacklist.keys.first())
        writeLocked()
    }

    /**
     * Re-present the prefix that has been withheld longest, dropping its ban and returning it.
     *
     * Their panel ships `clear_blacklist` for the same reason. Without this, a small prefix
     * pool ends in a state where `usable()` is null and every hop refuses - measured
     * 2026-11-19: 15 bans covered every /64 WARP hands this location, so the guard sat
     * paused while a working tunnel was one rotation away.
     *
     * @return the prefix now available again, or null when nothing is withheld at all.
     */
    suspend fun liftOldestBan(): String? = mutex.withLock {
        val now = System.currentTimeMillis()
        blacklist.entries.removeIf { it.value.untilMs != 0L && it.value.untilMs <= now }
        val oldest = blacklist.entries.minByOrNull { it.value.untilMs } ?: return@withLock null
        blacklist.remove(oldest.key)
        writeLocked()
        oldest.key
    }
    /** Mark the egress /64 of this reg as walled (falls back to the interface prefix). */
    suspend fun wallHit(reg: WarpReg, reason: String) =
        ban(reg.egress64 ?: prefix64Of(reg.v6).orEmpty(), reason)

    /** Record the /64 Cloudflare actually reports for this registration, and the full
     *  address from the same reading when the caller has it. */
    suspend fun setEgress(reg: WarpReg, prefix: String, ip: String? = null) = mutex.withLock {
        val i = regs.indexOfFirst { it.id == reg.id }
        if (i >= 0) {
            val was = regs[i].egress64
            val spentWas = regs[i].spentTokens
            regs[i] = regs[i].copy(
                egress64 = prefix,
                egressIp = ip ?: regs[i].egressIp,
                // The budget belongs to the **/64**, not to the registration.
                //
                // Measured 22 Nov 10:54: registration `8b332803` reported `4ff2:2e1e::/64` at
                // 10:40 and `4ff1:2723::/64` at 10:54 with no re-registration in between. WARP
                // hands out a fresh /64 per connection, so keeping the spend attached to the
                // registration would drain a fresh address on the first turn and make the
                // reserve look empty while every rebuild handed us an untouched token.
                spentTokens = if (was != null && was != prefix) 0L else spentWas,
            )
            if (was != null && was != prefix && spentWas > 0L) {
                GuardState.log(
                    "pool",
                    "new /64 on ${reg.id.take(8)}: spend reset " +
                        "${spentWas / 1000}k -> 0k (budget is per /64, not per registration)",
                )
            }
            // A label that moves is evidence, not noise. Measured 22 Nov 09:51: the warm swap
            // logged `installed 2a09:bac5:4ff6:2e1e::/64` and the very next verify measured
            // `2a09:bac5:4ff3:263c::/64`. The swap was fine; the stored label was wrong, and
            // nothing said so. Every correction now names both values.
            if (was != null && was != prefix) {
                GuardState.log("pool", "label corrected ${reg.id.take(8)}: $was -> $prefix")
            }
            // One /64 is one token. Two registrations reporting the same prefix means one of
            // them is carrying nothing - say so instead of counting it twice.
            val twins = regs.count { it.id != reg.id && key(it) == prefix }
            if (twins > 0) {
                GuardState.log(
                    "pool",
                    "duplicate /64 $prefix on ${twins + 1} registrations - counted once",
                )
            }
        }
        writeLocked()
    }

    /**
     * Drop registrations whose /64 is already represented by a newer, better entry.
     *
     * Keeps the proven one over the unproven, and the most recently proven over the older.
     * A duplicate /64 is one address with one quota, so a second entry for it is a spare that
     * can never be spent - it only inflates the reserve count.
     */
    suspend fun pruneDuplicates(): Int = mutex.withLock {
        val before = regs.size
        val best = regs.groupBy { key(it) }.mapValues { (_, g) ->
            g.maxByOrNull { it.verifiedAtMs * 1_000L + it.createdAtMs }!!
        }
        val keep = best.values.map { it.id }.toSet()
        regs = regs.filter { it.id in keep }.toMutableList()
        val dropped = before - regs.size
        if (dropped > 0) {
            writeLocked()
            GuardState.log("pool", "pruned $dropped duplicate registration(s) - one /64, one token")
        }
        dropped
    }

    suspend fun retire(reg: WarpReg) = mutex.withLock {
        regs.removeAll { it.id == reg.id }
        // A pin to a retired identity would fall through to `usable()` step 2 anyway, but
        // clearing it keeps the file honest about what is actually chosen.
        if (st.preferredId == reg.id) st.preferredId = null
        writeLocked()
    }

    /**
     * Mark this identity as proven: a real `cdn-cgi/trace` came back through it.
     *
     * `setEgress` already stores the reading; this is the timestamp the panel sorts by, so
     * "solid" means *measured*, never "created and hoped for".
     */
    suspend fun markVerified(reg: WarpReg) = mutex.withLock {
        val i = regs.indexOfFirst { it.id == reg.id }
        if (i >= 0) regs[i] = regs[i].copy(verifiedAtMs = System.currentTimeMillis())
        writeLocked()
    }

    /**
     * Charge a turn's context against the address that carried it.
     *
     * the owner, 22 Nov: "ITS 200K TOKEN EACH". The free lane meters ~[BUDGET] of context per egress
     * address. Until now the only signal that an address was spent was the provider's refusal -
     * which is the wall he keeps sitting in. This makes the spend visible *before* the refusal, so
     * the swap can happen while a turn is still finishable instead of after it died.
     */
    suspend fun charge(reg: WarpReg, tokens: Long) = mutex.withLock {
        if (tokens <= 0L) return@withLock
        val i = regs.indexOfFirst { it.id == reg.id }
        if (i >= 0) regs[i] = regs[i].copy(spentTokens = regs[i].spentTokens + tokens)
        writeLocked()
    }

    /**
     * The unit Cloudflare meters: one egress /64.
     *
     * A token is a /64, not a registration - two registrations that report the same prefix are
     * ONE address with one quota. Counting them twice is how "9 solid" could be a lie.
     */
    private fun key(r: WarpReg): String = r.egress64 ?: prefix64Of(r.v6) ?: r.id

    /** Identities that are proven, unbanned and still carrying budget - the real "tokens". */
    fun solid(): List<WarpReg> {
        val now = System.currentTimeMillis()
        return regs.filter { r ->
            r.verifiedAtMs > 0L &&
                r.spentTokens < BUDGET &&
                r.peerPubHex.length == 64 &&
                !isBannedPrefix(r.egress64) &&
                !isBannedPrefix(prefix64Of(r.v6)) &&
                (now - r.verifiedAtMs) < STALE_MS
        }.distinctBy { key(it) }
    }

    /** Tokens still available across every proven address. */
    fun runway(): Long = solid().sumOf { (BUDGET - it.spentTokens).coerceAtLeast(0L) }

    /**
     * Identities that can be worn right now: proven ones first (newest proof first), then
     * pre-registered spares that have never carried traffic.
     *
     * A spare is not "solid" - it has no `cdn-cgi/trace` reading - but it is *ready*: no
     * Cloudflare round-trip stands between a wall and a working address. That is the difference
     * between a swap that takes 750 ms and one that takes 3-20 s of register retries.
     */
    fun ready(): List<WarpReg> {
        val proven = solid().sortedByDescending { it.verifiedAtMs }
        val spares = regs.filter { r ->
            r.verifiedAtMs == 0L &&
                r.spentTokens < BUDGET &&
                r.peerPubHex.length == 64 &&
                !isBannedPrefix(r.egress64) &&
                !isBannedPrefix(prefix64Of(r.v6))
        }.sortedByDescending { it.createdAtMs }
        return (proven + spares).distinctBy { key(it) }
    }

    /** One-line truth for the panel and the in-app banner. */
    fun summary(): String {
        val s = solid()
        val k = s.sumOf { (BUDGET - it.spentTokens).coerceAtLeast(0L) }
        return "${s.size} solid \u00b7 ${fmtTokens(k)} of ${fmtTokens(BUDGET * POOL_TARGET)} left"
    }

    private fun fmtTokens(v: Long): String = when {
        v >= 1_000_000L -> String.format(java.util.Locale.US, "%.1fM", v / 1_000_000.0)
        v >= 1_000L -> String.format(java.util.Locale.US, "%.0fk", v / 1_000.0)
        else -> v.toString()
    }

    /**
     * The panel's "Deep Reset" half that we can honour: forget every burned prefix, and
     * forget the measured egress of every live identity so the next measurement is the
     * one that decides. Chat history and the registrations themselves are untouched -
     * the same scope their `deep_reset_identity` documents ("chat sessions and project
     * data are preserved").
     */
    suspend fun clearBlacklist() = mutex.withLock {
        blacklist.clear()
        writeLocked()
    }

    suspend fun clearEgress() = mutex.withLock {
        regs = regs.map { it.copy(egress64 = null) }.toMutableList()
        writeLocked()
    }

    suspend fun add(reg: WarpReg) = mutex.withLock {
        regs.add(reg)
        writeLocked()
    }

    /**
     * Move a registration to the end of the list, which is the one `usable()` returns.
     *
     * `ZappVpnService` asks `pool.usable()` for its identity and `usable()` is `lastOrNull`, so
     * "install this proven address" is "make it last". The alternative - a separate `currentId`
     * field - would be a second source of truth about which identity the tunnel is wearing, and
     * the pool file is already the one the panel reads.
     */
    suspend fun promote(reg: WarpReg) = mutex.withLock {
        val i = regs.indexOfFirst { it.id == reg.id }
        if (i < 0) return@withLock
        // Set the pin, not just the list order. Order alone was not a choice: any registration
        // made after the promote landed at the end and became "the one to wear", which is how
        // a hop's proven address lost the swap to a keeper-registered spare (measured 22 Nov
        // 10:42 - 7 proven addresses parked, an unverified spare worn).
        st.preferredId = reg.id
        if (i != regs.lastIndex) {
            val r = regs.removeAt(i)
            regs.add(r)
        }
        writeLocked()
    }

    companion object {
        /** Their state file keeps 200; past that it drops the oldest block. */
        private const val BLACKLIST_MAX = 200

        /**
         * Context the free lane allows per egress address, in tokens.
         *
         * the owner's number, and the one the wall reports: "ITS 200K TOKEN EACH". It is a budget of
         * *context*, not of messages - a single 200k chat spends an address on its first turn,
         * which is why the pool has to be refilled long before it is empty.
         */
        const val BUDGET = 200_000L

        /** How many proven addresses to keep parked. Ten = ~2M tokens of runway. */
        const val POOL_TARGET = 10

        /**
         * A reading older than this is not trusted as proof any more.
         *
         * WARP's own handouts are long-lived, but an identity that has not answered a probe in
         * two hours is a guess, and the whole point of the pool is that the swap does not need a
         * verify wait.
         */
        const val STALE_MS = 2 * 60 * 60_000L

        /**
         * How long a burned prefix is withheld before it may be presented again. Chosen from
         * the measured pool: WARP hands this location only a handful of distinct /64s, so a
         * permanent ban exhausts them within one session and stalls rotation entirely.
         */
        /**
     * How long a refused /64 rests before it may be handed out again.
     *
     * Was 45 min, which combined with the server's multi-hour `retryAfterMs` locked every
     * /64 this location receives. Measured 2026-11-19 23:36 -> 23:40: the same prefix
     * answered the same prompt four minutes later. Five minutes is the honest number.
     */
    private const val BAN_TTL_MS = 5 * 60_000L

        fun prefix64Of(v6: String): String? = runCatching {
            // 2606:4700:xxxx:yyyy:... -> first 4 hextets = /64
            val parts = v6.split(":")
            if (parts.size < 4) null else parts.take(4).joinToString(":") + "::/64"
        }.getOrNull()
    }
}

/** X25519 keypair via the wireguard-android bundled crypto (package-private keygen â†’ derive via KeyPair). */
object KeyGen {
    fun newKeypair(): Pair<ByteArray, ByteArray> {
        val priv = ByteArray(32)
        SecureRandom().nextBytes(priv)
        val kp = com.wireguard.crypto.KeyPair(com.wireguard.crypto.Key.fromBytes(priv))
        return kp.privateKey.getBytes() to kp.publicKey.getBytes()
    }
}
