package com.example.easygallery

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.LongBuffer
import kotlin.math.sqrt

object ClipTextEncoder {

    private const val TAG = "ClipTextEncoder"

    private val env = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    fun load(context: Context) {
        if (session != null) return
        try {
            ClipTokenizer.load(ModelManager.tokenizerFile(context))
            session = env.createSession(
                ModelManager.textModelFile(context).absolutePath,
                OrtSession.SessionOptions()
            )
            android.util.Log.d(TAG, "Inputs:  ${session!!.inputNames}")
            android.util.Log.d(TAG, "Outputs: ${session!!.outputNames}")
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Load failed: ${t::class.simpleName}: ${t.message}")
        }
    }

    fun encode(text: String): FloatArray? {
        val s = session ?: return null
        return try {
            val (ids, _) = ClipTokenizer.encode(text)

            val idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))
            val output = s.run(mapOf("input_ids" to idsTensor))
            idsTensor.close()
            output.use { extractEmbedding(it) }
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Encode failed: ${t::class.simpleName}: ${t.message}")
            null
        }
    }

    private fun extractEmbedding(output: OrtSession.Result): FloatArray? {
        android.util.Log.d(TAG, "Output names: ${output.map { it.key }}")

        for (name in listOf("text_embeds", "pooler_output")) {
            val opt = output.get(name)
            if (!opt.isPresent) continue
            val v = opt.get().value
            if (v is Array<*> && v.isNotEmpty() && v[0] is FloatArray) {
                @Suppress("UNCHECKED_CAST")
                return l2Normalize((v as Array<FloatArray>)[0])
            }
        }
        val hidden = output.get("last_hidden_state")
        if (hidden.isPresent) {
            val v = hidden.get().value
            if (v is Array<*> && v.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST")
                return l2Normalize((v as Array<Array<FloatArray>>)[0][0])
            }
        }
        for (entry in output) {
            val v = entry.value.value
            if (v is Array<*> && v.isNotEmpty() && v[0] is FloatArray) {
                android.util.Log.w(TAG, "Using fallback output '${entry.key}'")
                @Suppress("UNCHECKED_CAST")
                return l2Normalize((v as Array<FloatArray>)[0])
            }
        }
        android.util.Log.e(TAG, "No usable output found")
        return null
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        return if (norm > 1e-8f) FloatArray(v.size) { v[it] / norm } else v
    }
}
