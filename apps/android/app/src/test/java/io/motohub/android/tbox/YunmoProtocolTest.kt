// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YunmoProtocolTest {

    @Test
    fun encodeSimpleFramesTheCommandWithLengthAndChecksum() {
        val frame = YunmoProtocol.encodeSimple(YunmoProtocol.CMD_DISPLAY, byteArrayOf(7))
        // FE FE FE FE | A0 | 00 01 | (00+01)&FF | 07 | checksum(sum of bytes[4..9))
        val expectedChecksum = (0xA0 + 0x00 + 0x01 + 0x01 + 0x07) and 0xFF
        assertArrayEquals(
            byteArrayOf(
                0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(),
                0xA0.toByte(), 0x00, 0x01, 0x01, 0x07, expectedChecksum.toByte()
            ),
            frame
        )
    }

    @Test
    fun theHandshakeFramesMatchTheReferenceOpcodes() {
        assertArrayEquals(
            YunmoProtocol.encodeSimple(YunmoProtocol.CMD_DISPLAY_ALT, byteArrayOf(1, 0, 1)),
            YunmoProtocol.dimQueryFrame()
        )
        val start = YunmoProtocol.startMirrorFrames()
        assertEquals(YunmoProtocol.CMD_DISPLAY_ALT, start[0][4].toInt() and 0xFF)
        assertEquals(7, start[0][8].toInt())
        assertEquals(YunmoProtocol.CMD_DISPLAY, start[1][4].toInt() and 0xFF)
        assertEquals(7, start[1][8].toInt())
        // The OEM app's sendExitDisplay writes A0{2} then A0{5}; an earlier guess used 3 here.
        val stop = YunmoProtocol.stopFrames()
        assertEquals(2, stop[0][8].toInt())
        assertEquals(5, stop[1][8].toInt())
        assertEquals(6, YunmoProtocol.mapNaviFrame()[8].toInt())
    }

    @Test
    fun encodeH264ExPadsToThirtyTwoAndKeepsMetadataOff() {
        val accessUnit = ByteArray(10) { 1 }
        val frame = YunmoProtocol.encodeH264Ex(accessUnit, width = 1024, height = 464, frameId = 9)

        // padded = ceil(10/32)*32 = 32; total = 32 + 40 header
        assertEquals(72, frame.size)
        assertEquals(0x1D, frame[4].toInt() and 0xFF)
        // blocks = (32 + 32) / 32 = 2, big-endian in [5..6], checksum in [7]
        assertEquals(0, frame[5].toInt())
        assertEquals(2, frame[6].toInt())
        assertEquals(2, frame[7].toInt())
        // media type LEGACY at [15]
        assertEquals(YunmoProtocol.MEDIA_TYPE_LEGACY, frame[15].toInt())
        // real length little-endian at [8..11]
        assertEquals(10, frame[8].toInt())
        assertEquals(0, frame[9].toInt())
        // AU byte checksum little-endian at [12..13] = 10
        assertEquals(10, frame[12].toInt())
        assertEquals(0, frame[13].toInt())
        // metadata block [16..24] stays zero because omitMeta defaults true
        for (i in 16..24) assertEquals("byte $i", 0, frame[i].toInt())
        // access unit copied verbatim at offset 40
        assertArrayEquals(accessUnit, frame.copyOfRange(40, 50))
    }

    @Test
    fun encodeH264ExWritesMetadataWhenAskedTo() {
        val frame = YunmoProtocol.encodeH264Ex(
            ByteArray(4) { 0 }, width = 0x0201, height = 0x0403, frameId = 0x0A, omitMeta = false
        )
        // frameId LE [16..19]
        assertEquals(0x0A, frame[16].toInt())
        // width LE [20..21]
        assertEquals(0x01, frame[20].toInt())
        assertEquals(0x02, frame[21].toInt())
        // height LE [22..23]
        assertEquals(0x03, frame[22].toInt())
        assertEquals(0x04, frame[23].toInt())
    }

    @Test
    fun parseOkDimensionReadsTheRealPanelSizeBigEndianWithoutDoubling() {
        // A dimension reply captured from a real X-Cape 1200. This test used to assert a 512x232
        // report doubling to the 1024x464 the owner measured on the OEM display; the capture shows
        // the dash reports that 1024x464 itself, so doubling asked for twice the panel.
        val payload = byteArrayOf(0, 0, 0, 0, 0x04, 0x00, 0x01, 0xD0.toByte())
        val report = YunmoProtocol.parseOkDimension(payload)!!
        assertEquals(1024, report.reportedWidth)
        assertEquals(464, report.reportedHeight)

        val (w, h) = YunmoProtocol.encodeCanvas(report, fallbackWidth = 800, fallbackHeight = 480)
        assertEquals(1024, w)
        assertEquals(464, h)
    }

    @Test
    fun encodeCanvasNeverExceedsWhatTheDashReportedAndFallsBackWhenItIsSilent() {
        // Odd axes round DOWN: overshooting the panel is the failure being guarded against, so an
        // odd height loses a line rather than gaining fifteen to reach a multiple of 16.
        val report = YunmoProtocol.DimensionReport(301, 151)
        val (w, h) = YunmoProtocol.encodeCanvas(report, 800, 480)
        assertEquals(300, w)
        assertEquals(150, h)

        val (fw, fh) = YunmoProtocol.encodeCanvas(null, 800, 480)
        assertEquals(800, fw)
        assertEquals(480, fh)
    }

    @Test
    fun parseOkDimensionRejectsShortOrOutOfRangePayloads() {
        assertNull(YunmoProtocol.parseOkDimension(ByteArray(4)))
        // width 0 is below the floor
        assertNull(YunmoProtocol.parseOkDimension(byteArrayOf(0, 0, 0, 0, 0, 0, 0x01, 0x00)))
    }

    @Test
    fun parseAckReadsLittleEndianFrameIdAndRejectsNonAcks() {
        assertEquals(0x04030201, YunmoProtocol.parseAck(byteArrayOf(0, 1, 2, 3, 4)))
        // A map-nav confirm (payload[0] == 6) is not an ack.
        assertNull(YunmoProtocol.parseAck(byteArrayOf(6)))
        assertTrue(YunmoProtocol.isMapNaviConfirm(byteArrayOf(6)))
    }

    @Test
    fun readSimpleFrameRoundTripsAnEncodedFrame() {
        val encoded = YunmoProtocol.encodeSimple(YunmoProtocol.CMD_OK_A, byteArrayOf(9, 8, 7))
        val frame = YunmoProtocol.readSimpleFrame(ByteArrayInputStream(encoded))!!
        assertEquals(YunmoProtocol.CMD_OK_A, frame.command)
        assertArrayEquals(byteArrayOf(9, 8, 7), frame.payload)
    }

    @Test
    fun readSimpleFrameResyncsPastLeadingGarbage() {
        val encoded = YunmoProtocol.encodeSimple(YunmoProtocol.CMD_DISPLAY, byteArrayOf(0, 1, 2, 3, 4))
        val noisy = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55) + encoded
        val frame = YunmoProtocol.readSimpleFrame(ByteArrayInputStream(noisy))!!
        assertEquals(YunmoProtocol.CMD_DISPLAY, frame.command)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4), frame.payload)
    }

    @Test
    fun readSimpleFrameRejectsACorruptChecksum() {
        val encoded = YunmoProtocol.encodeSimple(YunmoProtocol.CMD_OK_A, byteArrayOf(1, 2, 3))
        encoded[encoded.size - 1] = (encoded[encoded.size - 1] + 1).toByte()
        assertNull(YunmoProtocol.readSimpleFrame(ByteArrayInputStream(encoded)))
    }

    @Test
    fun annexBFromAvccRewritesLengthPrefixesToStartCodes() {
        // One AVCC NAL: 4-byte big-endian length 3, then payload 67 01 02 (SPS).
        val avcc = byteArrayOf(0, 0, 0, 3, 0x67, 0x01, 0x02)
        val annexB = YunmoProtocol.annexBFromAvcc(avcc)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 0x01, 0x02), annexB)
        val nals = YunmoProtocol.splitAnnexB(annexB)
        assertEquals(1, nals.size)
        assertEquals(YunmoProtocol.NAL_SPS, nals[0].type)
    }

    @Test
    fun hexRendersControlPayloadsAndTruncatesLongOnes() {
        // The shape an unrecognised A0 frame is logged in, e.g. the OEM's `a=160 b=8`.
        assertEquals("08", YunmoProtocol.hex(byteArrayOf(8)))
        assertEquals("00 04 00 01 d0", YunmoProtocol.hex(byteArrayOf(0, 4, 0, 1, 0xD0.toByte())))
        assertEquals("00 01 … (3b)", YunmoProtocol.hex(byteArrayOf(0, 1, 2), max = 2))
    }

    @Test
    fun nalNameLabelsTheTypesThePhaseLogReports() {
        assertEquals("SPS", YunmoProtocol.nalName(YunmoProtocol.NAL_SPS))
        assertEquals("PPS", YunmoProtocol.nalName(YunmoProtocol.NAL_PPS))
        assertEquals("IDR", YunmoProtocol.nalName(YunmoProtocol.NAL_IDR))
        assertEquals("P", YunmoProtocol.nalName(YunmoProtocol.NAL_P))
        assertEquals("NAL9", YunmoProtocol.nalName(9))
    }

    @Test
    fun stripLeadingSpsPpsKeepsOnlyTheCodedPicture() {
        val sc = byteArrayOf(0, 0, 0, 1)
        val sps = sc + byteArrayOf(0x67, 0xAA.toByte())
        val pps = sc + byteArrayOf(0x68, 0xBB.toByte())
        val idr = sc + byteArrayOf(0x65, 0xCC.toByte())
        val keyframe = sps + pps + idr

        assertTrue(YunmoProtocol.annexBContainsNal(keyframe, YunmoProtocol.NAL_IDR))
        assertArrayEquals(idr, YunmoProtocol.stripLeadingSpsPps(keyframe))
    }

    @Test
    fun displayOpcodesTellApartMapNavConfirmSimpleNaviAndAnAck() {
        // cmd=6: the dash is on the full-screen map and will paint what we push.
        assertTrue(YunmoProtocol.isMapNaviConfirm(byteArrayOf(6)))
        assertFalse(YunmoProtocol.isSimpleNaviSwitch(byteArrayOf(6)))

        // cmd=5 inbound: the dash draws its own arrows and discards our frames. The same value is
        // the second half of our outbound teardown pair, so only the direction separates them.
        assertTrue(YunmoProtocol.isSimpleNaviSwitch(byteArrayOf(5)))
        assertFalse(YunmoProtocol.isMapNaviConfirm(byteArrayOf(5)))
        assertEquals(YunmoProtocol.DISP_EXIT_B, YunmoProtocol.DISP_SIMPLE_NAVI)

        // payload[0]==0 is an ack carrying a little-endian frame id, not a mode at all.
        val ack = byteArrayOf(0, 0x2A, 0, 0, 0)
        assertFalse(YunmoProtocol.isMapNaviConfirm(ack))
        assertFalse(YunmoProtocol.isSimpleNaviSwitch(ack))
        assertEquals(42, YunmoProtocol.parseAck(ack))

        // An empty payload must not be read as any of them.
        assertFalse(YunmoProtocol.isMapNaviConfirm(ByteArray(0)))
        assertFalse(YunmoProtocol.isSimpleNaviSwitch(ByteArray(0)))
    }

    @Test
    fun encodeJpegExWritesTypeZeroAndARealFrameId() {
        val jpeg = ByteArray(10) { 3 }
        val frame = YunmoProtocol.encodeJpegEx(jpeg, frameId = 0x01020304)

        // Envelope identical to the H.264 form: padded to 32, 40-byte header, block count in [5..6].
        assertEquals(72, frame.size)
        assertEquals(0x1D, frame[4].toInt() and 0xFF)
        assertEquals(0, frame[5].toInt())
        assertEquals(2, frame[6].toInt())
        assertEquals(2, frame[7].toInt())

        // The two bytes that differ, and the whole reason this path exists.
        assertEquals("media type must be 0 (JPEG), not the H.264 legacy 2", 0, frame[15].toInt())
        assertEquals(0x04, frame[16].toInt())
        assertEquals(0x03, frame[17].toInt())
        assertEquals(0x02, frame[18].toInt())
        assertEquals(0x01, frame[19].toInt())

        // Length and checksum still belong to the transport layer, unchanged.
        assertEquals(10, frame[8].toInt())
        assertEquals(30, frame[12].toInt())
        assertArrayEquals(jpeg, frame.copyOfRange(40, 50))
    }

    @Test
    fun theJpegFrameIdIsTheOnlyLivenessSignalSoItMustNotBeZeroed() {
        // The H.264 path leaves the id zero, which is why every ack on that path reports frame 0
        // and why neither implementation has ever had a usable sign of life from this dash. If this
        // ever regresses to writing zero, that signal is gone again.
        val frame = YunmoProtocol.encodeJpegEx(ByteArray(4), frameId = 7)
        assertEquals(7, frame[16].toInt())
        assertEquals(YunmoProtocol.MEDIA_TYPE_JPEG, frame[15].toInt())
        assertTrue(YunmoProtocol.MEDIA_TYPE_JPEG != YunmoProtocol.MEDIA_TYPE_LEGACY)
    }

    @Test
    fun nothingIsInFlightBeforeTheFirstStillOrAfterItIsAcked() {
        assertEquals(0, YunmoProtocol.stillsInFlight(lastOfferedId = -1, lastAckedId = -1))
        assertEquals(1, YunmoProtocol.stillsInFlight(lastOfferedId = 0, lastAckedId = -1))
        assertEquals(0, YunmoProtocol.stillsInFlight(lastOfferedId = 0, lastAckedId = 0))
        assertEquals(2, YunmoProtocol.stillsInFlight(lastOfferedId = 9, lastAckedId = 7))
    }

    @Test
    fun theStillWindowClosesOnADashThatFallsBehind() {
        // The regression that put fifteen seconds of delay on a rider's dash: read a counter the
        // stills path never advances and this is negative from the first ack on, so the window
        // never closes, the sender never yields, and the backlog moves into the socket buffers
        // where nothing can drop it. In flight must GROW as the dash falls behind.
        var inFlight = 0
        for (offered in 0..40) {
            inFlight = YunmoProtocol.stillsInFlight(lastOfferedId = offered, lastAckedId = 3)
        }
        assertTrue(
            "a dash stuck at ack 3 must look further and further behind, not caught up",
            inFlight >= YunmoProtocol.STILL_SEND_WINDOW
        )
    }

    @Test
    fun latestWinsGapsDoNotStrandTheWindow() {
        // The sender replaces a still that never reached the socket, so ids skip. Measuring the
        // distance to the newest id (rather than counting frames sent) means an ack for that
        // newest id still empties the window.
        assertEquals(0, YunmoProtocol.stillsInFlight(lastOfferedId = 120, lastAckedId = 120))
    }

    /**
     * A watchdog recovery rebuilds the connection but not the sender's id counter, so the first
     * still of the new connection can carry any id. The transport anchors a floor at
     * first-offer-minus-one and clamps lastAcked to it until the dash's first ack arrives -
     * without that, id 10 into a fresh connection read as eleven stills in flight against a
     * window of two, and every offer was refused (X-Cape 1200 field log, 2026-08-24).
     */
    @Test
    fun `an inherited still id does not jam a fresh connection's window`() {
        val floor = 10 - 1 // set on the first offer, before any ack
        assertEquals(1, YunmoProtocol.stillsInFlight(lastOfferedId = 10, lastAckedId = maxOf(-1, floor)))
        assertEquals(2, YunmoProtocol.stillsInFlight(lastOfferedId = 11, lastAckedId = maxOf(-1, floor)))
        // The first real ack takes over from the floor.
        assertEquals(1, YunmoProtocol.stillsInFlight(lastOfferedId = 11, lastAckedId = maxOf(10, floor)))
    }
}
