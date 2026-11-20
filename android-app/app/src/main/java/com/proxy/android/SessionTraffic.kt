package com.proxy.android

/** Owned by the VPN service. Times are from the monotonic elapsed-realtime clock. */
internal class SessionTraffic {
    data class Rate(val atMs: Long, val upload: Long, val download: Long)

    var startedAtMs: Long? = null
        private set
    private var previousAtMs: Long? = null
    private var previousUpload = 0L
    private var previousDownload = 0L
    private val history = ArrayDeque<Rate>()

    fun samplesAt(nowMs: Long): List<Rate> {
        val last = history.lastOrNull() ?: return emptyList()
        if (nowMs - last.atMs > MAX_INTERVAL_MS) return emptyList()
        return history.dropWhile { it.atMs < nowMs - WINDOW_MS }
    }

    fun start(nowMs: Long) {
        resetSamples()
        startedAtMs = nowMs
    }

    fun stop() {
        startedAtMs = null
        resetSamples()
    }

    private fun resetSamples() {
        previousAtMs = null
        history.clear()
    }

    fun record(nowMs: Long, uploaded: Long, downloaded: Long) {
        if (startedAtMs == null) return
        if (uploaded < 0 || downloaded < 0) {
            resetSamples()
            return
        }
        val previous = previousAtMs
        if (previous != null) {
            val elapsed = nowMs - previous
            if (elapsed <= 0) return
            if (elapsed > MAX_INTERVAL_MS || uploaded < previousUpload || downloaded < previousDownload) {
                history.clear()
            } else {
                history.addLast(Rate(nowMs, perSecond(uploaded - previousUpload, elapsed),
                    perSecond(downloaded - previousDownload, elapsed)))
                while (history.first().atMs < nowMs - WINDOW_MS) history.removeFirst()
            }
        }
        previousAtMs = nowMs
        previousUpload = uploaded
        previousDownload = downloaded
    }

    private fun perSecond(bytes: Long, elapsedMs: Long) = (bytes * 1000.0 / elapsedMs).toLong()

    companion object {
        const val WINDOW_MS = 30_000L
        // A suspended device or a missing native sample leaves no instantaneous rate.
        private const val MAX_INTERVAL_MS = 3_500L
    }
}
