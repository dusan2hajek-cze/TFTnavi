// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The BLE side of a ThinkerRide session: finds the dash by its GATT service, opens the link,
 * runs the JSON handshake and keeps the 5s heartbeat alive. TCP stays in [ThinkerRideTransport];
 * nothing here touches sockets.
 *
 * Android only allows **one** GATT operation in flight per connection, even for
 * write-without-response: a second call while the first is outstanding comes straight back as
 * [BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY] and the payload is never transmitted.
 * Firing writes on a timer therefore loses packets silently — the CCCD subscribe alone was enough
 * to swallow `get_pairinfo` on every session ever logged. So every command goes through
 * [enqueue]: one packet in flight, the next one released only once the stack reports the previous
 * one done, [ThinkerRideProtocol.BLE_WRITE_SPACING_MS] later because the dash firmware also drops
 * back-to-back packets. A busy stack is retried rather than dropped, and a completion callback
 * that never arrives is bounded by [WRITE_CALLBACK_TIMEOUT_MS] so the queue can never wedge.
 *
 * All Bluetooth calls are wrapped against [SecurityException] because the runtime grant can be
 * revoked mid-session; a revoked permission surfaces as [onLinkLost], never a crash.
 */
@SuppressLint("MissingPermission")
internal class ThinkerRideBleLink(
    context: Context,
    private val log: (String) -> Unit,
    private val onLinkLost: (String) -> Unit,
    /**
     * Whether this dash's profile wants the OEM's 104-byte byteCat envelope instead of bare
     * JSON. A lambda rather than a value because the session is built before the caller has
     * necessarily configured the profile, and getting the framing from a stale read would be
     * silent: an unframed write is not rejected, it is simply never parsed.
     */
    private val useByteCatFraming: () -> Boolean = { false }
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val serviceUuid = UUID.fromString(ThinkerRideProtocol.BLE_SERVICE_UUID)
    private val writeUuid = UUID.fromString(ThinkerRideProtocol.BLE_WRITE_CHARACTERISTIC_UUID)
    private val notifyUuid = UUID.fromString(ThinkerRideProtocol.BLE_NOTIFY_CHARACTERISTIC_UUID)
    private val cccUuid = UUID.fromString(ThinkerRideProtocol.BLE_CCC_DESCRIPTOR_UUID)

    private val scheduler = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "MotoHubThinkerRideBle").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var heartbeat: ScheduledFuture<*>? = null

    @Volatile
    private var scanStop: ScheduledFuture<*>? = null

    private val closed = AtomicBoolean(false)
    private val handshakeStarted = AtomicBoolean(false)
    private val discoveryStarted = AtomicBoolean(false)
    private val ready = CompletableDeferred<Result<String>>()

    /** Notifications are fragments, not messages; this puts them back together. */
    private val notifyAssembler = ThinkerRideProtocol.NotifyAssembler()

    /**
     * One GATT write: the bytes that go on the wire plus the JSON they came from, which is what
     * every log line and the heartbeat's already-queued check are really about. Compared by
     * identity everywhere - two frames of one message, or two heartbeats, are equal by value.
     */
    private class Packet(val label: String, val bytes: ByteArray)

    /** Guards [writeQueue], [inFlight], [inFlightAttempts], [writeGeneration], [writeWatchdog]. */
    private val queueLock = Any()
    private val writeQueue = ArrayDeque<Packet>()
    private var inFlight: Packet? = null
    private var inFlightAttempts = 0

    /**
     * byteCat sequence numbers, contiguous from 0 per connection: the dash's loss detector reads
     * the run, and restarting it mid-session makes it ask for retransmits of frames that were
     * never sent.
     */
    private val byteCatSequence = AtomicInteger(0)
    private var writeGeneration = 0L
    private var writeWatchdog: ScheduledFuture<*>? = null

    /** Completed once the dash acknowledges pairing (`send_pairresult` = 1) over notify. */
    private val pairConfirmation = CompletableDeferred<Unit>()

    /** [android.os.SystemClock.elapsedRealtime] of the last confirmation, or 0 if never. */
    @Volatile
    private var pairConfirmedAtElapsed = 0L

    /**
     * Scans for the dash, connects, subscribes to notifications, sends the opening handshake and
     * starts the heartbeat. Resolves with the peripheral name once commands can be written.
     */
    suspend fun connect(timeoutMillis: Long): Result<String> {
        val adapter = bluetoothManager?.adapter
            ?: return Result.failure(IllegalStateException("This phone has no Bluetooth adapter."))
        if (!isEnabled()) {
            return Result.failure(
                IllegalStateException("Bluetooth is off. This dashboard pairs over Bluetooth; turn it on and connect again.")
            )
        }
        val scanner = adapter.bluetoothLeScanner
            ?: return Result.failure(IllegalStateException("Bluetooth LE scanning is unavailable on this phone."))

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (closed.get() || gatt != null) return
                val name = runCatching { result.device.name }.getOrNull() ?: "ThinkerRide dashboard"
                log("BLE dashboard found: $name (${result.device.address}); connecting.")
                stopScan(scanner, this)
                openGatt(result, name)
            }

            override fun onScanFailed(errorCode: Int) {
                completeReady(Result.failure(IllegalStateException("Bluetooth scan failed (code $errorCode).")))
            }
        }

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val started = runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
        started.exceptionOrNull()?.let { failure ->
            return Result.failure(
                if (failure is SecurityException) missingPermissionFailure() else failure
            )
        }
        log("Scanning for the dashboard's Bluetooth service ($serviceUuid).")

        scanStop = schedule(timeoutMillis) {
            if (!ready.isCompleted && gatt == null) {
                stopScan(scanner, scanCallback)
                completeReady(
                    Result.failure(
                        IllegalStateException(
                            "No ThinkerRide dashboard was found over Bluetooth. Make sure the " +
                                "dash is on its phone-connection screen and within range."
                        )
                    )
                )
            }
        }

        val outcome = ready.await()
        if (outcome.isFailure) {
            stopScan(scanner, scanCallback)
            close()
        }
        return outcome
    }

    /**
     * Suspends until the dash confirms pairing, or [timeoutMillis] elapses. The dash only honours
     * a mirror-start once its own pairing handshake has landed, so [ThinkerRideTransport] waits on
     * this before asking for projection.
     */
    suspend fun awaitPairConfirmation(timeoutMillis: Long): Boolean {
        // An already-settled deferred resolves without suspending, so this needs no fast path:
        // confirmed returns true immediately, a closed link throws and returns false.
        return withTimeoutOrNull(timeoutMillis) {
            try {
                pairConfirmation.await()
                true
            } catch (_: IllegalStateException) {
                false
            }
        } == true
    }

    /**
     * How long ago the dash last confirmed pairing, or [Long.MAX_VALUE] if it never has. Logged
     * next to every mirror-start: if a dash ever turns out to care how closely the two follow
     * each other, this is the number that will show it.
     */
    fun millisSincePairConfirmation(): Long {
        val confirmedAt = pairConfirmedAtElapsed
        return if (confirmedAt == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - confirmedAt
    }

    /** Sends the projection start/stop pair; safe to call from any thread. */
    fun sendMirrorStatus(active: Boolean) {
        ThinkerRideProtocol.bleMirrorStatusPackets(active).forEach { enqueue(it) }
    }

    /**
     * Suspends until every queued command has left the phone, so a teardown does not close the
     * link out from under the mirror-stop packets.
     */
    suspend fun awaitWritesDrained(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            while (synchronized(queueLock) { inFlight != null || writeQueue.isNotEmpty() }) {
                delay(WRITE_DRAIN_POLL_MS)
            }
            true
        } == true

    fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        heartbeat?.cancel(false)
        scanStop?.cancel(false)
        synchronized(queueLock) {
            writeQueue.clear()
            inFlight = null
            writeWatchdog?.cancel(false)
            writeWatchdog = null
        }
        val activeGatt = gatt
        gatt = null
        writeCharacteristic = null
        runCatching {
            activeGatt?.disconnect()
            activeGatt?.close()
        }
        scheduler.shutdownNow()
        pairConfirmation.completeExceptionally(IllegalStateException("Bluetooth link closed."))
        completeReady(Result.failure(IllegalStateException("Bluetooth link closed.")))
    }

    private fun isEnabled(): Boolean =
        runCatching { bluetoothManager?.adapter?.isEnabled == true }.getOrDefault(false)

    private fun stopScan(scanner: android.bluetooth.le.BluetoothLeScanner, callback: ScanCallback) {
        runCatching { scanner.stopScan(callback) }
    }

    private fun openGatt(result: ScanResult, name: String) {
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(connectedGatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        // Ask for a bigger MTU before anything else: at the 23-byte default the
                        // dash's JSON arrives in 20-byte fragments. Only one GATT operation may
                        // be in flight, so service discovery waits for the answer (or for the
                        // fallback below when the stack never calls onMtuChanged).
                        val requested = runCatching {
                            connectedGatt.requestMtu(ThinkerRideProtocol.BLE_PREFERRED_MTU)
                        }.getOrDefault(false)
                        if (requested) {
                            schedule(MTU_TIMEOUT_MS) { startDiscoveryOnce(connectedGatt) }
                        } else {
                            startDiscoveryOnce(connectedGatt)
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (closed.get()) return
                        if (!ready.isCompleted) {
                            completeReady(
                                Result.failure(IllegalStateException("The dashboard closed the Bluetooth link (status $status)."))
                            )
                        } else {
                            onLinkLost("The dashboard closed the Bluetooth link (status $status).")
                        }
                    }
                }
            }

            override fun onMtuChanged(connectedGatt: BluetoothGatt, mtu: Int, status: Int) {
                log("BLE MTU is now $mtu bytes (status $status).")
                startDiscoveryOnce(connectedGatt)
            }

            override fun onServicesDiscovered(connectedGatt: BluetoothGatt, status: Int) {
                val service = connectedGatt.getService(serviceUuid)
                val write = service?.getCharacteristic(writeUuid)
                if (service == null || write == null) {
                    completeReady(
                        Result.failure(IllegalStateException("The dashboard does not expose the ThinkerRide mirroring service."))
                    )
                    return
                }
                writeCharacteristic = write
                completeReady(Result.success(name))

                // Subscribing is itself a GATT write. The handshake has to wait for it to land or
                // its first packet comes back BUSY and is lost — which is exactly how every
                // session used to lose get_pairinfo.
                val subscribing = subscribeToNotifications(connectedGatt, service)
                if (subscribing) {
                    log("Mirroring service ready; enabling dashboard notifications.")
                    schedule(DESCRIPTOR_TIMEOUT_MS) {
                        if (handshakeStarted.get()) return@schedule
                        log("Notification subscription did not complete in ${DESCRIPTOR_TIMEOUT_MS}ms; sending the handshake anyway.")
                        startHandshakeOnce()
                    }
                } else {
                    log("Mirroring service ready; this dash exposes no notify channel.")
                    startHandshakeOnce()
                }
            }

            override fun onDescriptorWrite(
                connectedGatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                if (descriptor.uuid != cccUuid) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("Enabling dashboard notifications failed (GATT status $status); continuing without them.")
                }
                startHandshakeOnce()
            }

            override fun onCharacteristicWrite(
                connectedGatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (characteristic.uuid != writeUuid) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("The dashboard rejected a Bluetooth command (GATT status $status).")
                }
                finishInFlight()
            }

            /**
             * The pre-33 notification callback, which is the only one Android 12 has.
             *
             * The value-carrying overload below is API 33. Overriding just that one compiles
             * against compileSdk 36 and then never fires on a minSdk-31 phone, because the
             * framework class there has no such method to dispatch to - the subscription
             * succeeds and not one notification is ever delivered. On 33+ this is dead code:
             * the platform's own value-carrying default is what forwards to this shape, and
             * overriding it replaces that forwarding, so there is no double delivery.
             */
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                connectedGatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                val value = characteristic.value ?: return
                onCharacteristicChanged(connectedGatt, characteristic, value)
            }

            override fun onCharacteristicChanged(
                connectedGatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid != notifyUuid) return
                // One notification is not one message: reassemble first, then act.
                notifyAssembler.accept(value.toString(StandardCharsets.UTF_8)).forEach { message ->
                    log("Dash -> BLE: $message")
                    if (ThinkerRideProtocol.isPairConfirmation(message)) {
                        pairConfirmedAtElapsed = SystemClock.elapsedRealtime()
                        if (pairConfirmation.complete(Unit)) {
                            log("Dashboard confirmed Bluetooth pairing (send_pairresult=1).")
                        }
                    }
                }
            }
        }
        val opened = runCatching {
            result.device.connectGatt(appContext, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        }
        gatt = opened.getOrNull()
        if (gatt == null) {
            completeReady(
                Result.failure(opened.exceptionOrNull() ?: IllegalStateException("Unable to open the Bluetooth link."))
            )
        }
    }

    /** Discovery is reached from both the MTU answer and its fallback timer; run it once. */
    private fun startDiscoveryOnce(connectedGatt: BluetoothGatt) {
        if (closed.get() || !discoveryStarted.compareAndSet(false, true)) return
        log("BLE connected; discovering the mirroring service.")
        runCatching { connectedGatt.discoverServices() }
    }

    /** True when a CCCD write was actually submitted, so [onDescriptorWrite] is coming. */
    private fun subscribeToNotifications(
        connectedGatt: BluetoothGatt,
        service: android.bluetooth.BluetoothGattService
    ): Boolean {
        val notify = service.getCharacteristic(notifyUuid) ?: return false
        return runCatching {
            connectedGatt.setCharacteristicNotification(notify, true)
            val descriptor = notify.getDescriptor(cccUuid) ?: return@runCatching false
            BleCompat.writeDescriptor(
                connectedGatt,
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            ) == BluetoothStatusCodes.SUCCESS
        }.getOrDefault(false)
    }

    private fun startHandshakeOnce() {
        if (!handshakeStarted.compareAndSet(false, true)) return
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val packets = ThinkerRideProtocol.bleHandshakePackets(timestamp)
        // Which dialect went out is the first thing to check in a log where the dash answers
        // nothing, and it cannot be inferred from anything else the log says.
        val framing = if (useByteCatFraming()) "byteCat frames" else "bare JSON"
        log("Sending the Bluetooth handshake (${packets.size} packets, queued, $framing).")
        packets.forEach { enqueue(it) }
        // The first heartbeat goes out with the handshake, not one interval later: KoveMirror
        // added exactly this (upstream 22ed5d5, "Trying to fix the connection issues") and a
        // dash that never heard from us for 5s is one we have seen ignore mirror-start.
        queueHeartbeat()
        heartbeat = scheduleRepeating(ThinkerRideProtocol.BLE_HEARTBEAT_INTERVAL_MS) { queueHeartbeat() }
    }

    /** A heartbeat that is still waiting its turn is not worth queueing twice. */
    private fun queueHeartbeat() {
        val packet = ThinkerRideProtocol.bleHeartbeatPacket()
        val alreadyQueued = synchronized(queueLock) { writeQueue.any { it.label == packet } }
        if (!alreadyQueued) enqueue(packet)
    }

    // ---- Write queue -----------------------------------------------------------------------

    private fun enqueue(json: String) {
        val packets = if (useByteCatFraming()) {
            // Reserve this message's sequence numbers in one step, so two commands queued from
            // different threads can never interleave their frames' numbering.
            val frames = ThinkerRideProtocol.byteCatFrames(json, byteCatSequence.get())
            byteCatSequence.addAndGet(frames.size)
            frames.map { Packet(json, it) }
        } else {
            listOf(Packet(json, json.toByteArray(StandardCharsets.UTF_8)))
        }
        synchronized(queueLock) {
            if (closed.get()) return
            packets.forEach { writeQueue.addLast(it) }
        }
        schedule(0) { pump() }
    }

    private fun pump() {
        val next = synchronized(queueLock) {
            if (closed.get() || inFlight != null) return
            val head = writeQueue.removeFirstOrNull() ?: return
            inFlight = head
            inFlightAttempts = 0
            writeGeneration++
            head
        }
        dispatch(next)
    }

    private fun dispatch(packet: Packet) {
        val activeGatt = gatt
        val characteristic = writeCharacteristic
        if (closed.get() || activeGatt == null || characteristic == null) {
            finishInFlight()
            return
        }
        val submitted = runCatching {
            BleCompat.writeCharacteristic(
                activeGatt,
                characteristic,
                packet.bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
        }
        val failure = submitted.exceptionOrNull()
        when {
            failure is SecurityException -> onLinkLost(missingPermissionFailure().message.orEmpty())
            failure != null -> retryOrDrop(packet, "write threw ${failure.message}")
            submitted.getOrNull() == BluetoothStatusCodes.SUCCESS -> armWriteWatchdog(packet)
            else -> retryOrDrop(packet, "refused (code ${submitted.getOrNull()})")
        }
    }

    /**
     * A refusal means the radio is busy, not that the dash said no — the packet has not been
     * transmitted at all, so the only correct answer is to send it again.
     */
    private fun retryOrDrop(packet: Packet, reason: String) {
        val attempts = synchronized(queueLock) {
            if (inFlight !== packet) return
            ++inFlightAttempts
        }
        if (attempts >= MAX_WRITE_ATTEMPTS) {
            log("BLE write dropped after $attempts attempts — $reason: ${packet.label}")
            finishInFlight()
            return
        }
        schedule(WRITE_RETRY_DELAY_MS) { dispatch(packet) }
    }

    private fun armWriteWatchdog(packet: Packet) {
        val generation = synchronized(queueLock) { writeGeneration }
        val watchdog = schedule(WRITE_CALLBACK_TIMEOUT_MS) {
            val stalled = synchronized(queueLock) { writeGeneration == generation && inFlight != null }
            if (stalled) {
                log(
                    "BLE write got no completion callback in ${WRITE_CALLBACK_TIMEOUT_MS}ms; " +
                        "moving on: ${packet.label}"
                )
                finishInFlight()
            }
        }
        synchronized(queueLock) {
            if (writeGeneration == generation) writeWatchdog = watchdog else watchdog?.cancel(false)
        }
    }

    private fun finishInFlight() {
        synchronized(queueLock) {
            if (inFlight == null) return
            inFlight = null
            inFlightAttempts = 0
            writeGeneration++
            writeWatchdog?.cancel(false)
            writeWatchdog = null
        }
        schedule(ThinkerRideProtocol.BLE_WRITE_SPACING_MS) { pump() }
    }

    private fun schedule(delayMillis: Long, action: () -> Unit): ScheduledFuture<*>? =
        runCatching { scheduler.schedule(action, delayMillis, TimeUnit.MILLISECONDS) }.getOrNull()

    private fun scheduleRepeating(intervalMillis: Long, action: () -> Unit): ScheduledFuture<*>? =
        runCatching {
            scheduler.scheduleWithFixedDelay(action, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS)
        }.getOrNull()

    private fun completeReady(outcome: Result<String>) {
        if (!ready.isCompleted) ready.complete(outcome)
    }

    private fun missingPermissionFailure(): IllegalStateException = IllegalStateException(
        "MOTO-HUB does not have the \"Nearby devices\" (Bluetooth) permission, which this " +
            "dashboard needs for pairing. Allow it in the app settings, then connect again."
    )

    private companion object {
        /** Attempts per packet before it is given up on; a busy radio clears well inside this. */
        const val MAX_WRITE_ATTEMPTS = 8
        const val WRITE_RETRY_DELAY_MS = 60L

        /** Upper bound on a missing [BluetoothGattCallback.onCharacteristicWrite]. */
        const val WRITE_CALLBACK_TIMEOUT_MS = 1_500L

        /** Upper bound on a missing [BluetoothGattCallback.onDescriptorWrite]. */
        const val DESCRIPTOR_TIMEOUT_MS = 2_000L

        /** Upper bound on a missing [BluetoothGattCallback.onMtuChanged]. */
        const val MTU_TIMEOUT_MS = 1_500L

        const val WRITE_DRAIN_POLL_MS = 25L
    }
}
