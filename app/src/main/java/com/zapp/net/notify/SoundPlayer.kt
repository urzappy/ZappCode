package com.zapp.net.notify

import android.content.Context
import android.media.MediaPlayer
import android.util.Log

/**
 * The phone's `new Audio(url)` - their `oQt` in `main-CLeW75FU.js` @ 1 599 417.
 *
 * Theirs:
 *
 *     let t = new Audio(e); t.play().catch(() => void 0)
 *     return () => { t.pause(); t.currentTime = 0 }
 *
 * So a failed `play()` is swallowed and the stop function pauses and rewinds. Here a decode
 * failure is reported instead of swallowed, because it is the only way the preview's 5 s load
 * leash ever settles a broken file - [play] returns null when there is nothing to play.
 *
 * `MediaPlayer.create` prepares synchronously. These files are 1.5-12 KB of AAC, so the cost is a
 * frame or two; doing it off-thread would only add a hop between the debounce and the sound.
 */
object SoundPlayer {

    /** What their stop closure does: pause, rewind, release. */
    fun interface Playback {
        fun stop()
    }

    /** Returns the stop function, or null when [id] has no file (their `"none"`, or unknown). */
    fun play(ctx: Context, id: String?): Playback? {
        val res = SoundAssets.res(id) ?: return null
        val mp = MediaPlayer.create(ctx.applicationContext, res) ?: run {
            Log.w("ZAPP", "sound: MediaPlayer.create returned null for $id")
            return null
        }
        mp.setOnCompletionListener { done -> runCatching { done.release() } }
        runCatching { mp.start() }.onFailure {
            Log.w("ZAPP", "sound: start failed for $id", it)
            runCatching { mp.release() }
            return null
        }
        return Playback {
            // Their stop: pause, then rewind. A player already released by its completion listener
            // is not an error.
            runCatching { mp.pause() }
            runCatching { mp.seekTo(0) }
            runCatching { mp.release() }
        }
    }

    /**
     * Fire and forget - the notification path, where nothing ever calls stop.
     *
     * Their `coordinator.sound(...)` is the same shape: play it, do not hold it.
     */
    fun playOnce(ctx: Context, id: String?): Boolean = play(ctx, id) != null
}
