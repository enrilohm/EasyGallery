package com.example.easygallery

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.FloatBuffer
import java.util.EnumSet
import kotlin.math.sqrt

object ClipEncoder {

    private const val TAG = "ClipEncoder"
    const val IMG_SIZE = 224

    private val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val std  = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    private val env = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    fun load(context: Context) {
        if (session != null) return
        val modelFile = ModelManager.modelFile(context)
        try {
            val opts = OrtSession.SessionOptions()
            try {
                opts.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                android.util.Log.d(TAG, "NNAPI enabled")
            } catch (e: OrtException) {
                android.util.Log.w(TAG, "NNAPI unavailable, using CPU: ${e.message}")
            }
            session = env.createSession(modelFile.absolutePath, opts)
            android.util.Log.d(TAG, "Inputs:  ${session!!.inputNames}")
            android.util.Log.d(TAG, "Outputs: ${session!!.outputNames}")
        } catch (e: OrtException) {
            android.util.Log.e(TAG, "Failed to load model: ${e.message}")
        }
    }

    fun decodeBitmap(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            android.util.Log.e(TAG, "Cannot decode bounds: $path")
            return null
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= IMG_SIZE &&
               bounds.outHeight / (sample * 2) >= IMG_SIZE) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeFile(path, opts) ?: run {
            android.util.Log.e(TAG, "Cannot decode pixels: $path")
            return null
        }
        val scaled = Bitmap.createScaledBitmap(raw, IMG_SIZE, IMG_SIZE, true)
        if (scaled !== raw) raw.recycle()
        return scaled
    }

    fun encodeBatch(bitmaps: List<Bitmap>): List<FloatArray?> {
        val s = session ?: return List(bitmaps.size) { null }
        val n = bitmaps.size
        if (n == 0) return emptyList()

        val pixelsPerImage = IMG_SIZE * IMG_SIZE
        val buf = FloatBuffer.allocate(n * 3 * pixelsPerImage)
        val pixels = IntArray(pixelsPerImage)

        for (bitmap in bitmaps) {
            bitmap.getPixels(pixels, 0, IMG_SIZE, 0, 0, IMG_SIZE, IMG_SIZE)
            for (c in 0..2) {
                val shift = when (c) { 0 -> 16; 1 -> 8; else -> 0 }
                for (pixel in pixels) {
                    val v = ((pixel shr shift) and 0xFF) / 255f
                    buf.put((v - mean[c]) / std[c])
                }
            }
        }
        buf.rewind()

        val tensor = try {
            OnnxTensor.createTensor(env, buf, longArrayOf(n.toLong(), 3, IMG_SIZE.toLong(), IMG_SIZE.toLong()))
        } catch (e: OrtException) {
            android.util.Log.e(TAG, "Tensor creation failed: ${e.message}")
            return List(n) { null }
        }

        return try {
            s.run(mapOf("pixel_values" to tensor)).use { output ->
                tensor.close()
                extractBatchEmbeddings(output, n)
            }
        } catch (t: Throwable) {
            tensor.close()
            android.util.Log.e(TAG, "Batch inference failed: ${t.message}")
            List(n) { null }
        }
    }

    private fun extractBatchEmbeddings(output: OrtSession.Result, n: Int): List<FloatArray?> {
        // pooler_output → [N, dim]
        val poolerOpt = output.get("pooler_output")
        if (poolerOpt.isPresent) {
            @Suppress("UNCHECKED_CAST")
            return (poolerOpt.get().value as Array<FloatArray>).map { l2Normalize(it) }
        }
        // last_hidden_state → CLS token at [:, 0, :], shape [N, seq_len, dim]
        val hiddenOpt = output.get("last_hidden_state")
        if (hiddenOpt.isPresent) {
            @Suppress("UNCHECKED_CAST")
            return (hiddenOpt.get().value as Array<Array<FloatArray>>).map { l2Normalize(it[0]) }
        }
        // Fallback: first 2-D output [N, dim]
        for (entry in output) {
            val v = entry.value.value
            if (v is Array<*> && v.isNotEmpty() && v[0] is FloatArray) {
                @Suppress("UNCHECKED_CAST")
                return (v as Array<FloatArray>).map { l2Normalize(it) }
            }
        }
        android.util.Log.e(TAG, "No usable batch embedding output")
        return List(n) { null }
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        return if (norm > 1e-8f) FloatArray(v.size) { v[it] / norm } else v
    }
}
