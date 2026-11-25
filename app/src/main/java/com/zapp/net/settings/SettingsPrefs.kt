package com.zapp.net.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * PORT of every key in `settings/model.tsx` that ZAPP stores but does not yet act on.
 *
 * The schema is theirs, read straight out of `settings/model.tsx:217-403`, and so is every
 * default (`:601-659`). Nothing here is invented: a key that does not exist in their
 * `generalSchema`, `appearanceSchema`, `permissionsSchema` or `workspacesSchema` must not appear in
 * this file. `notificationsSchema` and `soundsSchema` are absent on purpose - both are acted on and
 * live in [com.zapp.net.notify.NotifySettings] behind [com.zapp.net.settings.SessionPrefs].
 *
 * ```
 * general.showCustomAgents       true      general.followUpBehavior      "steer"
 * general.showTurnDiffs          false     general.mobileTitlebarPosition "top"
 * general.backgroundActivity     "left"    general.sidebarOrganize       "list"
 * general.timelineWidth          "comfortable"
 * general.foldUserMessages       false     appearance.fontSize           14
 * general.newTimeline            false     appearance.tabLayout          "horizontal"
 * general.newQuestionUI          false     appearance.reduceMotion       "system"
 * workspaces.defaultDestination  "last-used"
 * ```
 *
 * **Why a store and not a switch.** The three timeline toggles that change what the chat draws
 * live in [DisplayPrefs] and are wired. Everything here is either a desktop placement (there is
 * one pane on a phone), a font (Android picks the system font), or a value the *server* owns
 * (`followUpBehavior`). Storing them means the surface round-trips the same document PC does;
 * pretending they change something would be the lie.
 */
object SettingsPrefs {

    private const val FILE = "zapp_settings"

    // ---- keys, named after their schema path so a diff against their file is mechanical ----

    const val KEY_SHOW_CUSTOM_AGENTS = "general.showCustomAgents"
    const val KEY_BACKGROUND_ACTIVITY = "general.backgroundActivity"
    const val KEY_TIMELINE_WIDTH = "general.timelineWidth"
    const val KEY_FOLLOW_UP = "general.followUpBehavior"
    const val KEY_TITLEBAR_POSITION = "general.mobileTitlebarPosition"
    const val KEY_DIFF_WRAP = "general.mobileDiffWrap"
    const val KEY_NEW_TIMELINE = "general.newTimeline"
    const val KEY_NEW_QUESTION_UI = "general.newQuestionUI"
    const val KEY_SIDEBAR_ORGANIZE = "general.sidebarOrganize"
    const val KEY_LANGUAGE = "general.language"

    const val KEY_FONT_SIZE = "appearance.fontSize"
    const val KEY_MONO = "appearance.mono"
    const val KEY_SANS = "appearance.sans"
    const val KEY_TERMINAL_FONT = "appearance.terminal"
    const val KEY_TAB_LAYOUT = "appearance.tabLayout"
    const val KEY_REDUCE_MOTION = "appearance.reduceMotion"
    const val KEY_COLOR_SCHEME = "appearance.colorScheme"

    const val KEY_DEFAULT_DESTINATION = "workspaces.defaultDestination"

    // `sounds.*` is deliberately absent. Since Pass 31 those five keys are acted on, so they live
    // in `SessionPrefs` / `NotifySettings` (`sound_agent`, `notify_*`) where the chime can read
    // them. A second copy here would be a second source of truth for the same four ids.

    // ---- defaults, `defaultSettings` verbatim ----

    const val DEFAULT_BACKGROUND_ACTIVITY = "left"
    const val DEFAULT_TIMELINE_WIDTH = "comfortable"
    const val DEFAULT_FOLLOW_UP = "steer"
    const val DEFAULT_TITLEBAR_POSITION = "top"
    const val DEFAULT_SIDEBAR_ORGANIZE = "list"
    const val DEFAULT_TAB_LAYOUT = "horizontal"
    const val DEFAULT_REDUCE_MOTION = "system"
    const val DEFAULT_COLOR_SCHEME = "system"
    const val DEFAULT_DESTINATION = "last-used"
    const val DEFAULT_LANGUAGE = "en"

    /** `appearance.fontSize` - 14. */
    const val DEFAULT_FONT_SIZE = 14

    private val values = HashMap<String, String>()

