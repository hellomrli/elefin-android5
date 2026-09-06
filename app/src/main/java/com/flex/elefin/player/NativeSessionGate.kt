package com.flex.elefin.player

/** Serializes access to a native singleton without letting old views address a new player. */
class NativeSessionGate {
    private val lock = Any()
    private var owner: Any? = null
    private var closing = false

    fun create(token: Any, action: () -> Unit) = synchronized(lock) {
        check(owner == null && !closing) { "A native player is already active" }
        action()
        owner = token
    }

    fun <T> call(token: Any, action: () -> T): T? = synchronized(lock) {
        if (owner !== token || closing) null else action()
    }

    fun destroy(token: Any, action: () -> Unit) {
        synchronized(lock) {
            if (owner !== token || closing) return
            // Waits for any call holding the lock, then rejects all subsequent calls.
            closing = true
        }
        try {
            // mpv joins its event thread here. Do not hold the lock while joining it:
            // event callbacks may attempt a (now rejected) property read.
            action()
        } finally {
            synchronized(lock) {
                owner = null
                closing = false
            }
        }
    }
}
