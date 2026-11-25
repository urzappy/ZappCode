package com.zapp.net.api

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.zapp.net.settings.Keys
import com.zapp.net.settings.zappStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie

/**
 * Persisted pair cookie = the app remembers the login forever.
 * Without this, every app restart forces a fresh `opencode pair` (links expire in 5 min).
 */
@Serializable
data class CookieData(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAt: Long,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
)

private val cj = Json { ignoreUnknownKeys = true }

fun cookieToData(c: Cookie): CookieData = CookieData(
    name = c.name, value = c.value, domain = c.domain, path = c.path,
    expiresAt = c.expiresAt, secure = c.secure, httpOnly = c.httpOnly,
)

fun dataToCookie(d: CookieData): Cookie? = runCatching {
    Cookie.Builder()
        .name(d.name).value(d.value)
        .domain(d.domain).path(d.path)
        .expiresAt(d.expiresAt)
        .apply {
            if (d.secure) secure()
            if (d.httpOnly) httpOnly()
        }
        .build()
}.getOrNull()

fun decodeCookies(raw: String): List<CookieData> =
    runCatching { cj.decodeFromString<List<CookieData>>(raw) }.getOrDefault(emptyList())

suspend fun saveCookies(context: Context, cookies: List<CookieData>) {
    context.zappStore.edit { it[Keys.COOKIES_JSON] = cj.encodeToString(cookies) }
}

suspend fun loadCookies(context: Context): List<CookieData> =
    decodeCookies(
        context.zappStore.data.map { it[Keys.COOKIES_JSON].orEmpty() }.first()
    )

/** The pair endpoint's real credential: {"token":"..."} -> sent as Basic auth on every call. */
suspend fun saveToken(context: Context, token: String) {
    context.zappStore.edit { it[Keys.AUTH_TOKEN] = token }
}

/**
 * The credential for the server at the saved address.
 *
 * Loopback is this phone's own `serve.sh`, and that server's password IS the app's
 * per-install device token (`BootRelay` passes it in front of `sh`). So there is nothing
 * stored to read and nothing to type: the same 64 hex chars are generated once and used
 * on both sides. A remote base keeps whatever credential was saved for it.
 */
suspend fun loadToken(context: Context): String {
    val p = context.zappStore.data.first()
    val base = p[Keys.SERVER_BASE] ?: com.zapp.net.settings.PHONE_SERVER
    val loopback = base.contains("127.0.0.1") || base.contains("localhost")
    return if (loopback) {
        com.zapp.net.onboarding.Onboarding.ensureToken(context)
    } else {
        p[Keys.AUTH_TOKEN].orEmpty()
    }
}
