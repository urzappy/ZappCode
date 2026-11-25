package com.zapp.net.onboarding

import android.content.Context
import java.security.SecureRandom

/**
 * Persisted onboarding state. Three facts and one secret, nothing more.
 *
 * Deliberately NOT stored: the plaintext address. Not in prefs, not in a file, not in a log.
 * `IpIdentity.plain` lives in memory for the length of the consent screen and is dropped
 * when it closes. Once [storeIdentity] returns, the address is gone and only the 16-hex id
 * remains - which is the entire point of hashing it on the device.
 */
object Onboarding {

    private const val FILE = "zapp_onboarding"

    private const val K_CONSENT = "consent_accepted_ms"
    private const val K_ID = "identity_id"
    private const val K_MASKED = "identity_masked"
    private const val K_FP = "identity_fingerprint"
    private const val K_FAMILY = "identity_family"
    private const val K_TOKEN = "device_token"
    private const val K_TERMUX = "termux_ready_ms"
    private const val K_INVITE = "discord_invite_code"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- gate -------------------------------------------------------------

    /** Nothing is read before this is true: the first screen is the consent itself. */
    fun consented(ctx: Context): Boolean = sp(ctx).getLong(K_CONSENT, 0L) > 0L

    fun acceptConsent(ctx: Context) {
        sp(ctx).edit().putLong(K_CONSENT, System.currentTimeMillis()).apply()
    }

    // --- identity ---------------------------------------------------------

    fun hasIdentity(ctx: Context): Boolean = sp(ctx).getString(K_ID, null) != null

    fun id(ctx: Context): String? = sp(ctx).getString(K_ID, null)
    fun masked(ctx: Context): String = sp(ctx).getString(K_MASKED, "").orEmpty()
    fun fingerprint(ctx: Context): String = sp(ctx).getString(K_FP, "").orEmpty()
    fun family(ctx: Context): String = sp(ctx).getString(K_FAMILY, "").orEmpty()

    /**
     * Write the hash and drop the address.
     *
     * The token is minted here rather than at first server contact so the app is fully
     * usable with no server at all - Discord linking arrives last and will adopt whatever
     * token already exists instead of replacing it.
     */
    fun storeIdentity(ctx: Context, identity: IpIdentity.Identity) {
        sp(ctx).edit()
            .putString(K_ID, identity.id)
            .putString(K_MASKED, identity.masked)
            .putString(K_FP, identity.fingerprint)
            .putString(K_FAMILY, identity.family.name)
            .apply()
        if (token(ctx).isBlank()) sp(ctx).edit().putString(K_TOKEN, newToken()).apply()
    }

    // --- device token -----------------------------------------------------

    /**
     * 32 random bytes from `/dev/urandom`, hex. Unrelated to the address on purpose: the
     * tunnel address changes every reconnect (measured a dozen times on 2026-11-20), so a
     * session that keyed off it would log the user out every few minutes. The hash binds
     * the first contact; this token carries the session after that.
     */
    fun token(ctx: Context): String = sp(ctx).getString(K_TOKEN, "").orEmpty()

    fun ensureToken(ctx: Context): String {
        val existing = token(ctx)
        if (existing.isNotBlank()) return existing
        val fresh = newToken()
        sp(ctx).edit().putString(K_TOKEN, fresh).apply()
        return fresh
    }

    private fun newToken(): String {
        val b = ByteArray(32)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { String.format("%02x", it) }
    }

    // --- termux gate ------------------------------------------------------

    fun termuxReady(ctx: Context): Boolean = sp(ctx).getLong(K_TERMUX, 0L) > 0L

    fun markTermuxReady(ctx: Context) {
        sp(ctx).edit().putLong(K_TERMUX, System.currentTimeMillis()).apply()
    }

    // --- discord (last, and optional) -------------------------------------

    /**
     * The invite code is the whole of the Discord state.
     *
     * There is no `linked` flag, no `discordId`, no join check and no leave check - the owner
     * removed that layer on 2026-11-20: "remove mo muna yung discord leave and join". The
     * card is an invitation, `Skip` is always on screen, and nothing in the app gates on it.
     */

    /**
     * The server's invite code, parsed from whatever link the owner pasted.
     *
     * Only the code is kept (`abc123`), never the full URL, so the card always reads live
     * data instead of a stale copy of the name or the member count.
     */
    fun discordInvite(ctx: Context): String = sp(ctx).getString(K_INVITE, DEFAULT_INVITE).orEmpty()

    /**
     * The server this build ships pointing at.
     *
     * Verified from the phone 2026-11-20 against the public API:
     *
     *     GET /api/v10/invites/RQ6NVspT2u?with_counts=true
     *       guild   1571905536658776136 | ZAPP OPENCODE CP
     *       expires None          (never expires, no max uses)
     *
     * A cleared value is stored as `""`, and SharedPreferences hands back that empty string
     * rather than the default - so "Change invite" still means what it says.
     */
    private const val DEFAULT_INVITE = "RQ6NVspT2u"

    fun setDiscordInvite(ctx: Context, code: String) {
        sp(ctx).edit().putString(K_INVITE, code.trim()).apply()
    }

    /** Wipes everything except the consent, so the flow can be replayed without re-agreeing. */
    fun reset(ctx: Context) {
        sp(ctx).edit()
            .remove(K_ID).remove(K_MASKED).remove(K_FP).remove(K_FAMILY)
            .remove(K_TOKEN).remove(K_TERMUX)
            .apply()
    }
}
