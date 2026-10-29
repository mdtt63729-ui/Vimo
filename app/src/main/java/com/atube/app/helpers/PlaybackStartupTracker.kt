package com.atube.app.helpers

import android.util.Log

/**
 * Instruments tap-to-first-frame playback startup latency.
 *
 * Records the phases of video startup:
 *   T0 = user tap
 *   T1 = stream resolution start
 *   T2 = playable source selected
 *   T3 = player prepare
 *   T4 = first rendered frame
 *
 * Primary metric: tapToFirstFrame = T4 - T0
 *
 * This is a debug-only instrument; it logs to Logcat and does not
 * affect playback behaviour. Call [begin] on tap, then mark each
 * phase as it completes.
 */
object PlaybackStartupTracker {

    private const val TAG = "PlaybackStartup"

    private var tapTimeMs: Long = 0L
    private var resolveStartMs: Long = 0L
    private var sourceSelectedMs: Long = 0L
    private var prepareStartMs: Long = 0L
    private var firstFrameMs: Long = 0L

    private var videoId: String? = null
    private var provider: String = ""
    private var streamType: String = ""
    private var initialQuality: String = ""
    private var cacheHit: Boolean = false
    private var networkType: String = ""

    /** Begin tracking a new playback startup. Call on user tap. */
    fun begin(videoId: String) {
        this.videoId = videoId
        tapTimeMs = System.currentTimeMillis()
        resolveStartMs = 0L
        sourceSelectedMs = 0L
        prepareStartMs = 0L
        firstFrameMs = 0L
        provider = ""
        streamType = ""
        initialQuality = ""
        cacheHit = false
        networkType = ""
    }

    fun markResolveStart() {
        resolveStartMs = System.currentTimeMillis()
    }

    fun markSourceSelected(provider: String, streamType: String, cacheHit: Boolean) {
        sourceSelectedMs = System.currentTimeMillis()
        this.provider = provider
        this.streamType = streamType
        this.cacheHit = cacheHit
    }

    fun markPrepareStart(initialQuality: String) {
        prepareStartMs = System.currentTimeMillis()
        this.initialQuality = initialQuality
    }

    fun markFirstFrame() {
        firstFrameMs = System.currentTimeMillis()
        logReport()
    }

    fun setNetworkType(type: String) {
        networkType = type
    }

    private fun logReport() {
        if (tapTimeMs == 0L || firstFrameMs == 0L) return

        val tapToResolve = if (resolveStartMs > 0) resolveStartMs - tapTimeMs else -1L
        val resolveToPrepare = if (sourceSelectedMs > 0 && prepareStartMs > 0) prepareStartMs - sourceSelectedMs else -1L
        val prepareToFirstFrame = if (prepareStartMs > 0) firstFrameMs - prepareStartMs else -1L
        val totalStartup = firstFrameMs - tapTimeMs

        Log.i(TAG, "=== Playback Startup Report ===")
        Log.i(TAG, "  videoId:        $videoId")
        Log.i(TAG, "  provider:       $provider")
        Log.i(TAG, "  streamType:     $streamType")
        Log.i(TAG, "  initialQuality: $initialQuality")
        Log.i(TAG, "  cacheHit:       $cacheHit")
        Log.i(TAG, "  networkType:    $networkType")
        Log.i(TAG, "  tapToResolve:   ${tapToResolve}ms")
        Log.i(TAG, "  resolveToPrepare: ${resolveToPrepare}ms")
        Log.i(TAG, "  prepareToFirstFrame: ${prepareToFirstFrame}ms")
        Log.i(TAG, "  totalStartup:   ${totalStartup}ms")
        Log.i(TAG, "===============================")
    }

    /** Reset tracker state. */
    fun reset() {
        tapTimeMs = 0L
        resolveStartMs = 0L
        sourceSelectedMs = 0L
        prepareStartMs = 0L
        firstFrameMs = 0L
        videoId = null
    }
}
