// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.encoding

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import io.motohub.android.session.ProjectionEventLog
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Captures the projected display as JPEG stills instead of an H.264 stream.
 *
 * This exists for exactly one dash family. The Moto Morini X-Cape 1200's OEM app, Ride MO 1.0.23,
 * **never streams H.264**: `ParamSettings.deviceStreamType` is initialised to `Image`, its only
 * setter has zero call sites in the APK, and `createDisplayAndLiveAdapter` therefore always takes
 * the image branch. Its H.264 classes are complete and maintained but unreachable. Two independent
 * implementations - this one and the public reference - spent weeks tuning an H.264 stream against
 * those dead classes, and both ended at the same wall: the dash acknowledges every frame and paints
 * none of them. Acknowledgement is a transport-layer counter keyed on the frame index, so it says
 * nothing about whether the payload was understood.
 *
 * Every parameter here is read from that app rather than guessed: RGBA_8888 into an ImageReader of
 * two buffers, the full frame compressed at JPEG quality 60, one frame on the wire every 100 ms.
 *
 * **Capture and send are deliberately decoupled**, which is the shape `GoogleImageReaderLiveThread`
 * uses and the shape this class had to be rewritten into. That thread compresses *every* image the
 * VirtualDisplay posts into a single latest-wins slot (`imgBuf`, guarded by `dataUseSem`) and lets
 * a 100 ms `Timer` release a semaphore that sends whatever is in the slot. Pacing the capture
 * instead - which is what this class did first - looks equivalent and is not: it caps production at
 * exactly the send rate, so any frame lost to a busy socket or a full send window is a frame that
 * is never made up, and the dash receives at an irregular rate rather than a slow one. Irregular is
 * what a rider sees as judder, and turn-by-turn guidance is where it is least tolerable.
 *
 * **Nothing else in the app uses this.** It is selected only by a profile that opts in, and the
 * H.264 path it sits beside is untouched - a dash that streams today cannot reach this code.
 */
