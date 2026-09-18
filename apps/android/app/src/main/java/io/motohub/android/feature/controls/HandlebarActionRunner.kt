// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.controls

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import io.motohub.android.androidauto.AndroidAutoInputCodes
import io.motohub.android.androidauto.AndroidAutoPreviewRuntime

/**
 * What performing a [HandlebarAction] amounts to, with nothing performed yet.
 *
 * Split out from the doing so the wiring can be asserted in a plain unit test: this is the table
 * that says the rider's Select really reaches Android Auto's Enter and not its Home, and it is
 * the only guard against a future edit to [HandlebarActionRunner] silently rewiring the
 * motorcycle's handlebar. See HandlebarHidKeyLearningTest.
 */
internal sealed interface HandlebarActionPlan {
    /** The action is bound to nothing — the press is deliberately inert. */
    data object Idle : HandlebarActionPlan
    data class Key(val androidAutoKeyCode: Int) : HandlebarActionPlan
    data class Scroll(val delta: Int) : HandlebarActionPlan
    /** Zero-based index into [SavedPlaces]. */
    data class Nav(val slot: Int) : HandlebarActionPlan
    /** Handled by the Ride Dashboard, which only the ADVANCED edition has. */
    data class Dashboard(val action: HandlebarAction) : HandlebarActionPlan
    /** The rider's music player. */
    data class Media(val action: HandlebarAction) : HandlebarActionPlan
}

internal fun planFor(action: HandlebarAction): HandlebarActionPlan = when (action) {
    HandlebarAction.NONE -> HandlebarActionPlan.Idle
    HandlebarAction.SCROLL_FORWARD -> HandlebarActionPlan.Scroll(+1)
    HandlebarAction.SCROLL_BACK -> HandlebarActionPlan.Scroll(-1)
    HandlebarAction.DPAD_UP -> HandlebarActionPlan.Key(AndroidAutoInputCodes.KEY_UP)
    HandlebarAction.DPAD_DOWN -> HandlebarActionPlan.Key(AndroidAutoInputCodes.KEY_DOWN)
    HandlebarAction.DPAD_LEFT -> HandlebarActionPlan.Key(AndroidAutoInputCodes.KEY_LEFT)
    HandlebarAction.DPAD_RIGHT -> HandlebarActionPlan.Key(AndroidAutoInputCodes.KEY_RIGHT)
    HandlebarAction.SELECT -> HandlebarActionPlan.Key(AndroidAutoInputCodes.KEY_ENTER)
    HandlebarAction.BACK -> HandlebarActionPlan.Key(AndroidAutoInputCodes.KEY_BACK)
    HandlebarAction.HOME -> HandlebarActionPlan.Key(AndroidAutoInputCodes.KEY_HOME)
    HandlebarAction.ASSISTANT -> HandlebarActionPlan.Key(AndroidAutoInputCodes.KEY_ASSISTANT)
    HandlebarAction.NAV_1 -> HandlebarActionPlan.Nav(0)
    HandlebarAction.NAV_2 -> HandlebarActionPlan.Nav(1)
    HandlebarAction.NAV_3 -> HandlebarActionPlan.Nav(2)
    HandlebarAction.DASH_NEXT_PANEL,
    HandlebarAction.DASH_FULLSCREEN_MAP,
    HandlebarAction.DASH_MAP_ZOOM,
    HandlebarAction.DASH_WIDGET_LEFT,
    HandlebarAction.DASH_WIDGET_RIGHT -> HandlebarActionPlan.Dashboard(action)
    HandlebarAction.MEDIA_PLAY_PAUSE,
    HandlebarAction.MEDIA_NEXT,
    HandlebarAction.MEDIA_PREVIOUS,
    HandlebarAction.MEDIA_VOLUME_UP,
    HandlebarAction.MEDIA_VOLUME_DOWN -> HandlebarActionPlan.Media(action)
}

/**
 * Performs a [HandlebarAction], for any caller that has one and no [MediaButtonBridge].
 *
 * Extracted from `MediaButtonBridge.dispatch` unchanged, because a bound press no longer arrives
 * only from a motorcycle: [HandlebarHidCaptureService] resolves a learned Bluetooth-controller
 * keycode to an action while no Android Auto session — and therefore no bridge instance — exists
 * at all. Everything here depends on a Context and nothing else, which is what makes that
 * possible.
 *
 * Runs on the caller's thread, as it always did: both callers are on the main thread.
 */
object HandlebarActionRunner {

    /**
     * How a dashboard action gets performed, registered by the edition that has a dashboard.
     *
     * The Ride Dashboard lives in the pro source set and this file does not, so the enum can name
     * its verbs while the doing stays where the dashboard is. Null in CORE, and null in ADVANCED
     * until a dashboard session installs one - a press then reports that there is nothing to
     * drive rather than disappearing.
     */
    @Volatile
    var dashboardSink: ((HandlebarAction) -> Boolean)? = null

