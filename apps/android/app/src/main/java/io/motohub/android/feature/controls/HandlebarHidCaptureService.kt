// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.controls

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.net.Uri
import io.motohub.android.session.ProjectionEventLog

/**
 * Sees key presses from a Bluetooth HID-keyboard handlebar remote system-wide, regardless of
 * which app currently has focus on the TFT.
 *
 * Ordinary HID key events are delivered only to whatever window has focus — during a ride that
 * is normally Android Auto's own surface, not this app — so a plain `dispatchKeyEvent` in
 * [io.motohub.android.MainActivity] would never see them. Worse, a literal volume keycode
 * bypasses app focus entirely and goes straight to the system volume UI. An Accessibility
 * Service with [AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS] is the one mechanism
 * short of root that can intercept either before that happens, which is why HID mode exists
 * as a whole: [MediaButtonBridge]'s normal path only ever sees keys the system already decided
 * to treat as AVRCP media-button events.
 *
 * Entirely inert unless BOTH are true: the rider granted this service in system Accessibility
 * settings (an explicit toggle Android requires; nothing in-app can flip it for them — see
 * [openAccessibilitySettings]) AND [HandlebarControlStore.inputMode] is currently
 * [HandlebarInputMode.HID]. In AVRCP mode, or with the service off, every key event is returned
 * unconsumed so it reaches whatever app would normally get it.
 */
class HandlebarHidCaptureService : AccessibilityService() {

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            // No event types at all: key filtering comes from FLAG_REQUEST_FILTER_KEY_EVENTS
            // (plus canRequestFilterKeyEvents in the XML), never from eventTypes. Subscribing to
            // any would hand this service the screen's content for no reason - it needs to read
            // nothing but hardware keycodes. See handlebar_hid_capture_service.xml.
            eventTypes = 0
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 0
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Traced unconditionally, before every gate below: whether this even fires at all is
        // the first fact needed to tell "Android never delivered the key event here" apart from
        // "it arrived but a gate downstream dropped it" - the two look identical from a rider
        // just seeing the press do nothing.
        val actionName = when (event.action) {
            KeyEvent.ACTION_DOWN -> "down"
            KeyEvent.ACTION_UP -> "up"
            else -> "action=${event.action}"
        }
        // The device is what tells a handlebar remote apart from the phone's own volume rocker,
        // and it is the missing half of every HID support case so far: a rider reporting "nothing
        // happens" and a rider reporting "my phone's volume moved instead" produced the same log.
        val device = event.device
        val descriptor = device?.descriptor
        val deviceName = device?.name.orEmpty()
        val trace = "[HID] raw ${KeyEvent.keyCodeToString(event.keyCode)} $actionName " +
            "repeat=${event.repeatCount} source=${event.source} " +
            "device=${deviceName.ifBlank { "?" }} descriptor=${descriptor ?: "?"}"

        // Said before every gate below: the rider watching the TFT gets told a key arrived
        // even when every gate below throws it away.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            HandlebarPressHud.pressed(applicationContext, HandlebarPressHud.buttonName(event.keyCode))
        }

        if (HandlebarControlStore.inputMode(applicationContext) != HandlebarInputMode.HID) {
            ProjectionEventLog.debug("HID_BTN", "$trace (AVRCP mode selected; ignored)")
            return false
        }
        if (!HandlebarControlStore.isEnabled(applicationContext)) {
            ProjectionEventLog.record("HID_BTN", "$trace (handlebar capture is off in Settings; ignored)")
            return false
        }
        val consumed = MediaButtonBridge.dispatchHidKeyEvent(event.keyCode, event.action, event.repeatCount)
        if (!consumed) {
            ProjectionEventLog.record(
                "HID_BTN",
                "$trace not consumed - no session is capturing right now (start Android Auto " +
                    "or mirroring first), or this keycode is not recognized"
            )
        }
        return consumed
    }

    companion object {
        /** Deep-links to the system screen where the rider grants this service — Android
         *  requires an explicit in-settings toggle; no runtime permission covers it. */
        fun openAccessibilitySettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        /** Whether the rider has already granted this service, for the settings screen's hint. */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, HandlebarHidCaptureService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        /**
         * Deep-links to this app's own App info page, where Android 13+ hides the second half of
         * granting an Accessibility Service to an app that did not come from a store.
         *
         * MOTO-HUB is installed from a GitHub download, and updates itself through an
         * ACTION_VIEW install - neither is a store, so Android marks the Accessibility toggle a
         * "restricted setting" and greys it out. The rider has to open App info, then the ⋮ menu,
         * then "Allow restricted settings" before the toggle in [openAccessibilitySettings] can
         * be flipped at all. There is no API to ask whether that gate is currently blocking, and
         * none to lift it - so the settings screen watches [isEnabled] instead and offers this
         * once the rider comes back from Accessibility settings without the service on.
         *
         * (A phone that installed MOTO-HUB over adb is exempt from the gate, which is why this
         * never showed up in development.)
         */
        fun openAppInfo(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
