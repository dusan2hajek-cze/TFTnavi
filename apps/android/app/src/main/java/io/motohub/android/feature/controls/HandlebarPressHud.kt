// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.controls

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.KeyEvent

/**
 * What the rider just pressed and what it did, painted onto the picture the dashboard is shown.
 *
 * A button that does nothing looks exactly like a button that was never seen: 2026-08-29 cost an
 * evening to a controller whose presses were arriving, being resolved, and then dropped, with the
 * only evidence in a log nobody reads mid-ride. This says both halves where the rider is already
 * looking - the key that arrived, and the action it ran - and gets out of the way after a second.
 *
 * Deliberately in the STREAMED picture rather than on the phone: Android Auto and the Ride
 * Dashboard are both things you watch on the TFT, and a phone in a pocket cannot answer "what did
 * that button just do".
 *
 * Process-wide and polled rather than observed. Both renderers already redraw at 20-30fps and
 * neither is a Compose tree, so a StateFlow would buy nothing and cost a subscription per frame.
 */
object HandlebarPressHud {

    /**
     * [action] is null when the press was seen but nothing ran it - unmapped, switched off, or
     * bound in the other edition. It is still shown: "did that button do anything" cannot be
     * answered by a banner that only appears when the answer is yes.
     */
    data class Press(val button: String, val action: String?, val atElapsedRealtimeMillis: Long)

    private const val PREFERENCES = "handlebar_press_hud"
    private const val ENABLED = "enabled"

    /** How long one press stays on screen. Vincenzo asked for a second and a second is right. */
    const val VISIBLE_MILLIS = 1_000L

    @Volatile
    private var enabled = false

    @Volatile
    private var latest: Press? = null

    /** Cached so a press costs one bitmap, not one per frame for a second. */
    @Volatile
    private var rendered: Pair<Press, Bitmap>? = null

    /**
     * Reads the stored switch once per process and keeps it.
     *
     * Called from the capture paths, which run on every key event: [Context.getSharedPreferences]
     * is cheap but not free, and this sits in front of the motorcycle's handlebar.
     */
    fun bind(context: Context) {
        // Off unless the rider asks for it. This is a diagnostic: it answers "did that button do
        // anything", and a rider who is not asking that question does not want a black box over
        // the map every time they change a widget. A stored true survives - anyone who turned it
        // on keeps it.
        enabled = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(ENABLED, false)
    }

    fun isEnabled(context: Context): Boolean {
        bind(context)
        return enabled
    }

    fun setEnabled(context: Context, value: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED, value)
            .apply()
        enabled = value
        if (!value) latest = null
    }

    /**
     * A press arrived. Called from every path a button can take, before every gate.
     *
     * Deliberately in front of the capture switch, the mapping lookup and the exclusivity rules:
     * those all decide whether to ACT on a press, and this decides whether to SHOW one. A key
     * that is ignored is exactly the key the rider most needs to see arrive.
     */
    fun pressed(context: Context, button: String) {
        bind(context)
        if (!enabled) return
        latest = Press(button, null, SystemClock.elapsedRealtime())
    }

    /** The same press, once something has run it. Replaces the line [pressed] put up. */
    fun performed(context: Context, button: String, action: String) {
        bind(context)
        if (!enabled) return
        latest = Press(button, action, SystemClock.elapsedRealtime())
    }

    /** The press to draw right now, or null - expired, switched off, or nothing pressed yet. */
    fun current(): Press? {
        if (!enabled) return null
        val press = latest ?: return null
        if (SystemClock.elapsedRealtime() - press.atElapsedRealtimeMillis >= VISIBLE_MILLIS) {
            return null
        }
        return press
    }

    /** "KEYCODE_DPAD_UP" as the rider would write it: DPAD-UP. */
    fun buttonName(keyCode: Int): String =
        KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace('_', '-')

    /**
     * The banner for [press], sized against a [canvasWidth]-wide target.
     *
     * One bitmap for both renderers on purpose. The Ride Dashboard draws on a Canvas and could
     * paint the text itself, Android Auto composites in GL and cannot - two implementations would
     * be two subtly different banners, and the rider is meant to recognise this thing instantly on
     * either screen.
     */
    fun banner(press: Press, canvasWidth: Int): Bitmap? {
        if (canvasWidth <= 0) return null
        rendered?.let { (cached, bitmap) ->
            if (cached == press && bitmap.width == bannerWidth(canvasWidth)) return bitmap
        }
        val width = bannerWidth(canvasWidth)
        val buttonSize = width * 0.085f
        val actionSize = width * 0.055f
        val padding = width * 0.045f

        val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = buttonSize
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val actionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = actionSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val buttonText = press.button
        // The action name is the long one and there is no room to wrap: ellipsize rather than let
        // it run off a banner sized for a 800x480 TFT.
        val actionText = press.action?.let { ellipsize(it, actionPaint, width - padding * 2) }

        val buttonMetrics = buttonPaint.fontMetrics
        val actionMetrics = actionPaint.fontMetrics
        val lineGap = width * 0.015f
        // One line when nothing ran it: an empty second line would read as a banner that failed
        // to say something, rather than a press that did not do anything.
        val actionHeight =
            if (actionText == null) 0f else lineGap + (actionMetrics.descent - actionMetrics.ascent)
        val height = padding * 2 + (buttonMetrics.descent - buttonMetrics.ascent) + actionHeight

        val bitmap = Bitmap.createBitmap(width, height.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val corner = width * 0.03f
        canvas.drawRoundRect(
            RectF(0f, 0f, width.toFloat(), bitmap.height.toFloat()),
            corner,
            corner,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        )
        var baseline = padding - buttonMetrics.ascent
        canvas.drawText(buttonText, padding, baseline, buttonPaint)
        if (actionText != null) {
            baseline += buttonMetrics.descent + lineGap - actionMetrics.ascent
            canvas.drawText(actionText, padding, baseline, actionPaint)
        }

        rendered = press to bitmap
        return bitmap
    }

    /** Wide enough to read at arm's length, narrow enough to leave the picture usable. */
    fun bannerWidth(canvasWidth: Int): Int = (canvasWidth * 0.55f).toInt().coerceAtLeast(1)

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var candidate = text
        while (candidate.isNotEmpty() && paint.measureText("$candidate…") > maxWidth) {
            candidate = candidate.dropLast(1)
        }
        return "$candidate…"
    }
}
