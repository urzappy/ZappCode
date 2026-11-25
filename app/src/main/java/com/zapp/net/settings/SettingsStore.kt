package com.zapp.net.settings

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.zappStore by preferencesDataStore(name = "zapp")

object Keys {
    val SERVER_BASE = stringPreferencesKey("server_base")
    val PROVIDER_ID = stringPreferencesKey("provider_id")
    val MODEL_ID = stringPreferencesKey("model_id")
    val VARIANT = stringPreferencesKey("variant")
    val MODEL_LABEL = stringPreferencesKey("model_label")
    val HOPS = longPreferencesKey("hops")
    val WALLS = longPreferencesKey("walls")
    val COOKIES_JSON = stringPreferencesKey("cookies_json")
    val AUTH_TOKEN = stringPreferencesKey("auth_token")
    val SESSION_ID = stringPreferencesKey("session_id")
}

/** The phone's own `opencode serve` port. `127.0.0.1` on Android is the phone, so the
    old PC dev port stored from earlier test builds can never reach anything — map it
    once on read instead of leaving a dead address in the field. */
const val PHONE_SERVER = "http://127.0.0.1:4096"
private const val LEGACY_PC_PORT = "http://127.0.0.1:28894"

data class Settings(
    val serverBase: String = PHONE_SERVER,
    val providerID: String = "",
    val modelID: String = "",
    val variant: String = "",
    val modelLabel: String = "",
    val hops: Long = 0,
    val walls: Long = 0,
    val cookiesJson: String = "",
    val authToken: String = "",
    val sessionId: String = "",
)

fun observe(context: Context): Flow<Settings> =
    context.zappStore.data.map { p ->
        val base = (p[Keys.SERVER_BASE] ?: PHONE_SERVER).let {
            if (it.trimEnd('/') == LEGACY_PC_PORT) PHONE_SERVER else it
        }
        Settings(
            serverBase = base,
            providerID = p[Keys.PROVIDER_ID].orEmpty(),
            modelID = p[Keys.MODEL_ID].orEmpty(),
            variant = p[Keys.VARIANT].orEmpty(),
            modelLabel = p[Keys.MODEL_LABEL].orEmpty(),
            hops = p[Keys.HOPS] ?: 0L,
            walls = p[Keys.WALLS] ?: 0L,
            cookiesJson = p[Keys.COOKIES_JSON].orEmpty(),
            // Loopback is this phone's own `serve.sh`, started with the app's device token
            // as its password (`BootRelay`). That token IS the credential - nothing was
            // ever typed and nothing is stored for it. A remote base keeps its saved one.
            authToken = if (isLoopback(base)) {
                com.zapp.net.onboarding.Onboarding.ensureToken(context)
            } else {
                p[Keys.AUTH_TOKEN].orEmpty()
            },
            sessionId = p[Keys.SESSION_ID].orEmpty(),
        )
    }

/** True when [raw] points at this handset. */
private fun isLoopback(raw: String): Boolean {
    val s = raw.lowercase()
    return s.contains("127.0.0.1") || s.contains("localhost") || s.contains("[::1]")
}

suspend fun saveModel(context: Context, providerID: String, modelID: String, variant: String, label: String) {
    context.zappStore.edit { p ->
        p[Keys.PROVIDER_ID] = providerID
        p[Keys.MODEL_ID] = modelID
        p[Keys.VARIANT] = variant
        p[Keys.MODEL_LABEL] = label
    }
}

suspend fun saveServer(context: Context, serverBase: String) {
    context.zappStore.edit { it[Keys.SERVER_BASE] = serverBase }
}

suspend fun saveSession(context: Context, sessionId: String) {
    context.zappStore.edit { it[Keys.SESSION_ID] = sessionId }
}

suspend fun saveStats(context: Context, hops: Long, walls: Long) {
    context.zappStore.edit { p ->
        p[Keys.HOPS] = hops
        p[Keys.WALLS] = walls
    }
}
