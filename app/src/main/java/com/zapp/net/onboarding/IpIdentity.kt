package com.zapp.net.onboarding

import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest

/**
 * The whole login, minus the server.
 *
 * The operator must never be able to learn a user's address, so the address is turned into
 * an identifier **on the device** and only the identifier is ever sent anywhere:
 *
 *     canonical ip  ->  "zapp-ip-id-v1|" + ip  ->  SHA-256  ->  first 8 bytes  ->  hex
 *
 * Nobody holds a key, so nobody can decrypt it back. The privacy comes from the size of the
 * address space, not from a secret:
 *
 *  - an IPv6 host address lives inside a /64, so there are 2^64 candidates to test. A
 *    preimage search is not feasible on any hardware that exists.
 *  - IPv4 has 2^32 candidates, which one machine enumerates in hours. **IPv4 is not private**
 *    and [Strength] says so out loud instead of pretending otherwise.
 *
 * Two devices that share an address share an id. That is the intended behaviour: the id is
 * the address, not the phone.
 */
object IpIdentity {

    /** Bumped only if the canonical form below ever changes; keeps old ids stable. */
    private const val DOMAIN = "zapp-ip-id-v1|"

    enum class Strength { V6_PRIVATE, V4_NOT_PRIVATE, UNKNOWN }

    data class Identity(
        /** 16 uppercase hex characters, grouped `XXXX-XXXX-XXXX-XXXX`. */
        val id: String,
        /** What the user is allowed to see of their own address. */
        val masked: String,
        /** Short tail so the owner can recognise their own machine. */
        val fingerprint: String,
        val family: Strength,
        /** The plaintext, kept in memory only. Never written to disk, never logged. */
        val plain: String,
    )

    /**
     * The exact address the provider sees, reduced to a stable form.
     *
     * Lowercase, no `%zone`, no leading/trailing space, no IPv4-mapped IPv6 prefix. An IPv6
     * address is used as written - we deliberately do NOT collapse to /64, because the
     * provider buckets on the host address and two users must not collide.
     */
    fun canonical(ip: String): String {
        var s = ip.trim().lowercase().substringBefore("%")
        if (s.startsWith("::ffff:") && s.contains('.')) s = s.removePrefix("::ffff:")
        return s.trim('[', ']')
    }

    fun identify(ip: String): Identity? {
        val canon = canonical(ip)
        if (canon.isBlank()) return null
        val isV6 = canon.contains(':')
        val strength = when {
            !isV6 && canon.count { it == '.' } == 3 -> Strength.V4_NOT_PRIVATE
            isV6 -> Strength.V6_PRIVATE
            else -> Strength.UNKNOWN
        }
        return Identity(
            id = group(idOf(canon)),
            masked = if (isV6) maskV6(canon) else maskV4(canon),
            fingerprint = fingerprintOf(canon, isV6),
            family = strength,
            plain = canon,
        )
    }

    /** 16 uppercase hex characters. The only thing that ever leaves the device. */
    fun idOf(canonicalIp: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val d = md.digest((DOMAIN + canonicalIp).toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(16)
        for (i in 0 until 8) sb.append(String.format("%02X", d[i]))
        return sb.toString()
    }

    private fun group(hex: String): String =
        hex.chunked(4).joinToString("-")

    /**
     * `2a09:bac5:4ff3:25d7::3c5:10`  ->  `2a09:bac5:••••:••••:••••:••••:••••:•••`
     *
     * The first two hextets stay so the user can tell WARP from carrier, and the tail is
     * shown separately by [fingerprintOf]. Everything that would identify the machine is
     * replaced. This string is what the operator may see; it cannot be hashed back to an
     * id without enumerating the hidden part.
     */
    private fun maskV6(addr: String): String {
        val head = addr.split(':').take(2).joinToString(":")
        return "$head:" + List(6) { "••••" }.joinToString(":")
    }

    /** `203.0.113.47` -> `203.•••.•••.47` */
    private fun maskV4(addr: String): String {
        val p = addr.split('.')
        if (p.size != 4) return "•••"
        return "${p[0]}.•••.•••.${p[3]}"
    }

    /**
     * The last two hextets (IPv6) or last octet (IPv4) - enough for the owner to confirm
     * "that's me" and for a support ticket to name one machine, useless to anyone else.
     */
    private fun fingerprintOf(addr: String, isV6: Boolean): String {
        if (!isV6) return "#" + addr.substringAfterLast('.')
        val groups = expand(addr)
        return "#" + groups.takeLast(2).joinToString(":")
    }

    /** Full 8 x 16-bit expansion, so `::` cannot make two different addresses look alike. */
    private fun expand(addr: String): List<String> {
        val parts: List<String> =
            if (addr.contains("::")) addr.split("::", limit = 2) else listOf(addr, "")
        val head = parts[0]
        val tail = parts[1]
        val h = head.split(':').filter { it.isNotEmpty() }
        val t = tail.split(':').filter { it.isNotEmpty() }
        val missing = 8 - h.size - t.size
        val mid = if (addr.contains("::")) List(missing.coerceAtLeast(0)) { "0" } else emptyList()
        return (h + mid + t).map { it.padStart(4, '0') }
    }

    /** True when the value looks like something we can hash at all. */
    fun looksLikeAddress(ip: String): Boolean {
        val c = canonical(ip)
        if (c.isBlank()) return false
        return runCatching { InetAddress.getByName(c) }.isSuccess
    }

    /** Reports the JDK's own classification, used only for the on-screen label. */
    fun engineFamily(ip: String): String = runCatching {
        when (InetAddress.getByName(canonical(ip))) {
            is Inet6Address -> "IPv6"
            else -> "IPv4"
        }
    }.getOrDefault("unknown")
}
