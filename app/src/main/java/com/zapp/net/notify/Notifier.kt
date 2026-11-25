package com.zapp.net.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * The phone half of `shell/notifications/notification.tsx`.
 *
 * Their four triggers (`:809-867`) map to two channels here:
 *
 *  - `session.execution.succeeded` -> response ready      (`:619-667`)
 *  - `session.execution.failed`    -> session error       (`:673-717`)
 *  - `permission.asked`            -> permission required (`:823-831`)
 *  - `form.created` with kind `question` / `mcp-elicitation` / `websearch.provider`
 *                                  -> question            (`:835-851`)
 *
 * `platform.notify(title, body, onClick)` is a desktop banner with a click target; a
 * `NotificationCompat` post whose `PendingIntent` carries `session` is the same thing on
 * Android. Their per-session mute (`input.muted`) is `SessionPrefs.mutedFor`.
 *
 * One notification per session, keyed by tag, so a second event replaces the first instead of
 * stacking, and opening the session clears it (`cancelFor`).
 */
object Notifier {

    const val CH_AGENT = "zapp_agent"
    const val CH_REQUEST = "zapp_request"

    /** Notification ids are per tag, so one constant is enough. */
    private const val ID = 4101

    fun ensure(ctx: Context) {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(CH_AGENT, "Agent replies", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A session finished or failed" },
        )
        mgr.createNotificationChannel(
            NotificationChannel(CH_REQUEST, "Permissions & questions", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "The agent is blocked and needs an answer" },
        )
    }

    private fun tag(sessionId: String) = "session:$sessionId"

    /**
     * `platform.notify(title, body, onClick)`. `sound=false` keeps the banner and drops the
     * chime, which is their `settings.sounds.*Enabled` switch — the two are independent
     * (`notification.tsx:645` vs `:653`).
     */
    fun post(
        ctx: Context,
        sessionId: String,
        title: String,
        body: String,
        channel: String,
        sound: Boolean,
        soundId: String? = null,
    ) {
        ensure(ctx)
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        // Their chime is their own audio file, not the OS tone (`coordinator.sound(...)`), so when
        // the picker names a file we play it and keep the banner silent - otherwise the user hears
        // the system tone *and* the file.
        val hasFile = sound && SoundAssets.res(soundId) != null
        val open = Intent(ctx, com.zapp.net.MainActivity::class.java)
            .putExtra("session", sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            ctx,
            sessionId.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setSilent(!sound || hasFile)
            .build()
        runCatching { mgr.notify(tag(sessionId), ID, n) }
        // After the banner, so the two land together.
        if (hasFile) SoundPlayer.playOnce(ctx, soundId)
    }

    /** Opening a session clears whatever it left in the shade. */
    fun cancelFor(ctx: Context, sessionId: String) {
        runCatching {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(tag(sessionId), ID)
        }
    }

    /**
     * The sound half on its own.
     *
     * Desktop keeps them apart: `sounds.agentEnabled` decides the chime (`notification.tsx:645`)
     * and `notifications.agent` decides the banner (`:653`). With the banner off and the chime
     * on, a notification would be the wrong instrument — it always leaves a row in the shade —
     * so the tone is played directly instead.
     */
    fun chime(ctx: Context, soundId: String? = null) {
        // The picked file first - that is what desktop plays. The OS tone is only the fallback for
        // an id we have no asset for, so a bare tone is never silent by accident.
        if (SoundPlayer.playOnce(ctx, soundId)) return
        runCatching {
            val uri = android.media.RingtoneManager.getDefaultUri(
                android.media.RingtoneManager.TYPE_NOTIFICATION,
            )
            android.media.RingtoneManager.getRingtone(ctx.applicationContext, uri)
                ?.play()
        }
    }
}
