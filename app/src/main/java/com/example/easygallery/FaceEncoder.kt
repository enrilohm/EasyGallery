package com.example.easygallery

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer
import java.util.EnumSet
import kotlin.math.sqrt

object FaceEncoder {

    private const val TAG = "FaceEncoder"
    private val env = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    fun load(context: Context) {
        if (session != null) return
        try {
            val opts = OrtSession.SessionOptions()
            try {
                opts.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                android.util.Log.d(TAG, "NNAPI enabled")
            } catch (e: OrtException) {
                android.util.Log.w(TAG, "NNAPI unavailable, using CPU: ${e.message}")
            }
            session = env.createSession(FaceModelManager.modelFile(context).absolutePath, opts)
            android.util.Log.d(TAG, "Inputs:  ${session!!.inputNames}")
            android.util.Log.d(TAG, "Outputs: ${session!!.outputNames}")
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Load failed: ${t.message}")
        }
    }

    fun encode(face: Bitmap): FloatArray? {
        val s = session ?: return null
        return try {
            val input = bitmapToTensor(face)
            val inputName = s.inputNames.iterator().next()
            val tensor = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, 112, 112))
            val output = s.run(mapOf(inputName to tensor))
            tensor.close()
            output.use {
                val v = it.first().value.value
                @Suppress("UNCHECKED_CAST")
                val arr = when (v) {
                    is Array<*> -> (v as Array<FloatArray>)[0]
                    is FloatArray -> v
                    else -> null
                }
                arr?.let { l2Normalize(it) }
            }
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Encode failed: ${t.message}")
            null
        }
    }

    private fun bitmapToTensor(bitmap: Bitmap): FloatBuffer {
        val buf = FloatBuffer.allocate(3 * 112 * 112)
        val pixels = IntArray(112 * 112)
        bitmap.getPixels(pixels, 0, 112, 0, 0, 112, 112)
        // NCHW, normalize to [-1, 1]
        for (p in pixels) buf.put(((p shr 16 and 0xFF) / 127.5f) - 1f)
        for (p in pixels) buf.put(((p shr 8  and 0xFF) / 127.5f) - 1f)
        for (p in pixels) buf.put(((p        and 0xFF) / 127.5f) - 1f)
        buf.rewind()
        return buf
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        return if (norm > 1e-8f) FloatArray(v.size) { v[it] / norm } else v
    }
}
