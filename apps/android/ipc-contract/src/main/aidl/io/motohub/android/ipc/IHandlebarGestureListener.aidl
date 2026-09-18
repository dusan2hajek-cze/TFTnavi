package io.motohub.android.ipc;

/**
 * Every handlebar gesture CORE recognises during an Android Auto session, as it is recognised.
 *
 * The teaching wizard ("Teach my handlebar") asks the rider to press a button and records which
 * command answered. It reads HandlebarGestureFeed, a plain in-process singleton that CORE's
 * MediaButtonBridge publishes into - so with Android Auto running, the presses are recognised in
 * CORE's process and the wizard, one process away in the companion app, waits forever. Rider
 * 315e0af3 (2026-08-26) ran it to the end with the dash connected and reported that no button
 * ever reached the app; nothing was wrong with his handlebar.
 *
 * gestureId is a HandlebarGesture id, atElapsedRealtimeMillis a SystemClock.elapsedRealtime()
 * stamp - system-wide, so the wizard's "is this press newer than this step?" test survives the
 * crossing unchanged.
 */
oneway interface IHandlebarGestureListener {
    void onHandlebarGesture(String gestureId, long atElapsedRealtimeMillis);
}
