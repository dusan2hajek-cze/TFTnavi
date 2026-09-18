// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.roundToInt

/** Copies a selected motorcycle photo into app-private storage for reliable offline use. */
class MotorcyclePhotoStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val photoDirectory = File(applicationContext.filesDir, PHOTO_DIRECTORY)
    private val resolver = applicationContext.contentResolver

    fun copyFromUri(profileId: String, uri: Uri): Result<String> = runCatching {
        photoDirectory.mkdirs()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open the selected photo." }
            BitmapFactory.decodeStream(input, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected file is not a valid image." }

        val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to read the selected photo." }
            requireNotNull(BitmapFactory.decodeStream(input, null, options)) {
                "Unable to decode the selected photo."
            }
        }
        val upright = applyExifOrientation(bitmap, uri)
        val scaled = scaleDown(upright)
        val target = File(photoDirectory, "$profileId-${System.currentTimeMillis()}.jpg")
        target.outputStream().use { output ->
            check(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                "Unable to store the selected photo."
            }
        }
        if (scaled !== upright) scaled.recycle()
        if (upright !== bitmap) upright.recycle()
        bitmap.recycle()
        target.absolutePath
    }

    /**
     * Creates a fresh content URI the system camera can write a full-size capture into.
     * The file lives in the app cache; call [discardCameraCapture] once it has been copied.
     */
    fun createCameraCaptureUri(): Uri {
        val directory = File(applicationContext.cacheDir, CAMERA_DIRECTORY).apply { mkdirs() }
        val file = File(directory, "capture-${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(
            applicationContext,
            "${applicationContext.packageName}.fileprovider",
            file
        )
    }

    fun discardCameraCapture(uri: Uri?) {
        uri ?: return
        runCatching { applicationContext.contentResolver.delete(uri, null, null) }
    }

    /** Camera captures (and many gallery files) carry their rotation in EXIF only. */
    private fun applyExifOrientation(bitmap: Bitmap, uri: Uri): Bitmap {
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.preScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.preScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun delete(path: String?) {
        path?.let(::File)?.takeIf { it.parentFile == photoDirectory }?.delete()
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_DIMENSION || height / sample > MAX_DIMENSION) sample *= 2
        return sample
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= MAX_DIMENSION) return bitmap
        val scale = MAX_DIMENSION.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt(),
            (bitmap.height * scale).roundToInt(),
            true
        )
    }

    private companion object {
        const val PHOTO_DIRECTORY = "motorcycle_photos"
        const val CAMERA_DIRECTORY = "garage-camera"
        const val MAX_DIMENSION = 1600
        const val JPEG_QUALITY = 88
    }
}
