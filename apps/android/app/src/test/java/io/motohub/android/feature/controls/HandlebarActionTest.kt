// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The wiring table asserted by hand, action by action.
 *
 * This is the regression net for pulling the action `when` out of `MediaButtonBridge.dispatch`
 * into [HandlebarActionRunner]: the Android Auto keycodes are spelled out as literals rather than
 * read back from [io.motohub.android.androidauto.AndroidAutoInputCodes], so an edit that renames
 * or renumbers a constant cannot quietly rewire the motorcycle's Select onto Home and still pass.
 */
class HandlebarActionPlanTest {

    @Test
    fun `every action plans to the input it has always sent`() {
        val expected = mapOf(
            HandlebarAction.NONE to HandlebarActionPlan.Idle,
            HandlebarAction.SCROLL_FORWARD to HandlebarActionPlan.Scroll(+1),
            HandlebarAction.SCROLL_BACK to HandlebarActionPlan.Scroll(-1),
            HandlebarAction.DPAD_UP to HandlebarActionPlan.Key(19),
            HandlebarAction.DPAD_DOWN to HandlebarActionPlan.Key(20),
            HandlebarAction.DPAD_LEFT to HandlebarActionPlan.Key(21),
            HandlebarAction.DPAD_RIGHT to HandlebarActionPlan.Key(22),
            HandlebarAction.SELECT to HandlebarActionPlan.Key(23),
            HandlebarAction.BACK to HandlebarActionPlan.Key(4),
            HandlebarAction.HOME to HandlebarActionPlan.Key(3),
            HandlebarAction.ASSISTANT to HandlebarActionPlan.Key(84),
            HandlebarAction.NAV_1 to HandlebarActionPlan.Nav(0),
            HandlebarAction.NAV_2 to HandlebarActionPlan.Nav(1),
            HandlebarAction.NAV_3 to HandlebarActionPlan.Nav(2),
            HandlebarAction.DASH_NEXT_PANEL to HandlebarActionPlan.Dashboard(HandlebarAction.DASH_NEXT_PANEL),
            HandlebarAction.DASH_FULLSCREEN_MAP to HandlebarActionPlan.Dashboard(HandlebarAction.DASH_FULLSCREEN_MAP),
            HandlebarAction.DASH_MAP_ZOOM to HandlebarActionPlan.Dashboard(HandlebarAction.DASH_MAP_ZOOM),
            HandlebarAction.DASH_WIDGET_LEFT to HandlebarActionPlan.Dashboard(HandlebarAction.DASH_WIDGET_LEFT),
            HandlebarAction.DASH_WIDGET_RIGHT to HandlebarActionPlan.Dashboard(HandlebarAction.DASH_WIDGET_RIGHT),
            HandlebarAction.MEDIA_PLAY_PAUSE to HandlebarActionPlan.Media(HandlebarAction.MEDIA_PLAY_PAUSE),
            HandlebarAction.MEDIA_NEXT to HandlebarActionPlan.Media(HandlebarAction.MEDIA_NEXT),
            HandlebarAction.MEDIA_PREVIOUS to HandlebarActionPlan.Media(HandlebarAction.MEDIA_PREVIOUS),
            HandlebarAction.MEDIA_VOLUME_UP to HandlebarActionPlan.Media(HandlebarAction.MEDIA_VOLUME_UP),
            HandlebarAction.MEDIA_VOLUME_DOWN to HandlebarActionPlan.Media(HandlebarAction.MEDIA_VOLUME_DOWN)
        )
        // Fails when an action is added without deciding here what it does, rather than silently
        // covering 14 of 15.
        assertEquals(HandlebarAction.entries.toSet(), expected.keys)
        HandlebarAction.entries.forEach { action ->
            assertEquals("plan for ${action.id}", expected[action], planFor(action))
        }
    }

    @Test
    fun `only NONE is inert`() {
        HandlebarAction.entries.forEach { action ->
            val inert = planFor(action) == HandlebarActionPlan.Idle
            assertEquals("inertness of ${action.id}", action == HandlebarAction.NONE, inert)
        }
    }
}

class HandlebarActionMigrationTest {

    @Test
    fun `a binding to the retired previous-panel action becomes the fullscreen map`() {
        // Without V3 this id resolves to nothing, and HandlebarControlStore.action() quietly
        // hands back the gesture's factory default - so a button the rider deliberately gave to
        // the dashboard would come back as a rotary scroll, shown as if he had chosen it.
        assertEquals(
            HandlebarAction.DASH_FULLSCREEN_MAP.id,
            HandlebarControlStore.rewrittenActionId("dashPrevPanel")
        )
    }

    @Test
    fun `nothing else in the preferences file is touched`() {
        // The migration walks every entry rather than the gesture ids, because bindings are also
        // stored per motorcycle as "<gesture>#<id>". That only stays safe while the match is on
        // the one retired value and nothing else - including the non-string entries beside it.
        HandlebarAction.entries.forEach { action ->
            assertNull(HandlebarControlStore.rewrittenActionId(action.id))
        }
        assertNull(HandlebarControlStore.rewrittenActionId("avrcp"))
        assertNull(HandlebarControlStore.rewrittenActionId(true))
        assertNull(HandlebarControlStore.rewrittenActionId(3))
        assertNull(HandlebarControlStore.rewrittenActionId(null))
    }

    @Test
    fun `the retired id really is unreachable`() {
        // If it ever comes back as a live action, the migration above starts rewriting a valid
        // binding into a different one - so this is the assumption V3 rests on.
        assertNull(HandlebarAction.entries.firstOrNull { it.id == "dashPrevPanel" })
    }
}
