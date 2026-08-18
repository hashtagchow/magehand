package com.hashtagchow.magehand.core.model

/**
 * Single source of truth for connectivity, per docs/design/06-offline-and-sync.md.
 *
 * Who sets what:
 * - [CONNECTING], [LIVE] and [AUTH_FAILED] are driven by `:core:ddp`'s `DdpClient`
 *   (socket + handshake + resume login).
 * - [OFFLINE] is a *policy* state owned by `:core:data`: no network at all, or the
 *   reconnect loop has been retrying long enough that the UI should fall back to the
 *   Room snapshot. `DdpClient` never emits it — from the socket's point of view
 *   "no network" and "server not answering" are the same CONNECTING loop.
 */
enum class ConnectionState {
    /** Socket down or handshake/login in flight; retrying with backoff. */
    CONNECTING,

    /** Socket up, DDP handshake done, resume login accepted. */
    LIVE,

    /** No network / retries exhausted-for-now — render the cached snapshot. */
    OFFLINE,

    /** The resume token was rejected; re-login is required for this account. */
    AUTH_FAILED,
}
