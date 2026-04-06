// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.audio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decodes image bytes, downscales so the longest side is at most [maxSidePx], re-encodes as JPEG.
 * @return jpeg bytes and "image/jpeg"
 */
fun downscaleToJpeg(bytes: ByteArray, maxSidePx: Int = 2048, quality: Int = 85): Pair<ByteArray, String> {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    val w = opts.outWidth
    val h = opts.outHeight
    if (w <= 0 || h <= 0) return bytes to "image/jpeg"
    val sample = max(1, max(w, h) / maxSidePx)
    val decode = BitmapFactory.Options().apply {
        inSampleSize = sample
    }
    var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode) ?: return bytes to "image/jpeg"
    val rw = bmp.width
    val rh = bmp.height
    val longSide = max(rw, rh)
    val scaled = if (longSide > maxSidePx) {
        val scale = maxSidePx.toFloat() / longSide
        val nw = (rw * scale).roundToInt().coerceAtLeast(1)
        val nh = (rh * scale).roundToInt().coerceAtLeast(1)
        val s = Bitmap.createScaledBitmap(bmp, nw, nh, true)
        if (s != bmp) bmp.recycle()
        s
    } else {
        bmp
    }
    val out = ByteArrayOutputStream((scaled.width * scaled.height / 4).coerceAtLeast(32_768))
    scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 95), out)
    scaled.recycle()
    return out.toByteArray() to "image/jpeg"
}