class JpegDisplaySource(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    /**
     * Returning false means the transport refused the frame; the source drops it rather than
     * queueing, exactly as the encoder path does, because a still is only worth sending while it
     * is still current.
     */
    private val onFrame: (jpeg: ByteArray, frameId: Int) -> Boolean,
    private val onFailure: (Throwable) -> Unit
) {

    private var imageReader: ImageReader? = null
    private var readerThread: HandlerThread? = null
    private var sender: ScheduledExecutorService? = null
    private val running = AtomicBoolean(false)
    private val frameId = AtomicInteger(0)

    /** The most recently compressed still, waiting for the next send tick. Latest wins. */
    private val latest = AtomicReference<ByteArray?>(null)

    // Reused across frames. Compressing a 1024x464 frame used to allocate three ARGB_8888 bitmaps
    // of ~1.9 MB each and copy the pixels through all of them; two of those copies existed only to
    // crop the reader's row padding and to apply a scale that is now the identity.
    private var padded: Bitmap? = null
    private var cropped: Bitmap? = null
    private var croppedCanvas: Canvas? = null
    private val jpegBuffer = ByteArrayOutputStream(DEFAULT_JPEG_BUFFER_BYTES)
    /** Read by the capture thread, written by the send thread. */
    @Volatile private var quality = JpegQualityLadder.QUALITY_LADDER.first()

    /** Decides that quality, one window at a time. Everything about *when* stays here. */
    private val ladder = JpegQualityLadder()
    private var adaptAtMillis = 0L
    private var adaptAccepted = 0

    /** Stills handed to the transport in this window, refused ones included. Zero means the
     *  window measured the phone, not the dashboard - see [JpegQualityLadder]. */
    private var adaptOffered = 0
    private var compressedAtMillis = 0L
    private var periodMillis = 100L

    /**
     * How often to compress, tracking the rate the transport actually accepts.
     *
     * Written by the send thread, read by the capture thread, like [quality]. Compressing at the
     * tick rate when the dash takes a fifth of it is pure heat: a 1024x464 ARGB frame costs about
     * 125ms of CPU here, and on the 75-minute ride of 2026-08-22 four of every five of those were
     * overwritten before anyone could send them, while Android Auto's own decoder fell from 29fps
     * to 14 underneath it.
     */
    @Volatile private var captureIntervalMillis = 100L
    private var acceptedAtMillis = 0L
    private var acceptInterval = 0.0
    private val cropSource = Rect()
    private val cropDestination = Rect()

    // Counters for the periodic throughput line. Only ever touched from the capture thread and the
    // single sender thread respectively, except `bytesSent`, which the sender alone accumulates.
    private val captured = AtomicInteger(0)
    private var sent = 0
    private var refused = 0
    private var idle = 0
    private var repeated = 0
    private var bytesSent = 0L
    private var lastSent: ByteArray? = null
    private var statsAtMillis = 0L

    /** The surface a VirtualDisplay should render into. Null until [start]. */
    var surface: Surface? = null
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val thread = HandlerThread("motohub-jpeg-capture").also { it.start() }
        readerThread = thread
        // Two buffers, matching the OEM. One would stall the producer while a frame compresses;
        // more would only add latency, because a still older than the next one is worthless.
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ onImageAvailable(it) }, Handler(thread.looper))
        imageReader = reader
        surface = reader.surface

        periodMillis = (1000L / frameRate.coerceAtLeast(1)).coerceAtLeast(1L)
        captureIntervalMillis = periodMillis
        val now = SystemClock.elapsedRealtime()
        statsAtMillis = now
        adaptAtMillis = now
        sender = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "motohub-jpeg-send")
        }.also {
            it.scheduleAtFixedRate({ tick() }, periodMillis, periodMillis, TimeUnit.MILLISECONDS)
        }

        ProjectionEventLog.record(
            "JPEG",
            "Capturing ${width}x$height as JPEG stills, quality $quality (adaptive), " +
                "one frame on the wire every ${periodMillis}ms (${frameRate}fps)."
        )
    }

    /**
     * Compresses every frame the display posts, keeping only the newest.
     *
     * There is no rate limit here on purpose - see the note on the class. The single capture thread
     * limits itself: the next image cannot be picked up until this one has finished compressing, so
     * the loop settles at whatever rate the phone can actually sustain, and the send tick always
     * finds the freshest picture that rate allowed.
     */
    private fun onImageAvailable(reader: ImageReader) {
        if (!running.get()) {
            runCatching { reader.acquireLatestImage()?.close() }
            return
        }
        // Compressing faster than the dash drinks is pure heat: the extra frames are overwritten
        // before anyone can send them. [captureIntervalMillis] tracks the accepted rate at roughly
        // twice its speed, which keeps a picture no older than half a send slot ready for the
        // moment one opens - that staleness is what the delay the rider notices is made of - while
        // leaving the CPU for Android Auto's decoder, which shares it.
        val now = SystemClock.elapsedRealtime()
        if (now - compressedAtMillis < captureIntervalMillis) {
            runCatching { reader.acquireLatestImage()?.close() }
            return
        }
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        compressedAtMillis = now
        try {
            val plane = image.planes.firstOrNull() ?: return
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            // The reader hands back rows padded to its own stride, so the backing bitmap has to be
            // wide enough to hold the padding and is cropped afterwards. Skipping this shears the
            // picture diagonally, which would look exactly like a codec fault.
            val paddedWidth = (if (pixelStride > 0) rowStride / pixelStride else width)
                .coerceAtLeast(width)

            val source = paddedBitmap(paddedWidth).also { it.copyPixelsFromBuffer(plane.buffer) }
            // Only pay for the crop when the reader actually padded the rows. At 1024 pixels of
            // RGBA the stride is usually already exact, and then the padded bitmap *is* the frame.
            val frame = if (paddedWidth == width) source else croppedBitmap().also { destination ->
                cropSource.set(0, 0, width, height)
                cropDestination.set(0, 0, width, height)
                croppedCanvas(destination).drawBitmap(source, cropSource, cropDestination, null)
            }

            jpegBuffer.reset()
            frame.compress(Bitmap.CompressFormat.JPEG, quality, jpegBuffer)
            latest.set(jpegBuffer.toByteArray())
            captured.incrementAndGet()
        } catch (failure: Throwable) {
            if (running.get()) onFailure(failure)
        } finally {
            runCatching { image.close() }
        }
    }

    /** Sends whatever the capture thread last produced, on a fixed cadence. */
    private fun tick() {
        if (!running.get()) return
        try {
            val jpeg = latest.get()
            when {
                jpeg == null -> idle++
                // Already on the wire. Re-sending it spends the dash's byte budget on a picture it
                // is already showing, and that budget is the whole of what stands between the
                // rider and a smooth image.
                jpeg === lastSent -> repeated++
                // The id only advances for a frame the transport actually took. The dash echoes
                // the last id it processed and our own flow control is keyed on that number, so
                // numbering frames that were never sent throws the two out of step - it was
                // running six ids ahead of reality in the field log of 2026-08-21.
                onFrame(jpeg, frameId.get()) -> {
                    adaptOffered++
                    lastSent = jpeg
                    frameId.incrementAndGet()
                    sent++
                    bytesSent += jpeg.size
                    recordAccepted()
                }
                else -> {
                    adaptOffered++
                    refused++
                }
            }
            adaptQuality()
            reportThroughput()
        } catch (failure: Throwable) {
            if (running.get()) onFailure(failure)
        }
    }

    /** Notes that a still reached the transport, and paces the capture thread off that rhythm. */
    private fun recordAccepted() {
        adaptAccepted++
        val now = SystemClock.elapsedRealtime()
        val previous = acceptedAtMillis
        acceptedAtMillis = now
        if (previous == 0L) return
        val gap = (now - previous).toDouble()
        acceptInterval =
            if (acceptInterval == 0.0) gap else acceptInterval + (gap - acceptInterval) * ACCEPT_SMOOTHING
        captureIntervalMillis = (acceptInterval * CAPTURE_AHEAD).toLong()
            .coerceIn(periodMillis, MAX_CAPTURE_INTERVAL_MS)
    }

    /**
     * Walks the JPEG quality down until the dash can keep up, and back up when it can.
     *
     * The X-Cape's link is not the constraint - the field log of 2026-08-21 measured 5GHz at
     * -33dBm and 39Mbps while we were managing 125 KB/s, about a fortieth of it. The dash itself
     * accepts roughly 130 KB/s no matter how that is divided up: at half resolution it took 5.5
     * stills a second of ~22 KB, at full resolution 1.7 a second of ~78 KB. Bytes are the budget,
     * so bytes are what this spends - and quality is the only way to spend fewer of them without
     * shrinking the picture, which this dash cannot do anything with. It blits a still at its
     * natural size and leaves the rest of the panel black, which is what the "cut off on the
     * right" of the half-resolution build actually was.
     */
    private fun adaptQuality() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - adaptAtMillis
        if (elapsed < ADAPT_INTERVAL_MS) return
        val offered = adaptOffered
        val accepted = adaptAccepted
        adaptAtMillis = now
        adaptAccepted = 0
        adaptOffered = 0
        // Stills a second, not a share of the offers made. The two used to be the same number
        // divided by the tick rate, and stopped being so the moment the capture thread started
        // pacing itself off the accepted rate: a ratio would then have measured this class against
        // its own throttle and walked the quality to the floor by construction.
        val fps = accepted * 1000.0 / elapsed
        when (val outcome = ladder.onWindow(offered, accepted, elapsed)) {
            is JpegQualityLadder.Outcome.IdleHold -> if (outcome.first) {
                ProjectionEventLog.record(
                    "JPEG",
                    "Nothing to send to the dashboard; holding the still quality at $quality " +
                        "instead of reading an idle wire as congestion."
                )
            }

            is JpegQualityLadder.Outcome.Changed -> {
                quality = outcome.quality
                ProjectionEventLog.record(
                    "JPEG",
                    if (outcome.probe) {
                        "Still quality now $quality, trying a finer rung (the dashboard was " +
                            "taking %.1f stills a second and keeping up)."
                    } else {
                        "Still quality now $quality (the dashboard was taking %.1f stills a second)."
                    }.format(fps)
                )
            }

            is JpegQualityLadder.Outcome.Reverted -> {
                quality = outcome.quality
                ProjectionEventLog.record(
                    "JPEG",
                    ("Still quality back to $quality: the coarser rung bought no frames " +
                        "(still %.1f stills a second), so this dashboard is pacing frames, " +
                        "not bytes; holding the quality instead.").format(fps)
                )
            }

            JpegQualityLadder.Outcome.Unchanged -> Unit
        }
    }

    /**
     * One line every [STATS_INTERVAL_MS], because the field question this path keeps raising is
     * "why is it not smooth" and the answer is always one of three numbers: frames the phone could
     * compress, frames the transport would take, and bytes per second on the wire. Guessing between
     * them has already cost several test rides.
     */
    private fun reportThroughput() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - statsAtMillis
        if (elapsed < STATS_INTERVAL_MS) return
        val seconds = elapsed / 1000.0
        val averageKb = if (sent > 0) bytesSent / sent / 1024.0 else 0.0
        ProjectionEventLog.debug(
            "JPEG",
            "%.1f fps out of the phone, %.1f fps on the wire, %.0f KB average, %.0f KB/s"
                .format(captured.getAndSet(0) / seconds, sent / seconds, averageKb,
                    bytesSent / 1024.0 / seconds) +
                " at quality $quality, compressing every ${captureIntervalMillis}ms" +
                " ($refused held back, ${idle + repeated} ticks with nothing new)"
        )
        sent = 0
        refused = 0
        idle = 0
        repeated = 0
        bytesSent = 0
        statsAtMillis = now
    }

    private fun paddedBitmap(paddedWidth: Int): Bitmap {
        val existing = padded
        if (existing != null && !existing.isRecycled &&
            existing.width == paddedWidth && existing.height == height
        ) {
            return existing
        }
        existing?.recycle()
        return Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888).also { padded = it }
    }

    private fun croppedBitmap(): Bitmap {
        val existing = cropped
        if (existing != null && !existing.isRecycled) return existing
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            cropped = it
            croppedCanvas = null
        }
    }

    private fun croppedCanvas(destination: Bitmap): Canvas =
        croppedCanvas ?: Canvas(destination).also { croppedCanvas = it }

    /** Resets the frame counter, for a dash that just told us it re-entered its map view. */
    fun resetFrameId() {
        frameId.set(0)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { sender?.shutdownNow() }
        sender = null
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null
        surface = null
        runCatching { readerThread?.quitSafely() }
        readerThread = null
        latest.set(null)
        lastSent = null
        padded?.recycle()
        padded = null
        cropped?.recycle()
        cropped = null
        croppedCanvas = null
        ProjectionEventLog.record("JPEG", "JPEG capture stopped after ${frameId.get()} frames.")
    }

    private companion object {
        const val ADAPT_INTERVAL_MS = 2_000L

        /** How much of the accepted interval to wait before compressing again. */
        const val CAPTURE_AHEAD = 0.5

        /** Weight of the newest gap in the accepted-interval average. */
        const val ACCEPT_SMOOTHING = 0.3

        /** However slow the dash gets, never let the ready picture age past this. */
        const val MAX_CAPTURE_INTERVAL_MS = 500L

        /** Roughly one full-size map frame at [JPEG_QUALITY]; the stream grows itself if wrong. */
        const val DEFAULT_JPEG_BUFFER_BYTES = 128 * 1024

        const val STATS_INTERVAL_MS = 5_000L

    }
}
