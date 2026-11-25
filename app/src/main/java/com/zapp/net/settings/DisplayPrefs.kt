package com.zapp.net.settings

import android.content.Context
import com.zapp.net.ui.TimelineDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * PORT of the settings in `settings/model.tsx` that ZAPP can actually honour.
 *
 * Their defaults, verbatim (`settings/model.tsx:303-308`, `:316`):
 *
 * ```
 * timelineDetail:      { ...timelinePresets[2].value }   // "compact"
 * showTurnDiffs:       false
 * backgroundActivity:  "left"
 * timelineWidth:       "comfortable"
 * foldUserMessages:    false
 * permissions:         { autoApprove: false, autoApproveProjects: {} }
 * ```
 *
 * `backgroundActivity` and `timelineWidth` are desktop *placement* settings: the phone has one
 * pane, so "left" and "comfortable" are the only possible answers and a control for them would
 * be a lie. That is a disclosed deviation, not a missing feature.
 *
 * **`foldUserMessages` defaults to false**, which matters: the fold drew on every long message
 * before this file existed. Their rule is that the fold is a preference, off until asked for.
 *
 * ## The custom timeline detail
 *
 * `settings/model.tsx:255` stores `timelineDetail` as a whole `Detail` object, not a preset id,
 * and `timelinePreset(value)` reads the id back by comparison - so a hand-edited value that
 * happens to equal a preset reports that preset, and anything else reports `custom`. That is
 * reproduced exactly: [setDetail] stores the id, [setCustom] stores the encoded object, and
 * [detailId] is always derived from the value rather than remembered.
 */
object DisplayPrefs {

    private const val FILE = "zapp_display"

    const val KEY_TIMELINE_DETAIL = "timeline_detail"
    const val KEY_TIMELINE_CUSTOM = "timeline_custom"
    const val KEY_TURN_DIFFS = "show_turn_diffs"
    const val KEY_FOLD_USER = "fold_user_messages"
    const val KEY_AUTO_APPROVE = "auto_approve_permissions"

    /** `defaultSettings.general.timelineDetail` - the third preset, `compact`. */
    const val DEFAULT_DETAIL = "compact"

    /** `timelinePreset(value)` returned nothing. */
    const val CUSTOM_DETAIL = "custom"

    private val _detail = MutableStateFlow(TimelineDetail.default)
    val detail: StateFlow<TimelineDetail.Detail> = _detail

    private val _detailId = MutableStateFlow(DEFAULT_DETAIL)

    /** The preset id the current value still equals, or `custom` when it matches none. */
    val detailId: StateFlow<String> = _detailId

    private val _turnDiffs = MutableStateFlow(false)
    val turnDiffs: StateFlow<Boolean> = _turnDiffs

    private val _foldUser = MutableStateFlow(false)
    val foldUser: StateFlow<Boolean> = _foldUser

    /** `defaultSettings.permissions.autoApprove` - `false`. */
    private val _autoApprove = MutableStateFlow(false)
    val autoApprove: StateFlow<Boolean> = _autoApprove

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = sp(ctx)
        // A hand-edited detail outranks a preset id: whichever was written last wins, and
        // `setDetail` deletes the custom key so the two can never disagree.
        val custom = p.getString(KEY_TIMELINE_CUSTOM, null)?.let { TimelineDetail.decode(it) }
        if (custom != null) apply(custom) else applyPreset(p.getString(KEY_TIMELINE_DETAIL, DEFAULT_DETAIL) ?: DEFAULT_DETAIL)
        _turnDiffs.value = p.getBoolean(KEY_TURN_DIFFS, false)
        _foldUser.value = p.getBoolean(KEY_FOLD_USER, false)
        _autoApprove.value = p.getBoolean(KEY_AUTO_APPROVE, false)
    }

    /** Sets the value and derives its id - never trusts a remembered id. */
    private fun apply(value: TimelineDetail.Detail) {
        _detail.value = value
        _detailId.value = TimelineDetail.presetId(value) ?: CUSTOM_DETAIL
    }

    private fun applyPreset(id: String) {
        val resolved = TimelineDetail.presets.firstOrNull { it.first == id }?.second
            ?: TimelineDetail.default
        apply(resolved)
    }

    /** Applies a preset id. An id this build does not know falls back to the shipped default. */
    fun setDetail(ctx: Context, id: String, persist: Boolean = true) {
        applyPreset(id)
        if (!persist) return
        sp(ctx).edit()
            .putString(KEY_TIMELINE_DETAIL, id)
            .remove(KEY_TIMELINE_CUSTOM)
            .apply()
    }

    /** Applies a hand-edited detail - one category moved by the advanced editor. */
    fun setCustom(ctx: Context, value: TimelineDetail.Detail) {
        apply(value)
        sp(ctx).edit()
            .putString(KEY_TIMELINE_CUSTOM, TimelineDetail.encode(value))
            .apply()
    }

    /** Clears the hand-edited value and returns to the shipped preset. */
    fun resetDetail(ctx: Context) {
        sp(ctx).edit().remove(KEY_TIMELINE_CUSTOM).apply()
        setDetail(ctx, DEFAULT_DETAIL)
    }

    fun setTurnDiffs(ctx: Context, on: Boolean) {
        _turnDiffs.value = on
        sp(ctx).edit().putBoolean(KEY_TURN_DIFFS, on).apply()
    }

    fun setFoldUser(ctx: Context, on: Boolean) {
        _foldUser.value = on
        sp(ctx).edit().putBoolean(KEY_FOLD_USER, on).apply()
    }

    fun setAutoApprove(ctx: Context, on: Boolean) {
        _autoApprove.value = on
        sp(ctx).edit().putBoolean(KEY_AUTO_APPROVE, on).apply()
    }
}
