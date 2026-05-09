package com.example.easygallery

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.FloatBuffer
import kotlin.math.sqrt

object ClipEncoder {

    private const val TAG = "ClipEncoder"
    private const val IMG_SIZE = 224

    private val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val std  = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    private val env = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    fun load(context: Context) {
        if (session != null) return
        val modelFile = ModelManager.modelFile(context)
        try {
            session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
            android.util.Log.d(TAG, "Inputs:  ${session!!.inputNames}")
            android.util.Log.d(TAG, "Outputs: ${session!!.outputNames}")
        } catch (e: OrtException) {
            android.util.Log.e(TAG, "Failed to load model: ${e.message}")
        }
    }

    fun encode(path: String): FloatArray? {
        val s = session ?: return null

        val bitmap = decodeBitmap(path) ?: return null
        val tensor = try {
            bitmapToTensor(bitmap)
        } catch (e: OrtException) {
            android.util.Log.e(TAG, "Tensor creation failed: ${e.message}")
            bitmap.recycle()
            return null
        }
        bitmap.recycle()

        return try {
            val output = s.run(mapOf("pixel_values" to tensor))
            tensor.close()
            output.use { extractEmbedding(it) }  // returns null if no output matches
        } catch (t: Throwable) {
            tensor.close()
            android.util.Log.e(TAG, "Inference failed: ${t::class.simpleName}: ${t.message}")
            null
        }
    }

    private fun extractEmbedding(output: OrtSession.Result): FloatArray? {
        val names = output.map { it.key }
        android.util.Log.d(TAG, "Output names: $names")

        // pooler_output → shape [1, 768]
        val poolerOpt = output.get("pooler_output")
        if (poolerOpt.isPresent) {
            @Suppress("UNCHECKED_CAST")
            return l2Normalize((poolerOpt.get().value as Array<FloatArray>)[0])
        }

        // last_hidden_state → CLS token at [0][0], shape [1, seq_len, 768]
        val hiddenOpt = output.get("last_hidden_state")
        if (hiddenOpt.isPresent) {
            @Suppress("UNCHECKED_CAST")
            return l2Normalize((hiddenOpt.get().value as Array<Array<FloatArray>>)[0][0])
        }

        // Fallback: scan outputs for the first 2-D float array [1, N]
        for (entry in output) {
            val v = entry.value.value
            if (v is Array<*> && v.isNotEmpty() && v[0] is FloatArray) {
                android.util.Log.w(TAG, "Using fallback output '${entry.key}'")
                @Suppress("UNCHECKED_CAST")
                return l2Normalize((v as Array<FloatArray>)[0])
            }
        }

        android.util.Log.e(TAG, "No usable embedding output in: $names")
        return null
    }

    private fun decodeBitmap(path: String): Bitmap? {
        // First pass: read dimensions only (no pixel memory allocated)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            android.util.Log.e(TAG, "Cannot decode bounds: $path")
            return null
        }

        // Subsample by powers-of-2 so the decoded bitmap is just above IMG_SIZE
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

    private fun bitmapToTensor(bitmap: Bitmap): OnnxTensor {
        // Output layout: NCHW float32 [1, 3, 224, 224]
        val buf = FloatBuffer.allocate(3 * IMG_SIZE * IMG_SIZE)
        val pixels = IntArray(IMG_SIZE * IMG_SIZE)
        bitmap.getPixels(pixels, 0, IMG_SIZE, 0, 0, IMG_SIZE, IMG_SIZE)

        // ARGB packed int: red=bits 16-23, green=8-15, blue=0-7
        for (c in 0..2) {
            val shift = when (c) { 0 -> 16; 1 -> 8; else -> 0 }
            for (pixel in pixels) {
                val v = ((pixel shr shift) and 0xFF) / 255f
                buf.put((v - mean[c]) / std[c])
            }
        }
        buf.rewind()

        return OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, IMG_SIZE.toLong(), IMG_SIZE.toLong()))
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        return if (norm > 1e-8f) FloatArray(v.size) { v[it] / norm } else v
    }
}
