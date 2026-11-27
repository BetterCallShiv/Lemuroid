package com.swordfish.lemuroid.common.graphics

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.opengl.GLSurfaceView
import android.view.PixelCopy
import com.swordfish.lemuroid.common.kotlin.runCatchingWithRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt

suspend fun GLSurfaceView.takeScreenshot(
    maxResolution: Int,
    retries: Int = 1,
    cropViewport: RectF? = null,
): Bitmap? =
    withContext(Dispatchers.Main) {
        runCatchingWithRetry(retries) {
            takeScreenshot(maxResolution, cropViewport)
        }.getOrNull()
    }

private suspend fun GLSurfaceView.takeScreenshot(
    maxResolution: Int,
    cropViewport: RectF?,
): Bitmap? =
    suspendCoroutine { cont ->
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            cont.resume(null)
            return@suspendCoroutine
        }

        queueEvent {
            try {
                if (width <= 0 || height <= 0) {
                    cont.resume(null)
                    return@queueEvent
                }

                val srcRect =
                    cropViewport
                        ?.takeIf { it.width() > 0f && it.height() > 0f }
                        ?.let { vp ->
                            val left = (vp.left * width).roundToInt().coerceIn(0, width - 1)
                            val top = (vp.top * height).roundToInt().coerceIn(0, height - 1)
                            val right = (vp.right * width).roundToInt().coerceIn(left + 1, width)
                            val bottom = (vp.bottom * height).roundToInt().coerceIn(top + 1, height)
                            Rect(left, top, right, bottom)
                        }

                val srcWidth = srcRect?.width() ?: width
                val srcHeight = srcRect?.height() ?: height
                val outputScaling = maxResolution / maxOf(srcWidth, srcHeight).toFloat()
                val inputScaling = outputScaling * 2f

                val inputBitmap =
                    Bitmap.createBitmap(
                        (srcWidth * inputScaling).roundToInt().coerceAtLeast(1),
                        (srcHeight * inputScaling).roundToInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )

                val onCompleted = { result: Int ->
                    if (result == PixelCopy.SUCCESS) {
                        val trimmedBitmap = trimSolidBlackBorders(inputBitmap)
                        val targetScale = maxResolution / maxOf(trimmedBitmap.width, trimmedBitmap.height).toFloat()
                        val targetWidth = (trimmedBitmap.width * targetScale).roundToInt().coerceAtLeast(1)
                        val targetHeight = (trimmedBitmap.height * targetScale).roundToInt().coerceAtLeast(1)
                        // This rescaling limits the artifacts introduced by shaders.
                        val outputBitmap =
                            Bitmap.createScaledBitmap(
                                trimmedBitmap,
                                targetWidth,
                                targetHeight,
                                true,
                            )
                        if (trimmedBitmap !== inputBitmap && trimmedBitmap !== outputBitmap) {
                            trimmedBitmap.recycle()
                        }
                        if (inputBitmap !== outputBitmap) {
                            inputBitmap.recycle()
                        }
                        cont.resume(outputBitmap)
                    } else {
                        inputBitmap.recycle()
                        cont.resumeWithException(RuntimeException("Cannot take screenshot. Error code: $result"))
                    }
                }
                PixelCopy.request(this, srcRect, inputBitmap, onCompleted, handler)
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }
    }

private fun isNearBlackPixel(pixel: Int): Boolean {
    return Color.red(pixel) <= 8 && Color.green(pixel) <= 8 && Color.blue(pixel) <= 8
}

private fun trimSolidBlackBorders(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    if (w < 8 || h < 8) return bitmap

    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

    var left = 0
    while (left < w) {
        var colBlack = true
        for (y in 0 until h) {
            if (!isNearBlackPixel(pixels[y * w + left])) {
                colBlack = false
                break
            }
        }
        if (!colBlack) break
        left++
    }

    var right = w - 1
    while (right > left) {
        var colBlack = true
        for (y in 0 until h) {
            if (!isNearBlackPixel(pixels[y * w + right])) {
                colBlack = false
                break
            }
        }
        if (!colBlack) break
        right--
    }

    var top = 0
    while (top < h) {
        var rowBlack = true
        val rowOffset = top * w
        for (x in left..right) {
            if (!isNearBlackPixel(pixels[rowOffset + x])) {
                rowBlack = false
                break
            }
        }
        if (!rowBlack) break
        top++
    }

    var bottom = h - 1
    while (bottom > top) {
        var rowBlack = true
        val rowOffset = bottom * w
        for (x in left..right) {
            if (!isNearBlackPixel(pixels[rowOffset + x])) {
                rowBlack = false
                break
            }
        }
        if (!rowBlack) break
        bottom--
    }

    val croppedW = right - left + 1
    val croppedH = bottom - top + 1
    if (croppedW < (w * 0.4f).roundToInt() || croppedH < (h * 0.4f).roundToInt()) {
        return bitmap
    }
    if (left < 2 && (w - 1 - right) < 2 && top < 2 && (h - 1 - bottom) < 2) {
        return bitmap
    }

    return Bitmap.createBitmap(bitmap, left, top, croppedW, croppedH)
}
