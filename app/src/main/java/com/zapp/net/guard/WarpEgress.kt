package com.zapp.net.guard

/**
 * Is this address a Cloudflare WARP egress?
 *
 * This used to be a two-literal test, copy-pasted into [AutoRepair] and [Doctor]:
 *
 *     startsWith("2606:4700:110:") || startsWith("2a09:bac5:")
 *
 * WARP does not hand out only `2a09:bac5`. This project's own logs carry verified
 * `warp=on` egresses from **both** /32s, and the `bac1` one is the more common of the two:
 *
 * ```
 *   egress local: ip=2a09:bac1:5a80:40::3cf:5b warp=on     22 Nov 16:56
 *   [hop] rotated #1 -> 2a09:bac1:5ac0:40::/64              20 Nov 01:37
 *   tunnel VERIFY ok: /64=2a09:bac1:5aa0:40::/64            21 Nov 05:36
 *   egress local: ip=2a09:bac5:4ff1:2e1e::498:24 warp=on    22 Nov 16:33
 * ```
 *
 * So on `bac1` the old test said "not WARP" while the tunnel was verifiably carrying
 * traffic. [AutoRepair] reads exactly that predicate to decide the route is broken, which
 * put it in a permanent repair loop: every tick tore the tunnel down, rebuilt it, measured
 * `bac1` again, and tore it down again. A live reply dies with every rebuild - that is the
 * "pause then a blink" the owner reported on 22 Nov, and it is why repairs kept starting on
 * their own at 16:55:32 and 16:56:40 with nobody touching the button.
 *
 * Both /32s share `2a09:bac`, so the test is on that stem plus the client-address /48.
 * A prefix that is not WARP still returns false - this widens the net to the range
 * Cloudflare actually uses, it does not accept anything that answers.
 */
object WarpEgress {

    /** `2606:4700:110::/48` - the address WARP assigns the tunnel interface. */
    private const val CLIENT = "2606:4700:110:"

    /** `2a09:bac0::/28` covers `bac1` (5a*) and `bac5` (4ff*), the two seen on this device. */
    private const val EGRESS = "2a09:bac"

    fun isTunnel(addr: String?): Boolean {
        val a = addr.orEmpty().trim().lowercase()
        if (a.isEmpty()) return false
        return a.startsWith(CLIENT) || a.startsWith(EGRESS)
    }
}