    /**
     * How a transport command reaches the rider's player, registered by the edition that can ask.
     *
     * Picking the right session needs `getActiveSessions`, which needs an enabled
     * NotificationListenerService - ADVANCED has one for Now Playing, CORE does not. Where it is
     * absent the fallback below dispatches a media key instead, which is coarser: Android sends it
     * to the most recently active session, and during a handlebar capture that can be OUR fake
     * one.
     */
    @Volatile
    var mediaSink: ((HandlebarAction) -> Boolean)? = null

    /** True when something was actually performed — a bound action whose sink accepted it. */
    fun run(context: Context, action: HandlebarAction, log: (String) -> Unit): Boolean =
        when (val plan = planFor(action)) {
            HandlebarActionPlan.Idle -> false
            is HandlebarActionPlan.Key -> sendKey(plan.androidAutoKeyCode, log)
            is HandlebarActionPlan.Scroll -> sendScroll(plan.delta, log)
            is HandlebarActionPlan.Nav -> navToSavedPlace(context, plan.slot, log)
            is HandlebarActionPlan.Media -> performMedia(context, plan.action, log)
            is HandlebarActionPlan.Dashboard -> {
                val sink = dashboardSink
                if (sink == null) {
                    log("[BTN] ${plan.action.label} needs the Ride Dashboard, which is not running")
                    false
                } else {
                    runCatching { sink(plan.action) }
                        .onFailure { log("[BTN] ${plan.action.label} failed: ${it.message}") }
                        .getOrDefault(false)
                }
            }
        }

    /**
     * Volume goes through [MediaButtonBridge], never straight to AudioManager.
     *
     * In AVRCP mode the bridge PINS the music volume and reads any drift as the rider working the
     * handlebar rocker. A volume change written behind its back is indistinguishable from a press,
     * so a mapped volume button would fire phantom handlebar gestures - while riding. Writing
     * through the bridge lets it move its own pin.
     */
    private fun performMedia(context: Context, action: HandlebarAction, log: (String) -> Unit): Boolean {
        if (action == HandlebarAction.MEDIA_VOLUME_UP || action == HandlebarAction.MEDIA_VOLUME_DOWN) {
            val (current, max) = MediaButtonBridge.volumeLevels(context)
            val step = if (action == HandlebarAction.MEDIA_VOLUME_UP) 1 else -1
            val wanted = (current + step).coerceIn(0, max)
            if (wanted == current) {
                log("[BTN] ${action.label}: already at ${if (step > 0) "maximum" else "minimum"}")
                return false
            }
            MediaButtonBridge.setVolume(context, wanted)
            log("[BTN] ${action.label} -> $wanted/$max")
            return true
        }
        // Falls through rather than giving up when the sink says no: the sink is installed on
        // app start and can only answer once the rider has granted the notification listener, so
        // "no" means "cannot ask", not "there is no player".
        val viaSession = mediaSink?.let { sink ->
            runCatching { sink(action) }
                .onFailure { log("[BTN] ${action.label} failed: ${it.message}") }
                .getOrDefault(false)
        } ?: false
        if (viaSession) return true
        // The blunt path, guarded so the key cannot come back in through our own AVRCP session
        // and read as a handlebar press.
        val keyCode = when (action) {
            HandlebarAction.MEDIA_NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            HandlebarAction.MEDIA_PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        MediaButtonBridge.noteSelfInjectedMediaKey(keyCode)
        return runCatching {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            log("[BTN] ${action.label} dispatched as ${KeyEvent.keyCodeToString(keyCode)}")
            true
        }.getOrElse {
            log("[BTN] ${action.label} failed: ${it.message}")
            false
        }
    }

    fun sendKey(keycode: Int, log: (String) -> Unit): Boolean {
        // Routes through AndroidAutoPreviewRuntime (not AaInputBridge directly) so this reaches
        // Core's live AA session over AIDL when Android Auto is delegated there (PRO), not just
        // a local AaInput sink that only exists when AA runs in-process (CORE).
        if (AndroidAutoPreviewRuntime.sendKey(keycode)) return true
        log("[BTN] Android Auto input is not ready; key=$keycode dropped")
        return false
    }

    fun sendScroll(delta: Int, log: (String) -> Unit): Boolean {
        if (AndroidAutoPreviewRuntime.sendScroll(delta)) return true
        log("[BTN] Android Auto input is not ready; scroll=$delta dropped")
        return false
    }

    private fun navToSavedPlace(context: Context, slot: Int, log: (String) -> Unit): Boolean {
        val query = SavedPlaces.query(context, slot)
        if (query.isBlank()) {
            log("[BTN] saved place ${slot + 1} is not set — set it in Controls → Saved Places")
            return false
        }
        log("[BTN] launching navigation to saved place ${slot + 1}: $query")
        NavLauncher.navigate(context, query, log)
        return true
    }
}