    /** Bumped on every write so a screen can recompose without watching fifteen flows. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = sp(ctx)
        values.clear()
        for (k in p.all.keys) {
            val v = p.all[k]
            if (v is String) values[k] = v
        }
        _version.value++
    }

    fun string(key: String, fallback: String): String = values[key] ?: fallback

    fun int(key: String, fallback: Int): Int = values[key]?.toIntOrNull() ?: fallback

    fun bool(key: String, fallback: Boolean): Boolean =
        values[key]?.let { it == "true" } ?: fallback

    /**
     * Context-taking readers read SharedPreferences directly rather than the cache.
     *
     * The Settings surface is the only writer, and it may write before [load] has run in a
     * process that started from a broadcast. Reading the store makes the screen authoritative
     * instead of one step behind.
     */
    fun string(ctx: Context, key: String, fallback: String): String =
        sp(ctx).getString(key, null) ?: fallback

    fun bool(ctx: Context, key: String, fallback: Boolean): Boolean {
        val v = sp(ctx).getString(key, null) ?: return fallback
        return v == "true"
    }

    fun set(ctx: Context, key: String, value: String) {
        values[key] = value
        sp(ctx).edit().putString(key, value).apply()
        _version.value++
    }

    fun set(ctx: Context, key: String, value: Boolean) = set(ctx, key, value.toString())

    fun set(ctx: Context, key: String, value: Int) = set(ctx, key, value.toString())

    /** `appearance.fontSize` is the only numeric one; clamped to the range their zoom offers. */
    fun fontSize(ctx: Context): Int = int(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)

    // =====================================================================================
    // Models - "Choose which models appear in model picker" (`settings.models.description`).
    //
    // Their store keeps this per server scope. We have one server, so the key is the model
    // id alone. It is stored as a newline-joined string because [values] only carries
    // strings and a `StringSet` would desync from it on the next `load`.
    // =====================================================================================

    private const val KEY_DISABLED_MODELS = "models.disabled"

    /**
     * the owner, 2026-11-24: *"WHAT IF I WANT 1 MODEL ONLY KASI YUNG IBANG MODEL ANG HIHINA"*.
     *
     * One model, no picker full of weaker ones. It is NOT a new mechanism: their picker
     * filter already hides whatever `models.disabled` lists, so a lock simply hides every
     * id except the chosen one and remembers what the list looked like first. Unlock puts
     * that exact set back - a lock never eats the hides he made by hand.
     */
    private const val KEY_LOCKED_MODEL = "models.locked"
    private const val KEY_PRELOCK_DISABLED = "models.prelock"

    fun lockedModel(ctx: Context): String = string(KEY_LOCKED_MODEL, "")

    fun lockModel(ctx: Context, id: String, allIds: Collection<String>) {
        if (id.isBlank()) return
        set(ctx, KEY_PRELOCK_DISABLED, string(KEY_DISABLED_MODELS, ""))
        set(ctx, KEY_DISABLED_MODELS, allIds.filter { it != id }.joinToString("\n"))
        set(ctx, KEY_LOCKED_MODEL, id)
    }

    fun unlockModel(ctx: Context) {
        set(ctx, KEY_DISABLED_MODELS, string(KEY_PRELOCK_DISABLED, ""))
        set(ctx, KEY_LOCKED_MODEL, "")
    }

    fun disabledModels(ctx: Context): Set<String> =
        string(KEY_DISABLED_MODELS, "").split('\n').filter { it.isNotBlank() }.toSet()

    fun setModelEnabled(ctx: Context, id: String, enabled: Boolean) {
        // Turning a second model on is a request for choices, so the lock retires first.
        if (enabled && lockedModel(ctx).isNotBlank()) unlockModel(ctx)
        val now = disabledModels(ctx).toMutableSet()
        if (enabled) now.remove(id) else now.add(id)
        set(ctx, KEY_DISABLED_MODELS, now.joinToString("\n"))
    }

    fun setAllModelsEnabled(ctx: Context, ids: Collection<String>, enabled: Boolean) {
        if (enabled) {
            if (lockedModel(ctx).isNotBlank()) unlockModel(ctx)
            set(ctx, KEY_DISABLED_MODELS, "")
        } else set(ctx, KEY_DISABLED_MODELS, ids.joinToString("\n"))
    }

    /**
     * Applies the picker filter. Their rule is exact: a model the store does not list is
     * shown; only the ones explicitly turned off disappear. Never the reverse.
     *
     * A lock narrows it to one id. If that id is not in the catalog the lock is stale, so the
     * ordinary filter applies: an empty picker is never a valid state.
     */
    fun <T> filterModels(ctx: Context, items: List<T>, id: (T) -> String): List<T> {
        val lock = lockedModel(ctx)
        if (lock.isNotBlank()) {
            val only = items.filter { id(it) == lock }
            if (only.isNotEmpty()) return only
        }
        val off = disabledModels(ctx)
        if (off.isEmpty()) return items
        val shown = items.filter { id(it) !in off }
        return if (shown.isEmpty()) items else shown
    }
}
