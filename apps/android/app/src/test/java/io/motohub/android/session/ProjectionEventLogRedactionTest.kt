// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionEventLogRedactionTest {
    @Test
    fun redactsPasswordKeyValuePairs() {
        assertEquals(
            "Connecting with password=<redacted> to SSID CFMOTO-1234",
            ProjectionEventLog.redact("Connecting with password=Sup3rSecret to SSID CFMOTO-1234")
        )
    }

    @Test
    fun redactsPskAndPassphraseSynonyms() {
        assertFalse(ProjectionEventLog.redact("psk: abc123def").contains("abc123def"))
        assertFalse(ProjectionEventLog.redact("passphrase=hunter2!!").contains("hunter2"))
    }

    @Test
    fun redactsIpv4AddressesInsideFreeText() {
        val redacted = ProjectionEventLog.redact(
            "java.net.ConnectException: failed to connect to /192.168.49.1 (port 7788)"
        )
        assertFalse(redacted.contains("192.168.49.1"))
        assertEquals(
            "java.net.ConnectException: failed to connect to /<redacted-ip> (port 7788)",
            redacted
        )
    }

    @Test
    fun redactsColonAndHyphenSeparatedMacAddresses() {
        assertFalse(ProjectionEventLog.redact("Peer MAC AA:BB:CC:DD:EE:FF found").contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(ProjectionEventLog.redact("Peer MAC aa-bb-cc-dd-ee-ff found").contains("aa-bb-cc-dd-ee-ff"))
    }

    @Test
    fun doesNotMangleVersionOrResolutionStrings() {
        val message = "MOTO-HUB 0.9.0-beta.10 negotiated 1280x720@30fps"
        assertEquals(message, ProjectionEventLog.redact(message))
    }

    /**
     * The dashboard identity line, which is four-part version numbers and nothing else. Field log
     * 90438e1e (2026-08-25) carried it as `sdk=<redacted-ip>`: the only line that says which
     * firmware a rider has, with the firmware scrubbed out of it as if it were an address.
     */
    @Test
    fun keepsAFourPartVersionThatIsAnnouncedAsOne() {
        val message =
            "Unrecognised dashboard: package=?, version=V0.0.1(?), sdk=1.0.13.1, pxc=1.0.2.3"

        assertEquals(message, ProjectionEventLog.redact(message))
    }

    @Test
    fun keepsAFourPartVersionInsideAQuotedJsonDump() {
        val redacted = ProjectionEventLog.redact("""{"sdkVersion":"1.0.13.1","pxcVersion":"1.0.2"}""")

        assertTrue(redacted.contains("1.0.13.1"))
    }

    /** The sparing rule must not become a way to smuggle an address through the scrub. */
    @Test
    fun stillRedactsAddressesAlongsideASparedVersion() {
        val redacted = ProjectionEventLog.redact(
            "sdk=1.0.13.1 connected to 192.168.49.1 from 192.168.49.37"
        )

        assertEquals("sdk=1.0.13.1 connected to <redacted-ip> from <redacted-ip>", redacted)
    }

    @Test
    fun stillRedactsAnAddressThatNoKeyExcuses() {
        val redacted = ProjectionEventLog.redact("phone=10.0.0.4, dash=10.0.0.1")

        assertFalse(redacted.contains("10.0.0."))
    }

    @Test
    fun redactsTheDashboardsStableIdentifiersInARawClientInfoDump() {
        // Verbose T-Box logging is on by default, so the raw CLIENT_INFO JSON reaches logs
        // that riders paste into public threads - HUID and uuid identify the dash forever
        // and must not survive. This redaction is the precondition of that default.
        val redacted = ProjectionEventLog.redact(
            """{"HUID": "8f4a2b1c9d3e", "uuid": "c1a2b3d4e5f6", "flavor": "65536"}"""
        )
        assertFalse(redacted.contains("8f4a2b1c9d3e"))
        assertFalse(redacted.contains("c1a2b3d4e5f6"))
        // The brand flavor is the diagnostic payload - it has to survive the scrub.
        assertTrue(redacted.contains("65536"))
    }

    /**
     * The AI-provider key rules used to exist only in the companion edition's copy of this
     * shared file. A key is worth real money if it leaks, and "which edition am I in?" is not
     * a question a redaction rule should have an answer to - both editions carry them now.
     */
    @Test
    fun redactsAiProviderKeysWhateverShapeTheyArriveIn() {
        assertFalse(ProjectionEventLog.redact("api_key=sk-abcdefghijklmnop").contains("abcdefghijklmnop"))
        assertFalse(ProjectionEventLog.redact("""{"apiKey": "xyz123456789"}""").contains("xyz123456789"))
        assertFalse(ProjectionEventLog.redact("access_token: ya29.A0AfH6SMB").contains("ya29"))
    }

    @Test
    fun redactsABearerTokenPastTheFirstSpace() {
        // SECRET_PATTERN stops at the first space, so without the Bearer rule this used to
        // redact the word "Bearer" and leave the token itself in the clear.
        val redacted = ProjectionEventLog.redact(
            "HTTP 401 for Authorization: Bearer sk-proj-AbCdEf0123456789xyz"
        )
        assertFalse(redacted.contains("sk-proj-AbCdEf0123456789xyz"))
        assertFalse(redacted.contains("AbCdEf0123456789"))
    }

    @Test
    fun redactsABareKeyLiteralWithNoLabelAtAll() {
        val redacted = ProjectionEventLog.redact("request failed using sk-or-v1-0123456789abcdef")
        assertFalse(redacted.contains("sk-or-v1-0123456789abcdef"))
        assertTrue(redacted.contains("<redacted-key>"))
    }

    // ── external (unlevelled) message classification ─────────────────────────────────────────

    @Test
    fun classifiesRealFaultsAsErrors() {
        assertEquals(LogLevel.ERROR, classifyExternalMessage("[AA] failed to bind :5288 — EADDRINUSE"))
        assertEquals(LogLevel.ERROR, classifyExternalMessage("[AA] handshake timed out after 10s"))
        assertEquals(LogLevel.ERROR, classifyExternalMessage("[BTN] unable to create AVRCP bridge"))
    }

    /**
     * The line that made this worth fixing: a counter at rest reads as a fault to a word
     * matcher, and every one of these used to be displayed red AND raise a Sentry event on a
     * completely healthy session.
     */
    @Test
    fun aCounterAtZeroIsNotAFault() {
        assertEquals(LogLevel.INFO, classifyExternalMessage("Frames dropped: 0"))
        assertEquals(LogLevel.INFO, classifyExternalMessage("frameTimeouts=0, frameRejections=0"))
        assertEquals(LogLevel.INFO, classifyExternalMessage("0 errors so far"))
    }

    @Test
    fun anOrdinaryTeardownIsNotAFault() {
        // Stopping the receiver closes its sockets; the stack reports that in the vocabulary
        // of failure, and it is not one.
        assertEquals(LogLevel.WARNING, classifyExternalMessage("[AA] accept ended: Socket closed"))
        assertEquals(LogLevel.WARNING, classifyExternalMessage("[AA] read failed: stream closed"))
    }

    @Test
    fun degradedButAliveStaysAWarning() {
        assertEquals(LogLevel.WARNING, classifyExternalMessage("[BTN] key=23 dropped; input not ready"))
        assertEquals(LogLevel.WARNING, classifyExternalMessage("EasyConn attempt 1/3 — retrying in 500ms"))
    }

    @Test
    fun ordinaryProgressStaysInformational() {
        assertEquals(LogLevel.INFO, classifyExternalMessage("[AA] WirelessServer listening on :5288"))
        assertEquals(LogLevel.INFO, classifyExternalMessage("[AA] first decoded video frame received"))
    }

    /**
     * AaLog writes every level through Log.i and carries the real one as a `W: `/`E: ` marker.
     * Reading it is the difference between the console showing rider 4d8a4c5b's 2fps collapse and
     * showing him a clean bill of health: not one of these lines contains a fault keyword.
     */
    @Test
    fun aDeclaredLevelIsUsedInsteadOfGuessing() {
        assertEquals(
            LogLevel.WARNING,
            classifyExternalMessage(
                "[AA] W: Decoder stall detected (no output for 9201ms, input 6ms ago). Forcing restart."
            )
        )
        assertEquals(LogLevel.WARNING, classifyExternalMessage("[AA] W: Decoder restart requested: sync_stall"))
        assertEquals(
            LogLevel.WARNING,
            classifyExternalMessage("[AA] W: AapTransport: Requesting recovery keyframe via focus cycle.")
        )
        assertEquals(LogLevel.ERROR, classifyExternalMessage("[AA] E: Decrypted payload too short: 3"))
        assertEquals(LogLevel.ERROR, classifyExternalMessage("[AA] E: No connection."))
    }

    @Test
    fun aDeclaredLevelStillLosesToTheTwoDemotions() {
        // A counter at rest stays a non-event however loudly it is announced...
        assertEquals(LogLevel.INFO, classifyExternalMessage("[AA] E: frames dropped: 0"))
        // ...and an ordinary teardown is not an ERROR just because the writer said so.
        assertEquals(LogLevel.WARNING, classifyExternalMessage("[AA] E: AapRead: read failed: socket closed"))
        // A teardown a writer already called a warning is not promoted by the demotion either.
        assertEquals(LogLevel.WARNING, classifyExternalMessage("[AA] W: accept ended: Socket closed"))
    }

    /**
     * The marker is a prefix, not a substring: AAP messages carry protobuf and buffer dumps, and
     * `... lim=25] w: 3` inside one of those declares nothing.
     */
    @Test
    fun onlyAnAnchoredMarkerCounts() {
        assertEquals(
            LogLevel.INFO,
            classifyExternalMessage("Config response: status: HEADUNIT [max_unacked: 12] w: 800")
        )
        assertEquals(LogLevel.INFO, classifyExternalMessage("[AA] Output Format Changed: {crop-right=799, w: 800}"))
        // A tag is stripped, but only one, and only when the message opens with it.
        assertEquals(LogLevel.WARNING, classifyExternalMessage("[AA] W: AapRead: FIFO overflow! Size: 4096"))
    }
}
