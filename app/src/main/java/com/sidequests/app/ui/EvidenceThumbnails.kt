package com.sidequests.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import com.sidequests.app.BuildConfig
import com.sidequests.app.data.photo.EvidencePhoto
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Owned by the account's bitácora route, so leaving it also releases the cache. */
internal class EvidenceThumbnails {
    private data class Key(val source: String, val width: Int, val height: Int)

    private val cache = object : LruCache<Key, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.allocationByteCount
    }

    suspend fun load(
        photo: EvidencePhoto,
        width: Int,
        height: Int,
        download: suspend (String) -> ByteArray,
    ): Bitmap = withContext(Dispatchers.IO) {
        val file = photo.file
        val identifier = if (file != null) {
            "file:${file.path}:${file.lastModified()}:${file.length()}"
        } else "remote:${requireNotNull(photo.storagePath)}"
        val key = Key(identifier, width, height)
        val lookupStart = SystemClock.elapsedRealtimeNanos()
        cache.get(key)?.let {
            log(it, cacheHit = true, lookupStart)
            return@withContext it
        }

        val bytes = if (file == null) download(requireNotNull(photo.storagePath)) else null
        val decodeStart = SystemClock.elapsedRealtimeNanos()
        val bitmap = decodeThumbnail(file, bytes, width, height)
        currentCoroutineContext().ensureActive()
        cache.put(key, bitmap)
        log(bitmap, cacheHit = false, decodeStart)
        bitmap
    }

    private fun log(bitmap: Bitmap, cacheHit: Boolean, start: Long) {
        if (!BuildConfig.DEBUG) return
        val millis = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        val metric = if (cacheHit) "lookupMs" else "decodeMs"
        Log.i(
            "SidequestsEvidence",
            "mode=optimized cacheHit=$cacheHit width=${bitmap.width} height=${bitmap.height} " +
                "bitmapBytes=${bitmap.allocationByteCount} $metric=$millis",
        )
    }
}

private fun decodeThumbnail(file: File?, bytes: ByteArray?, width: Int, height: Int): Bitmap {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = if (file != null) ImageDecoder.createSource(file)
            else ImageDecoder.createSource(ByteBuffer.wrap(requireNotNull(bytes)))
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = fittedSize(info.size.width, info.size.height, width, height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(w, h)
        }
    }

    fun decode(options: BitmapFactory.Options): Bitmap? =
        if (file != null) BitmapFactory.decodeFile(file.path, options)
        else requireNotNull(bytes).let { BitmapFactory.decodeByteArray(it, 0, it.size, options) }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decode(bounds)
    val (w, h) = fittedSize(bounds.outWidth, bounds.outHeight, width, height)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) {
        sample *= 2
    }
    val decoded = requireNotNull(decode(BitmapFactory.Options().apply { inSampleSize = sample }))
    if (decoded.width == w && decoded.height == h) return decoded
    return Bitmap.createScaledBitmap(decoded, w, h, true).also {
        if (it !== decoded) decoded.recycle()
    }
}

internal fun fittedSize(originalWidth: Int, originalHeight: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
    require(originalWidth > 0 && originalHeight > 0 && maxWidth > 0 && maxHeight > 0)
    val scale = minOf(1.0, maxWidth.toDouble() / originalWidth, maxHeight.toDouble() / originalHeight)
    return (originalWidth * scale).roundToInt().coerceAtLeast(1) to
        (originalHeight * scale).roundToInt().coerceAtLeast(1)
}
