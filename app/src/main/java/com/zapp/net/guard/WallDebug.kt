package com.zapp.net.guard

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Debug-only seam between the outside handle (`VpnRelay`, adb) and the live view model.
 *
 * Why it exists: the production wall arrives on the server's `/api/event` stream and there is
 * no way to make the provider emit a real `provider.free-limit` without burning the account's
 * whole free allowance. Every other part of the chain — parse, classify, rotate, restore — is
 * production code that simply needs a frame shaped like the server's.
 *
 * Nothing here changes behaviour: `onQuotaWall` runs identically for a frame from SSE and a
 * frame from this flow. Emission is gated on a debuggable build at the relay.
 */
object WallDebug {

    private val _frames = MutableSharedFlow<QuotaWall.Frame>(extraBufferCapacity = 4)

    /** Consumed by the view model only; never written by production paths. */
    val frames: SharedFlow<QuotaWall.Frame> = _frames

    fun emit(frame: QuotaWall.Frame): Boolean = _frames.tryEmit(frame)
}
