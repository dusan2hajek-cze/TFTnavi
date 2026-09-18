// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.diagnostics.report

import android.content.Context
import android.net.Network
import io.motohub.android.BuildConfig
import io.motohub.android.net.withCellularNetwork
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The outcome of one upload attempt, in words Diagnostics can show. */
sealed interface DiagnosticUploadResult {
    data class Sent(val reportId: String, val logBytes: Int) : DiagnosticUploadResult

    /** Worth retrying later: no Internet, timeout, server unavailable. */
    data class Unreachable(val reason: String) : DiagnosticUploadResult

    /** Not worth retrying with the same payload: rejected, misconfigured, no endpoint at all. */
    data class Rejected(val reason: String) : DiagnosticUploadResult
}

/**
 * Posts a [DiagnosticReport] to the collector as a multipart form: a `report` JSON part and a
 * gzip-compressed `log` file part. Plain `HttpURLConnection`, like every other one-shot call in
 * the app. The route matters more than the client: at launch the process is often bound to the
 * T-Box Wi-Fi, which has no Internet, so the request is made on a validated network (cellular
 * on a real bike) through the same helper the navigation stack uses.
 */
object DiagnosticReportUploader {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val BOUNDARY = "----MotoHubDiagnostics7f2e"

    /** Emitted by the collector only once the report is committed; see the response check below. */
    private const val SUCCESS_MARKER = "MOTOHUB-DIAG-OK"
    private const val MAX_RESPONSE_CHARS = 500

    val configured: Boolean
        get() = BuildConfig.DIAGNOSTICS_ENDPOINT.isNotBlank()

    suspend fun upload(context: Context, report: DiagnosticReport): DiagnosticUploadResult {
        val endpoint = BuildConfig.DIAGNOSTICS_ENDPOINT.trim()
        if (endpoint.isEmpty()) return DiagnosticUploadResult.Rejected("No collector configured in this build.")
        val body = withContext(Dispatchers.Default) { multipartBody(report) }
        return try {
            withCellularNetwork(context, cellularOnly = false) { network ->
                withContext(Dispatchers.IO) { post(endpoint, network, body, report) }
            }
        } catch (failure: IOException) {
            DiagnosticUploadResult.Unreachable(failure.message ?: failure.javaClass.simpleName)
        } catch (failure: Exception) {
            DiagnosticUploadResult.Rejected(failure.message ?: failure.javaClass.simpleName)
        }
    }

    private fun post(endpoint: String, network: Network?, body: ByteArray, report: DiagnosticReport): DiagnosticUploadResult {
        val url = URL(endpoint)
        val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.useCaches = false
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
            connection.setRequestProperty("User-Agent", "MOTO-HUB-Android/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("X-MotoHub-Key", BuildConfig.DIAGNOSTICS_KEY)
            connection.setRequestProperty("X-MotoHub-Support-Id", report.supportId)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            return classify(status, readBody(connection), report.reportId, body.size)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Turns one HTTP reply into a verdict.
     *
     * A 2xx alone does not mean the report was stored: n8n answers 200 to the caller for some
     * internal failures, so a report dropped by a failing workflow used to be recorded as
     * delivered and never retried. Only [SUCCESS_MARKER] - which the collector emits after the
     * database row and the log file are committed - counts as success; an unconfirmed 2xx is
     * treated as a transient failure so the next launch tries again.
     */
    internal fun classify(status: Int, body: String, reportId: String, uploadedBytes: Int): DiagnosticUploadResult = when {
        status in 200..299 && body.contains(SUCCESS_MARKER) -> DiagnosticUploadResult.Sent(reportId, uploadedBytes)
        status in 200..299 -> DiagnosticUploadResult.Unreachable(
            "Collector answered HTTP $status without confirming the report was stored."
        )
        status == 401 || status == 403 -> DiagnosticUploadResult.Rejected("Collector refused the key (HTTP $status).")
        status == 413 -> DiagnosticUploadResult.Rejected("Report too large for the collector (HTTP 413).")
        status in 500..599 || status == 429 -> DiagnosticUploadResult.Unreachable("Collector unavailable (HTTP $status).")
        else -> DiagnosticUploadResult.Rejected("Collector answered HTTP $status.")
    }

    /** The reply is a short one-line acknowledgement; anything longer is not ours and is capped. */
    private fun readBody(connection: HttpURLConnection): String = runCatching {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val buffer = CharArray(MAX_RESPONSE_CHARS)
            val read = reader.read(buffer)
            if (read <= 0) "" else String(buffer, 0, read)
        }.orEmpty()
    }.getOrDefault("")

    /** Builds the body in memory: the log part is gzip-compressed first (roughly 10:1 on this log). */
    internal fun multipartBody(report: DiagnosticReport): ByteArray {
        val gzip = ByteArrayOutputStream().also { buffer ->
            GZIPOutputStream(buffer).use { it.write(report.logText.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val out = ByteArrayOutputStream()
        fun line(text: String) = out.write((text + "\r\n").toByteArray(Charsets.UTF_8))
        line("--$BOUNDARY")
        line("Content-Disposition: form-data; name=\"report\"; filename=\"report.json\"")
        line("Content-Type: application/json; charset=utf-8")
        line("")
        out.write(report.metadata.toString().toByteArray(Charsets.UTF_8))
        line("")
        line("--$BOUNDARY")
        line("Content-Disposition: form-data; name=\"log\"; filename=\"${report.reportId}.log.gz\"")
        line("Content-Type: application/gzip")
        line("")
        out.write(gzip)
        line("")
        line("--$BOUNDARY--")
        return out.toByteArray()
    }
}
