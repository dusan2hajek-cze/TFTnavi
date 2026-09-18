// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.androidauto

import android.graphics.SurfaceTexture
import io.motohub.android.feature.controls.HandlebarPressHud
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/** GPU compositor that fills the TFT while keeping the complete source in the phone preview. */
class AaCompositor(
    private val log: (String) -> Unit,
    private val displayMode: AndroidAutoDisplayMode,
    private val sourceGeometry: DisplayGeometry,
    touchSurface: DisplayGeometry = sourceGeometry,
    private var screenMargins: TBoxScreenMargins = TBoxScreenMargins.NONE,
    /**
     * Coded-frame pixels Android Auto was told not to draw into (see [AaAspectMargins]).
     *
     * They have to be cut out here as well, and this is the whole reason black bands survived
     * every resolution and display mode: advertising the margins only moves Android Auto's UI
     * into a smaller, CENTRED area of the coded frame, it does not shrink the frame. Sampling all
     * of it copied the rows Android Auto had left black straight onto the dashboard. Confirmed
     * against open-cfmoto, which crops the same way in its own shader and documents the split as
     * even (DHU: marginWidth 280 becomes 140 + 140).
     *
     * Independent of [screenMargins], which is motorcycle furniture on the canvas side and, when
     * it is advertised at all, is asymmetric - never split evenly. In practice only one of the two
     * is ever non-zero: AUTO aspect matching advertises no screen margins, MANUAL computes no
     * aspect margins.
     */
    private val contentMargins: AaAspectMargins = AaAspectMargins.NONE,
    /**
     * Whether the output target is something that can actually jam - the bike's video encoder,
     * whose input queue backs up when the transport behind it stops writing.
     *
     * False for the phone's own preview panel, which [setOutput] also drives. That target's
     * `eglSwapBuffers` blocks on the phone's vsync, so a healthy 30fps preview spends a real and
     * substantial part of every window "blocked" - reported as back pressure it would accuse an
     * encoder that is not there, and, worse, talk the decoder's stall watchdog out of a restart it
     * genuinely needed.
     */
    private val outputAppliesBackPressure: Boolean = true
) {
    private val thread = HandlerThread("aa-compositor").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderWindowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    /** The Surface [encoderWindowSurface] was created against, so a resize can keep it. */
    private var attachedOutputSurface: Surface? = null
    private var previewWindowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    /** The Surface [previewWindowSurface] was created against, so a resize can keep it. */
    private var attachedPreviewSurface: Surface? = null

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var uCropMatrix = 0
    private var textureId = 0

    // The press banner: a second, much simpler program - a plain 2D texture with alpha, over the
    // video. Separate from the one above because that one samples an external OES texture and
    // carries the crop matrices; nothing here wants either.
    private var bannerProgram = 0
    private var bannerPosition = 0
    private var bannerTexCoord = 0
    private var bannerSampler = 0
    private var bannerTextureId = 0
    /** The press currently uploaded, so a second on screen costs one upload rather than thirty. */
    private var bannerUploaded: HandlebarPressHud.Press? = null
    private lateinit var surfaceTexture: SurfaceTexture

    @Volatile
    var inputSurface: Surface? = null
        private set

    @Volatile private var canvasW = 0
    @Volatile private var canvasH = 0
    @Volatile private var srcW = 0
    @Volatile private var srcH = 0
    @Volatile private var previewCanvasW = 0
    @Volatile private var previewCanvasH = 0
    @Volatile private var previewVpX = 0
    @Volatile private var previewVpY = 0
    @Volatile private var previewVpW = 0
    @Volatile private var previewVpH = 0
    @Volatile private var touchUiW = touchSurface.width
    @Volatile private var touchUiH = touchSurface.height
    @Volatile private var tftClipLeft = 0
    @Volatile private var tftClipTop = 0
    @Volatile private var tftClipW = 0
    @Volatile private var tftClipH = 0
    @Volatile private var tftViewport: PreviewViewport? = null

    private val texMatrix = FloatArray(16)
    private val tftMatrix = FloatArray(16)
    private val previewMatrix = FloatArray(16)
    @Volatile private var hasContent = false
    @Volatile private var pendingFrame = false
    private var lastDrawMs = 0L
    @Volatile private var frameCap = DEFAULT_FRAME_CAP
    @Volatile private var lastSourceFrameNanos = 0L

    /**
     * Cumulative milliseconds this compositor has spent inside `eglSwapBuffers` on the encoder
     * target, and the timestamp of the swap currently in flight.
     *
     * This is the number that tells a broken decoder from a jammed pipe, and not having it is why
     * rider 4d8a4c5b's 2fps collapse (2026-08-26) could not be diagnosed from his log at all. The
     * encoder's input surface is a bounded buffer queue: when the encoder stops draining - because
     * the transport write behind it is not moving - `eglSwapBuffers` blocks here, this thread stops
     * calling `updateTexImage`, the decoder runs out of output buffers, and it reports exactly what
     * a dead decoder reports, "input flowing, no output". Restarting the decoder then fixes
     * nothing, which is what his log shows seven times over.
     *
     * Monotonic on purpose: readers take deltas across a window they choose themselves
     * ([downstreamBlockedMillis]), so no window has to be agreed on here.
     */
    @Volatile private var swapBlockedMs = 0L
    @Volatile private var swapInFlightSinceMs = 0L

    // One line per window, not per frame: at 30fps a per-frame counter is 1800 entries a minute in
    // a ring that holds 1500 (see ProjectionEventLog's repeat folding for how that ends).
    private val statsWindowMs = 30_000L
    private var statsWindowStartedMs = 0L
    private var statsWindowBlockedMs = 0L
    private var framesIn = 0
    private var framesDrawn = 0
    private var framesCoalesced = 0
    private var keepAliveRedraws = 0
    private var worstSwapMs = 0L

    // The decoder may keep producing frames while Android Auto shows a static screen. Coalesce
    // those frames and use a slow redraw only as a transport keep-alive.
    private val keepAliveTickMs = 150L
    private val idleRedrawMs = 2_000L

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f
                )
            )
            position(0)
        }

    /** True once EGL/GL initialized and [inputSurface] is ready; false leaves the compositor unusable. */
    fun start(): Boolean {
        val latch = CountDownLatch(1)
        var initialized = false
        handler.post {
            try {
                initEgl()
                initGl()
                surfaceTexture = SurfaceTexture(textureId).apply {
                    setDefaultBufferSize(
                        sourceGeometry.width,
                        sourceGeometry.height
                    )
                    setOnFrameAvailableListener({ handler.post(::onFrame) }, handler)
                }
                inputSurface = Surface(surfaceTexture)
                handler.postDelayed(keepAlive, keepAliveTickMs)
                log(
                    "[COMPOSITOR] ready source=${sourceGeometry.width}x${sourceGeometry.height}"
                )
                initialized = true
            } catch (failure: Throwable) {
                log("[COMPOSITOR] init failed: $failure")
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        return initialized
    }

    fun setOutput(encoderSurface: Surface, cw: Int, ch: Int, sw: Int, sh: Int) {
        handler.post {
            try {
                // In phone-only mode this entry point IS the preview panel
                // (PhoneOnlyAndroidAutoBridge.attachPreview routes here), so it gets that panel's
                // open/close animation - which is why it needs the same guard [setPreview] does.
                val attaching = mustAttachWindowSurface(
                    encoderSurface,
                    attachedOutputSurface,
                    encoderWindowSurface
                )
                if (attaching) {
                    encoderWindowSurface =
                        replaceWindowSurface(encoderWindowSurface, encoderSurface, "encoder")
                    attachedOutputSurface =
                        encoderSurface.takeIf { encoderWindowSurface != EGL14.EGL_NO_SURFACE }
                    // A session does not inherit the previous one's counters.
                    resetStatsWindow(android.os.SystemClock.uptimeMillis())
                }
                val resized = cw != canvasW || ch != canvasH || sw != srcW || sh != srcH
                canvasW = cw
                canvasH = ch
                srcW = sw
                srcH = sh
                configureTftViewport()
                if (attaching || resized) {
                    log(
                        "[COMPOSITOR] TFT=${cw}x$ch source=${sw}x$sh mode=$displayMode " +
                            "viewport=${tftViewport?.width}x${tftViewport?.height} " +
                            "@(${tftViewport?.x},${tftViewport?.y})" +
                            if (contentMargins.any) {
                                " content=${contentSource().width}x${contentSource().height}" +
                                    "@(${contentLeft()},${contentTop()}) " +
                                    "[AA margins ${contentMargins.width}x${contentMargins.height} cropped out]"
                            } else {
                                " (no AA content margins)"
                            }
                    )
                }
                if (hasContent) drawFrame()
            } catch (failure: Throwable) {
                log("[COMPOSITOR] setOutput failed: $failure")
            }
        }
    }

    /**
     * Milliseconds spent blocked writing to the encoder target, including the swap in flight.
     *
     * Callable from any thread and never blocking: the stall watchdog that reads it is diagnosing
     * a thread that is, by hypothesis, stuck - taking this compositor's lock to ask would hang the
     * asker on the very condition it is asking about.
     */
    fun downstreamBlockedMillis(): Long {
        if (!outputAppliesBackPressure) return 0L
        val inFlightSince = swapInFlightSinceMs
        val inFlight =
            if (inFlightSince == 0L) 0L
            else (android.os.SystemClock.uptimeMillis() - inFlightSince).coerceAtLeast(0L)
        return swapBlockedMs + inFlight
    }

    /** Caps source redraws during thermal/link adaptation; keep-alive redraws remain enabled. */
    fun setFrameCap(frameRate: Int) {
        frameCap = frameRate.coerceIn(1, DEFAULT_FRAME_CAP)
        lastSourceFrameNanos = 0L
        pendingFrame = false
    }

    fun setTouchSurface(surface: DisplayGeometry) {
        handler.post {
            touchUiW = surface.width
            touchUiH = surface.height
            if (canvasW > 0 && canvasH > 0 && srcW > 0 && srcH > 0) {
                configureTftViewport()
            }
        }
    }

    /**
     * Applies a changed TFT safe-margin setting to a running session immediately, instead of
     * requiring the rider to stop and restart Android Auto for it to take effect.
     */
    fun refreshMargins(margins: TBoxScreenMargins) {
        handler.post {
            if (screenMargins == margins) return@post
            screenMargins = margins
            log("[COMPOSITOR] screen margins updated: $margins")
            if (canvasW > 0 && canvasH > 0 && srcW > 0 && srcH > 0) {
                configureTftViewport()
                if (hasContent) drawFrame()
            }
        }
    }

    fun setPreview(surface: Surface, width: Int, height: Int) {
        handler.post {
            try {
                val attaching = mustAttachWindowSurface(
                    surface,
                    attachedPreviewSurface,
                    previewWindowSurface
                )
                if (attaching) {
                    previewWindowSurface = replaceWindowSurface(previewWindowSurface, surface, "preview")
                    attachedPreviewSurface = surface.takeIf { previewWindowSurface != EGL14.EGL_NO_SURFACE }
                }
                previewCanvasW = width
                previewCanvasH = height
                computePreviewViewport()
                // Once per attach, not once per animation frame: the resize storm above also put
                // 60-odd identical-looking lines into every support log for one preview opening.
                if (attaching) {
                    log(
                        "[COMPOSITOR] phone preview=${width}x$height rect=" +
                            "${previewVpW}x$previewVpH @($previewVpX,$previewVpY)"
                    )
                }
                if (hasContent) drawFrame()
            } catch (failure: Throwable) {
                log("[COMPOSITOR] preview attach failed: $failure")
            }
        }
    }

    fun clearPreview() {
        handler.post {
            previewWindowSurface = destroyWindowSurface(previewWindowSurface)
            attachedPreviewSurface = null
            previewCanvasW = 0
            previewCanvasH = 0
            previewVpX = 0
            previewVpY = 0
            previewVpW = 0
            previewVpH = 0
            log("[COMPOSITOR] phone preview detached")
        }
    }

    /** Detaches the encoder surface before its MediaCodec is released during link recovery. */
    fun clearOutput() {
        val latch = CountDownLatch(1)
        handler.post {
            try {
                encoderWindowSurface = destroyWindowSurface(encoderWindowSurface)
                attachedOutputSurface = null
                canvasW = 0
                canvasH = 0
                tftViewport = null
                log("[COMPOSITOR] TFT encoder output detached")
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }

    fun mapCanvasToSource(cx: Int, cy: Int): Pair<Int, Int>? {
        return tftViewport?.mapToSource(cx, cy)
    }

    /** Maps a T-Box canvas point into the touchscreen dimensions advertised to Android Auto. */
    fun mapCanvasToUi(cx: Int, cy: Int): Pair<Int, Int>? {
        if (tftClipW > 0 && tftClipH > 0 &&
            (cx < tftClipLeft || cy < tftClipTop ||
                cx >= tftClipLeft + tftClipW || cy >= tftClipTop + tftClipH)
        ) return null
        val source = mapCanvasToSource(cx, cy) ?: return null
        return mapSourceToUi(source.first, source.second)
    }

    fun mapPreviewToSource(px: Int, py: Int): Pair<Int, Int>? {
        if (previewVpW <= 0 || previewVpH <= 0) return null
        return currentPreviewViewport().mapToSource(px, py)
    }

    fun mapPreviewToUi(px: Int, py: Int): Pair<Int, Int>? =
        mapPreviewToSource(px, py)?.let { mapSourceToUi(it.first, it.second) }

   fun mapSourceToUi(sourceX: Int, sourceY: Int): Pair<Int, Int>? {
        if (sourceX < screenMargins.left || sourceY < screenMargins.top ||
            sourceX >= srcW - screenMargins.right || sourceY >= srcH - screenMargins.bottom
        ) return null
       val uiW = touchUiW.coerceIn(1, srcW.coerceAtLeast(1))
        val uiH = touchUiH.coerceIn(1, srcH.coerceAtLeast(1))
        // The touch surface is srcW/H trimmed by screenMargins.left/top/right/bottom (see
        // AndroidAutoCapabilityProfile.touchSurface and setTouchSurface's caller), so the true
        // left/top offset is screenMargins.left/top - NOT (srcW - uiW) / 2, which silently assumed
        // the trim was split evenly and was wrong for any asymmetric margin (e.g. left=0, right=100).
        //
        // Content margins are the opposite case and add to that offset: Android Auto does split
        // them evenly, so its origin sits half a margin into the coded frame. A touch mapped
        // without this lands half a margin too low on every panel that needed aspect matching -
        // the very panels this whole path exists for.
        val uiX = sourceX - screenMargins.left - contentLeft()
        val uiY = sourceY - screenMargins.top - contentTop()
        if (uiX !in 0 until uiW || uiY !in 0 until uiH) return null
        return uiX to uiY
    }

    /** The coded frame minus the margins Android Auto was told to keep clear. */
    private fun codedSource() = DisplayGeometry(
        width = srcW.takeIf { it > 0 } ?: sourceGeometry.width,
        height = srcH.takeIf { it > 0 } ?: sourceGeometry.height
    )

    private fun contentSource(): DisplayGeometry {
        val coded = codedSource()
        return DisplayGeometry(
            width = (coded.width - contentMargins.width).coerceIn(1, coded.width),
            height = (coded.height - contentMargins.height).coerceIn(1, coded.height)
        )
    }

    /** Android Auto splits the advertised margin evenly, so its UI starts half a margin in. */
    private fun contentLeft(): Int = contentMargins.width / 2
    private fun contentTop(): Int = contentMargins.height / 2

    /** See [sampleContentOf]; bound to this compositor's coded frame and margins. */
    private fun PreviewViewport.sampleContent(): PreviewViewport =
        sampleContentOf(codedSource(), contentMargins)

    private fun configureTftViewport() {
        val canvas = DisplayGeometry(canvasW, canvasH)
        val source = contentSource()
        val available = DisplayGeometry(
            width = (canvas.width - screenMargins.left - screenMargins.right).coerceAtLeast(1),
            height = (canvas.height - screenMargins.top - screenMargins.bottom).coerceAtLeast(1)
        )
        Matrix.setIdentityM(tftMatrix, 0)
        tftClipLeft = screenMargins.left
        tftClipTop = screenMargins.top
        tftClipW = available.width
        tftClipH = available.height
        tftViewport = when (displayMode) {
            // The touch/UI surface still describes input coordinates only, and must not trim the
            // video: on an 800x384 TFT using an 800x480 AA stream, trimming by it made FIT and
            // STRETCH indistinguishable and exposed an inactive strip at the bottom. What IS
            // trimmed here is [contentMargins] - pixels Android Auto itself leaves black, which
            // is a different quantity that only ever shrinks the source it was computed from.
            AndroidAutoDisplayMode.LETTERBOX -> calculatePreviewViewport(available, source).offsetBy(
                screenMargins.left,
                screenMargins.top
            )
            AndroidAutoDisplayMode.STRETCH -> {
                val stretchViewport = calculateStretchViewport(
                    canvas = available,
                    source = source
                )
                stretchViewport.copy(
                    x = stretchViewport.x + screenMargins.left,
                    y = stretchViewport.y + screenMargins.top
                )
            }
            AndroidAutoDisplayMode.FILL -> calculateFillViewport(available, source).offsetBy(
                screenMargins.left,
                screenMargins.top
            )
        }.sampleContent()
        tftViewport?.let { configureCropMatrix(tftMatrix, it) }
        computePreviewViewport()
    }

    /**
     * Points the texture sampler at [viewport]'s sub-rect of the coded frame.
     *
     * A no-op for a viewport that covers the whole frame, which is what every viewport was until
     * content margins existed - and why this went unnoticed as dead code.
     */
    private fun configureCropMatrix(target: FloatArray, viewport: PreviewViewport) {
        Matrix.setIdentityM(target, 0)
        target[0] = viewport.sourceWidth.toFloat() / viewport.source.width
        target[5] = viewport.sourceHeight.toFloat() / viewport.source.height
        target[12] = viewport.sourceLeft.toFloat() / viewport.source.width
        target[13] = viewport.sourceTop.toFloat() / viewport.source.height
    }

    private fun computePreviewViewport() {
        if (previewCanvasW <= 0 || previewCanvasH <= 0) return
        val viewport = calculatePreviewViewport(
            canvas = DisplayGeometry(previewCanvasW, previewCanvasH),
            source = previewSourceGeometry()
        )
        previewVpX = viewport.x
        previewVpY = viewport.y
        previewVpW = viewport.width
        previewVpH = viewport.height
        // The phone preview crops exactly as the dashboard does. It is what the rider checks the
        // framing against before riding, so showing it the margins the TFT will not get would
        // make it lie in the one direction that matters.
        configureCropMatrix(previewMatrix, viewport.sampleContent())
    }

    private fun currentPreviewViewport() = PreviewViewport(
        x = previewVpX,
        y = previewVpY,
        width = previewVpW,
        height = previewVpH,
        source = previewSourceGeometry(),
        sourceLeft = 0,
        sourceTop = 0
    ).sampleContent()

    /** What the preview lays out against: the content area, not the coded frame. */
    private fun previewSourceGeometry() = contentSource()

    private fun PreviewViewport.offsetBy(dx: Int, dy: Int): PreviewViewport = copy(
        x = x + dx,
        y = y + dy
    )

    private fun onFrame() {
        try {
            surfaceTexture.updateTexImage()
        } catch (_: Throwable) {
            return
        }
        hasContent = true
        framesIn++
        val now = System.nanoTime()
        val interval = 1_000_000_000L / frameCap.coerceAtLeast(1)
        val idleMs = android.os.SystemClock.uptimeMillis() - lastDrawMs
        if (lastSourceFrameNanos == 0L || idleMs >= interval / 1_000_000L) {
            lastSourceFrameNanos = now
            pendingFrame = false
            drawFrame()
        } else {
            // SurfaceTexture already contains the newest frame; flush it on the next pacing tick.
            pendingFrame = true
            framesCoalesced++
        }
    }

    private val keepAlive = object : Runnable {
        override fun run() {
            if (hasContent && encoderWindowSurface != EGL14.EGL_NO_SURFACE) {
                val idleMs = android.os.SystemClock.uptimeMillis() - lastDrawMs
                val intervalMs = 1_000L / frameCap.coerceAtLeast(1)
                if (pendingFrame && idleMs >= intervalMs) {
                    pendingFrame = false
                    drawFrame()
                } else if (idleMs >= idleRedrawMs) {
                    keepAliveRedraws++
                    drawFrame()
                }
            }
            reportWindowIfDue()
            handler.postDelayed(this, keepAliveTickMs)
        }
    }

    /**
     * Summarises the window once per [statsWindowMs], while there is an encoder target to summarise.
     *
     * Every stage of the video path is on one line on purpose. Read alone, "the decoder produced
     * 3fps" is the same sentence whether the decoder broke or whether nothing downstream was
     * taking its frames - and picking the wrong one of those costs a fix that does nothing. Read
     * together, `in` versus `blocked` separates them: frames that never arrived with an idle pipe
     * is a decoder fault, frames that never arrived with a pipe blocked most of the window is not.
     *
     * This runs on the thread that does the blocking, so a pipe that never drains at all stops it
     * from reporting - and the window it eventually prints is longer than [statsWindowMs], which
     * is why the elapsed time is printed rather than assumed. That case is covered from the other
     * end: [downstreamBlockedMillis] counts the swap in flight, so the decoder's own watchdog
     * names the jam from a thread that is not stuck in it.
     */
    private fun reportWindowIfDue() {
        if (encoderWindowSurface == EGL14.EGL_NO_SURFACE) return
        val now = android.os.SystemClock.uptimeMillis()
        if (statsWindowStartedMs == 0L) {
            resetStatsWindow(now)
            return
        }
        val elapsedMs = now - statsWindowStartedMs
        if (elapsedMs < statsWindowMs) return
        val blockedMs = swapBlockedMs - statsWindowBlockedMs
        log(
            "[COMPOSITOR] ${elapsedMs / 1_000L}s: in=$framesIn drawn=$framesDrawn " +
                "coalesced=$framesCoalesced keepalive=$keepAliveRedraws " +
                "blocked=${blockedMs}ms (worst ${worstSwapMs}ms)" +
                // Said in words as well as numbers: the reader of a support log is looking for
                // which stage broke, and this line is the answer to that question.
                if (outputAppliesBackPressure && blockedMs * 2 >= elapsedMs) {
                    " - the encoder is not draining, so the video path is jammed downstream of " +
                        "the decoder, not at it."
                } else {
                    ""
                }
        )
        resetStatsWindow(now)
    }

    private fun resetStatsWindow(now: Long) {
        statsWindowStartedMs = now
        statsWindowBlockedMs = swapBlockedMs
        framesIn = 0
        framesDrawn = 0
        framesCoalesced = 0
        keepAliveRedraws = 0
        worstSwapMs = 0L
    }

    private fun drawFrame() {
        if (!::surfaceTexture.isInitialized) return
        surfaceTexture.getTransformMatrix(texMatrix)
        val viewport = tftViewport
        if (encoderWindowSurface != EGL14.EGL_NO_SURFACE && viewport != null) {
            drawTarget(
                encoderWindowSurface,
                viewport.x,
                viewport.y,
                viewport.width,
                viewport.height,
               tftMatrix,
                recordable = true,
                clipX = tftClipLeft,
                clipY = tftClipTop,
                clipWidth = tftClipW,
                clipHeight = tftClipH,
                framebufferHeight = canvasH
            )
            lastDrawMs = android.os.SystemClock.uptimeMillis()
        }
        if (previewWindowSurface != EGL14.EGL_NO_SURFACE) {
            drawTarget(
                previewWindowSurface,
                previewVpX,
                previewVpY,
                previewVpW,
                previewVpH,
                previewMatrix,
                recordable = false
            )
        }
    }

    private fun drawTarget(
        target: EGLSurface,
        viewportX: Int,
        viewportY: Int,
        viewportWidth: Int,
        viewportHeight: Int,
       contentMatrix: FloatArray,
        recordable: Boolean,
        clipX: Int = 0,
        clipY: Int = 0,
        clipWidth: Int = 0,
        clipHeight: Int = 0,
        framebufferHeight: Int = 0
    ) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        if (!EGL14.eglMakeCurrent(eglDisplay, target, target, eglContext)) return
       GLES20.glClearColor(0f, 0f, 0f, 1f)
       GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (clipWidth > 0 && clipHeight > 0 && framebufferHeight > 0) {
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            GLES20.glScissor(clipX, framebufferHeight - clipY - clipHeight, clipWidth, clipHeight)
        }
        // Viewports are top-left like the touch path that shares them (mapCanvasToSource), but GL
        // window coordinates grow upward - flip y exactly as the scissor above does. Centred
        // viewports hid this: the flip is a no-op there, and asymmetric vertical screen margins
        // were the first placement where the two conventions disagree (top margin moved the
        // picture up, into the bezel, instead of down).
        val glViewportY =
            if (framebufferHeight > 0) framebufferHeight - viewportY - viewportHeight else viewportY
        GLES20.glViewport(viewportX, glViewportY, viewportWidth, viewportHeight)
        GLES20.glUseProgram(program)

        quad.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPosition)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniformMatrix4fv(uCropMatrix, 1, false, contentMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
       GLES20.glDisableVertexAttribArray(aPosition)
       GLES20.glDisableVertexAttribArray(aTexCoord)
        // Inside the scissor on purpose: the banner belongs on the picture, never in the bezel
        // margins the motorcycle's furniture covers.
        drawPressBanner(viewportWidth, viewportHeight)
        if (clipWidth > 0 && clipHeight > 0 && framebufferHeight > 0) {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        }

        if (recordable) {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, target, System.nanoTime())
            // Only the encoder target is timed. The preview target is a SurfaceView the phone's
            // compositor consumes on its own; it cannot apply the back pressure being measured
            // here, and counting it would put the phone's own vsync into the bike's number.
            val startedMs = android.os.SystemClock.uptimeMillis()
            swapInFlightSinceMs = startedMs
            try {
                EGL14.eglSwapBuffers(eglDisplay, target)
            } finally {
                val blockedMs =
                    (android.os.SystemClock.uptimeMillis() - startedMs).coerceAtLeast(0L)
                swapBlockedMs += blockedMs
                swapInFlightSinceMs = 0L
                if (blockedMs > worstSwapMs) worstSwapMs = blockedMs
                framesDrawn++
            }
            return
        }
        EGL14.eglSwapBuffers(eglDisplay, target)
    }

    fun release() {
        handler.removeCallbacks(keepAlive)
        handler.post {
            runCatching { inputSurface?.release() }
            inputSurface = null
            runCatching { if (::surfaceTexture.isInitialized) surfaceTexture.release() }
            encoderWindowSurface = destroyWindowSurface(encoderWindowSurface)
            previewWindowSurface = destroyWindowSurface(previewWindowSurface)
            attachedOutputSurface = null
            attachedPreviewSurface = null
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbuffer)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        thread.quitSafely()
    }

    /**
     * Whether [requested] needs an EGL window surface created for it, or the one already attached
     * can be kept.
     *
     * A resize of the SAME Surface keeps its window surface: EGL follows the underlying buffer
     * queue's size on its own. Destroying and recreating on every call instead makes
     * eglCreateWindowSurface fail with EGL_BAD_ALLOC while the previous one is still being torn
     * down, and both surfaces this compositor drives are resized in bursts by a panel animation -
     * Android delivers one surfaceChanged per frame of it (1220x2712, x2710, x2707, ... over
     * ~700ms).
     *
     * Both call sites go through here because fixing this in one of them is exactly what already
     * happened: the preview path was guarded on 2026-08-24 after rider 315e0af3's log showed 65
     * failures in 46 seconds, the encoder path was not, and rider 4d8a4c5b's log (2026-08-26) then
     * showed it failing on three of the four times he opened the phone preview - which routes
     * here. A failure leaves EGL_NO_SURFACE and the compositor draws NOTHING to that target until
     * the next attach, so he sat tapping a black rectangle at 19:17:22.
     *
     * [attached] is left null by a failed attach, so the next call retries rather than treating a
     * surface that was never created as already current.
     */
    private fun mustAttachWindowSurface(
        requested: Surface,
        attached: Surface?,
        current: EGLSurface
    ): Boolean = requested !== attached || current == EGL14.EGL_NO_SURFACE

    /**
     * Destroys [current] and creates [surface]'s replacement. Never throws: on create failure the
     * old handle is already gone (destroyed above), so returning it here would leave the caller's
     * field pointing at a destroyed EGLSurface - drawFrame() would then eglMakeCurrent()/
     * eglSwapBuffers() on a dead surface instead of skipping the target. Always assign the result.
     */
    private fun replaceWindowSurface(current: EGLSurface, surface: Surface, tag: String): EGLSurface {
        destroyWindowSurface(current)
        return try {
            EGL14.eglCreateWindowSurface(
                eglDisplay,
                eglConfig,
                surface,
                intArrayOf(EGL14.EGL_NONE),
                0
            ).also {
                check(it != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: ${EGL14.eglGetError()}" }
            }
        } catch (failure: Throwable) {
            log("[COMPOSITOR] $tag surface creation failed: $failure")
            EGL14.EGL_NO_SURFACE
        }
    }

    private fun destroyWindowSurface(surface: EGLSurface): EGLSurface {
        if (surface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(eglDisplay, surface)
        }
        return EGL14.EGL_NO_SURFACE
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }
        val configAttributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, configAttributes, 0, configs, 0, 1, configCount, 0)) {
            "eglChooseConfig failed"
        }
        eglConfig = checkNotNull(configs[0])
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay,
            eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0
        )
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
    }

    private fun initGl() {
        val vertexShader = """
            uniform mat4 uTexMatrix;
            uniform mat4 uCropMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * uCropMatrix * aTexCoord).xy;
            }
        """.trimIndent()
        val fragmentShader = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, vTexCoord); }
        """.trimIndent()
        program = linkProgram(vertexShader, fragmentShader)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uCropMatrix = GLES20.glGetUniformLocation(program, "uCropMatrix")
        Matrix.setIdentityM(texMatrix, 0)
        Matrix.setIdentityM(tftMatrix, 0)
        Matrix.setIdentityM(previewMatrix, 0)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
    }

    /**
     * Paints what the rider just pressed over the Android Auto picture, for one second.
     *
     * Android Auto composites in GL, so the banner cannot be drawn with a Canvas the way the Ride
     * Dashboard draws its own: it is rendered to a bitmap once per press by [HandlebarPressHud] -
     * the same bitmap both screens use, so the rider recognises the same thing on either - and put
     * on screen here as a textured quad.
     */
    private fun drawPressBanner(viewportWidth: Int, viewportHeight: Int) {
        val press = HandlebarPressHud.current()
        if (press == null) {
            bannerUploaded = null
            return
        }
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        val bitmap = HandlebarPressHud.banner(press, viewportWidth) ?: return
        if (bannerProgram == 0) {
            bannerProgram = linkProgram(BANNER_VERTEX_SHADER, BANNER_FRAGMENT_SHADER)
            bannerPosition = GLES20.glGetAttribLocation(bannerProgram, "aPosition")
            bannerTexCoord = GLES20.glGetAttribLocation(bannerProgram, "aTexCoord")
            bannerSampler = GLES20.glGetUniformLocation(bannerProgram, "uTexture")
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            bannerTextureId = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bannerTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bannerTextureId)
        if (bannerUploaded != press) {
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bannerUploaded = press
        }

        // Top centre, in the viewport's own normalised coordinates. The bitmap is already sized
        // against the viewport width, so its height only has to be turned into the same units.
        val halfWidth = HandlebarPressHud.bannerWidth(viewportWidth).toFloat() / viewportWidth
        val height = 2f * bitmap.height / viewportHeight
        val top = 0.90f
        val bottom = top - height
        val quad = floatArrayOf(
            -halfWidth, bottom, 0f, 1f,
            halfWidth, bottom, 1f, 1f,
            -halfWidth, top, 0f, 0f,
            halfWidth, top, 1f, 0f
        )
        val buffer = ByteBuffer.allocateDirect(quad.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quad)

        GLES20.glUseProgram(bannerProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        buffer.position(0)
        GLES20.glVertexAttribPointer(bannerPosition, 2, GLES20.GL_FLOAT, false, 16, buffer)
        GLES20.glEnableVertexAttribArray(bannerPosition)
        buffer.position(2)
        GLES20.glVertexAttribPointer(bannerTexCoord, 2, GLES20.GL_FLOAT, false, 16, buffer)
        GLES20.glEnableVertexAttribArray(bannerTexCoord)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bannerTextureId)
        GLES20.glUniform1i(bannerSampler, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(bannerPosition)
        GLES20.glDisableVertexAttribArray(bannerTexCoord)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES20.glCreateProgram().also { result ->
            GLES20.glAttachShader(result, vertex)
            GLES20.glAttachShader(result, fragment)
            GLES20.glLinkProgram(result)
            val status = IntArray(1)
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(result) }
        }
    }

    private fun compileShader(type: Int, source: String): Int = GLES20.glCreateShader(type).also { shader ->
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
    }

    private companion object {
        const val DEFAULT_FRAME_CAP = 30

        const val BANNER_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                vTexCoord = aTexCoord;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        const val BANNER_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
