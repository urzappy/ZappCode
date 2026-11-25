package com.zapp.net.discord

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The card the owner asked for: a real Discord server, rendered natively.
 *
 * No WebView, no OAuth page, no link. `GET /api/v10/invites/{code}?with_counts=true` is
 * public and needs no token - measured on his phone 2026-11-20:
 *
 *     GET /api/v10/invites/discord-developers?with_counts=true -> HTTP 200
 *     {"guild":{"id":"613425648685547541","name":"Discord Developers",
 *               "icon":"a_1d18...","features":[...,"ANIMATED_ICON","VERIFIED",...]},
 *      "approximate_presence_count":...,"approximate_member_count":...,"expires_at":null}
 *
 * So the name, the logo, the online count and the member count are all real, and the app
 * never has to open a browser to get them.
 */
@Serializable
data class DiscordGuild(
    val id: String? = null,
    val name: String? = null,
    val icon: String? = null,
    val description: String? = null,
    val features: List<String> = emptyList(),
)

@Serializable
data class DiscordInvite(
    val code: String? = null,
    val guild: DiscordGuild? = null,
    @SerialName("approximate_member_count") val members: Int? = null,
    @SerialName("approximate_presence_count") val online: Int? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    /** Present when the code is dead: `{"message": "Unknown Invite", "code": 10006}`. */
    val message: String? = null,
) {
    val guildName: String get() = guild?.name ?: ""
    val verified: Boolean get() = guild?.features?.contains("VERIFIED") == true
    val animatedIcon: Boolean get() = guild?.icon?.startsWith("a_") == true

    /**
     * `cdn.discordapp.com/icons/{guild}/{hash}.png?size=128`
     *
     * PNG even for animated servers: the animated form is `.gif`, which is far larger for a
     * 64 dp tile and buys nothing on this screen. Verified against the live guild above.
     */
    val iconUrl: String?
        get() {
            val g = guild?.id ?: return null
            val h = guild?.icon ?: return null
            return "https://cdn.discordapp.com/icons/$g/$h.png?size=128"
        }
}

/**
 * Turns whatever the user pasted into an invite code.
 *
 * He may paste `discord.gg/abc`, `https://discord.com/invite/abc`, `abc`, or the whole
 * message with a trailing slash. All of them have to resolve, because making a user trim a
 * URL by hand is how a working flow gets reported as broken.
 */
object InviteCode {
    private val patterns = listOf(
        Regex("""discord\.gg/([A-Za-z0-9-]{2,64})"""),
        Regex("""discord(?:app)?\.com/invite/([A-Za-z0-9-]{2,64})"""),
        Regex("""^([A-Za-z0-9-]{2,64})$"""),
    )

    fun parse(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        for (p in patterns) {
            val m = p.find(s) ?: continue
            return m.groupValues[1]
        }
        return null
    }
}

/** One call, no auth. Failures are returned, never thrown: the dialog shows the reason. */
object DiscordApi {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    sealed interface Result {
        data class Ok(val invite: DiscordInvite) : Result
        data class Err(val reason: String) : Result
    }

    fun fetch(code: String): Result {
        val url = "https://discord.com/api/v10/invites/$code?with_counts=true"
        return runCatching {
            http.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
                .execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    val parsed = runCatching { json.decodeFromString<DiscordInvite>(body) }
                        .getOrElse { return@use Result.Err("unreadable invite (HTTP ${r.code})") }
                    when {
                        r.isSuccessful && !parsed.guildName.isBlank() -> Result.Ok(parsed)
                        r.code == 404 -> Result.Err("invite not found or expired")
                        parsed.message != null -> Result.Err(parsed.message)
                        else -> Result.Err("HTTP ${r.code}")
                    }
                }
        }.getOrElse { Result.Err(it.message ?: "network error") }
    }

    /** The bytes for [DiscordInvite.iconUrl], decoded by the caller. */
    fun iconBytes(url: String): ByteArray? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.bytes() else null
        }
    }.getOrNull()
}
