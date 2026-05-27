package com.aegis.app

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * DAY 3 — ConnectionLog
 *
 * Thread-safe circular buffer of the last MAX_ENTRIES DNS queries.
 * Written by LocalDnsServer (DNS thread pool), read by the EventChannel
 * sink on the main thread. Observers are notified on every new entry.
 */
object ConnectionLog {

    private const val MAX_ENTRIES = 500

    data class Entry(
        val domain: String,
        val blocked: Boolean,
        val timestampMs: Long = System.currentTimeMillis(),
    ) {
        // Serialise to a pipe-delimited string for the Flutter EventChannel.
        // Format: "domain|blocked|timestampMs"
        // Keep it simple — no JSON dependency needed.
        fun toWire(): String = "$domain|${if (blocked) "1" else "0"}|$timestampMs"
    }

    private val lock  = ReentrantReadWriteLock()
    private val buffer = ArrayDeque<Entry>(MAX_ENTRIES + 1)
    private var listener: ((Entry) -> Unit)? = null

    // ── Write side (called from DNS thread pool) ───────────────────────────────

    fun add(domain: String, blocked: Boolean) {
        val entry = Entry(domain, blocked)
        lock.write {
            buffer.addLast(entry)
            if (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        }
        listener?.invoke(entry)   // notify outside the write lock to avoid deadlock
    }

    // ── Read side (called from main / EventChannel thread) ────────────────────

    /** Returns a snapshot of all current entries, oldest first. */
    fun snapshot(): List<Entry> = lock.read { buffer.toList() }

    /** Register a listener that fires on every new entry. Only one at a time. */
    fun setListener(l: ((Entry) -> Unit)?) {
        listener = l
    }
}
