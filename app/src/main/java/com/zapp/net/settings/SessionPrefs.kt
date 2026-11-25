package com.zapp.net.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * PORT of two desktop stores. Neither touches the tunnel, so they live in their own file
 * instead of `GuardPrefs`:
 *
 *  - `shell/state/session-preferences.ts:25-33` — the `hidden` and `muted` arrays, one entry
 *    per session, both defaulting to `[]` (`:43-49`).
 *  - `settings/model.tsx:635-656` — the notification and sound defaults:
 *    `notifications: { agent: false, permissions: true, questions: true, errors: false }`,
 *    `sounds: { agentEnabled: true, permissionsEnabled: true, questionsEnabled: true,
 *    errorsEnabled: true, interfaceEnabled: true }`.
 *
 * Their `update` (`session-preferences.ts:81-98`) is a set add/remove and `forget` (`:113-119`)
 * clears both lists for one session; ours is the same, stored as StringSets.
 *
 * Manual unread is `notification.tsx:887-925`: `markUnread` records the session id locally
 * (`manual.sessions`) and `markRead` is the server call. A session is unread while the
 * server's `time.viewed` is behind `time.updated` **or** that local flag is set — which is why
 * `Session.unread` alone cannot answer it.
 */
object SessionPrefs {

    private const val FILE = "zapp_session_prefs"

    /** `settings/model.tsx:635` — what actually raises an OS notification. */
    private val _notify = MutableStateFlow(com.zapp.net.notify.NotifySettings())
    val notify: StateFlow<com.zapp.net.notify.NotifySettings> = _notify
    const val KEY_AGENT = "notify_agent"
    const val KEY_PERMISSIONS = "notify_permissions"
    const val KEY_QUESTIONS = "notify_questions"
    const val KEY_ERRORS = "notify_errors"
    const val KEY_SOUND_AGENT = "sound_agent"
    const val KEY_SOUND_PERMISSIONS = "sound_permissions"
    const val KEY_SOUND_QUESTIONS = "sound_questions"
    const val KEY_SOUND_ERRORS = "sound_errors"
    const val KEY_SOUND_INTERFACE = "sound_interface"

    /**
     * `settings/model.tsx:641-653` — the four sound **ids**. Desktop picks the wav by these
     * names (`staplebops-01`, `staplebops-02`, `staplebops-05`, `nope-03`).
     *
     * The wavs are not in the APK, so nothing here is played: Android has one notification
     * tone and `Notifier` uses it. The ids are still stored so the choice is not lost if the
     * packs are ever shipped, and so a reader can see what the default was.
     */
    const val KEY_ID_AGENT = "sound_id_agent"
    const val KEY_ID_PERMISSIONS = "sound_id_permissions"
    const val KEY_ID_QUESTIONS = "sound_id_questions"
    const val KEY_ID_ERRORS = "sound_id_errors"

    const val DEFAULT_ID_AGENT = "staplebops-01"
    const val DEFAULT_ID_PERMISSIONS = "staplebops-02"
    const val DEFAULT_ID_QUESTIONS = "staplebops-05"
    const val DEFAULT_ID_ERRORS = "nope-03"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = sp(ctx)
        _notify.value = com.zapp.net.notify.NotifySettings(
            agent = p.getBoolean(KEY_AGENT, false),
            permissions = p.getBoolean(KEY_PERMISSIONS, true),
            questions = p.getBoolean(KEY_QUESTIONS, true),
            errors = p.getBoolean(KEY_ERRORS, false),
            soundAgent = p.getBoolean(KEY_SOUND_AGENT, true),
            soundPermissions = p.getBoolean(KEY_SOUND_PERMISSIONS, true),
            soundQuestions = p.getBoolean(KEY_SOUND_QUESTIONS, true),
            soundErrors = p.getBoolean(KEY_SOUND_ERRORS, true),
            soundInterface = p.getBoolean(KEY_SOUND_INTERFACE, true),
            idAgent = p.getString(KEY_ID_AGENT, DEFAULT_ID_AGENT) ?: DEFAULT_ID_AGENT,
            idPermissions = p.getString(KEY_ID_PERMISSIONS, DEFAULT_ID_PERMISSIONS)
                ?: DEFAULT_ID_PERMISSIONS,
            idQuestions = p.getString(KEY_ID_QUESTIONS, DEFAULT_ID_QUESTIONS)
                ?: DEFAULT_ID_QUESTIONS,
            idErrors = p.getString(KEY_ID_ERRORS, DEFAULT_ID_ERRORS) ?: DEFAULT_ID_ERRORS,
        )
    }

    fun setNotify(ctx: Context, key: String, value: Boolean) {
        sp(ctx).edit().putBoolean(key, value).apply()
        load(ctx)
    }

    /** A sound id or another string-valued key (`sounds.agent` etc.). */
    fun string(ctx: Context, key: String, fallback: String): String =
        sp(ctx).getString(key, null) ?: fallback

    fun set(ctx: Context, key: String, value: String) {
        sp(ctx).edit().putString(key, value).apply()
    }

    // ---- per-session lists -------------------------------------------------------------

    private fun read(ctx: Context, name: String): Set<String> =
        sp(ctx).getStringSet(name, emptySet()).orEmpty().toSet()

    private fun write(ctx: Context, name: String, ids: Set<String>) {
        sp(ctx).edit().putStringSet(name, ids).apply()
    }

    private fun update(ctx: Context, name: String, id: String, on: Boolean) {
        val now = read(ctx, name).toMutableSet()
        if (on) now.add(id) else now.remove(id)
        write(ctx, name, now)
    }

    /** `session-preferences.ts:81-98` — `update("hidden", …)`. */
    fun hidden(ctx: Context): Set<String> = read(ctx, "hidden")

    fun setHidden(ctx: Context, id: String, on: Boolean) = update(ctx, "hidden", id, on)

    /** `session-preferences.ts:81-98` — `update("muted", …)`. */
    fun muted(ctx: Context): Set<String> = read(ctx, "muted")

    fun setMuted(ctx: Context, id: String, on: Boolean) = update(ctx, "muted", id, on)

    /** `notification.tsx:921-925` — local only, never a server call. */
    fun manualUnread(ctx: Context): Set<String> = read(ctx, "manual_unread")

    fun setManualUnread(ctx: Context, id: String, on: Boolean) =
        update(ctx, "manual_unread", id, on)

    /** `session-preferences.ts:113-119` — `forget` clears every list for one session. */
    fun forget(ctx: Context, id: String) {
        setHidden(ctx, id, false)
        setMuted(ctx, id, false)
        setManualUnread(ctx, id, false)
    }

    /** `notification.tsx:901-905` — a child inherits its root's mute, so ask the root. */
    fun mutedFor(ctx: Context, sessionId: String, rootId: String?): Boolean {
        val set = muted(ctx)
        return sessionId in set || (rootId != null && rootId in set)
    }
}
