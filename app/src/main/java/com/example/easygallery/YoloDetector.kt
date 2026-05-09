package com.example.easygallery

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.FloatBuffer

object YoloDetector {

    private const val TAG = "YoloDetector"
    private const val INPUT_SIZE = 640
    private const val CONF_THRESHOLD = 0.25f

    private val CLASSES = arrayOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
        "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
        "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
        "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
        "toothbrush"
    )

    private val env = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    fun load(context: Context) {
        if (session != null) return
        val bytes = YoloModelManager.modelFile(context).readBytes()
        session = env.createSession(bytes)
        android.util.Log.d(TAG, "Model loaded. Inputs: ${session!!.inputNames}, Outputs: ${session!!.outputNames}")
    }

    fun detect(path: String): List<String> {
        val sess = session ?: return emptyList()

        val bitmap = decodeBitmap(path) ?: return emptyList()
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        bitmap.recycle()

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        scaled.recycle()

        val n = INPUT_SIZE * INPUT_SIZE
        val buf = FloatBuffer.allocate(3 * n)
        for (i in pixels.indices) {
            buf.put(i,       ((pixels[i] shr 16) and 0xFF) / 255f)
            buf.put(i + n,   ((pixels[i] shr 8)  and 0xFF) / 255f)
            buf.put(i + n*2,  (pixels[i]          and 0xFF) / 255f)
        }
        buf.rewind()

        val inputName = sess.inputNames.iterator().next()
        val inputTensor = OnnxTensor.createTensor(
            env, buf, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )

        val result = sess.run(mapOf(inputName to inputTensor))
        inputTensor.close()

        // Find the output tensor (shape [1, 84, 8400])
        var outputTensor: OnnxTensor? = null
        for (entry in result) {
            val v = entry.value
            if (v is OnnxTensor) { outputTensor = v; break }
        }

        if (outputTensor == null) {
            result.close()
            return emptyList()
        }

        val shape = outputTensor.info.shape   // [1, 84, 8400]
        val numFeatures = shape[1].toInt()    // 84
        val numAnchors = shape[2].toInt()     // 8400
        val numClasses = CLASSES.size         // 80
        val flat = outputTensor.floatBuffer   // size = 1 * numFeatures * numAnchors

        val detected = mutableSetOf<Int>()
        for (b in 0 until numAnchors) {
            var maxScore = CONF_THRESHOLD
            var maxClass = -1
            val classOffset = 4 // first 4 features are box coords
            for (c in 0 until minOf(numClasses, numFeatures - classOffset)) {
                val score = flat.get((classOffset + c) * numAnchors + b)
                if (score > maxScore) {
                    maxScore = score
                    maxClass = c
                }
            }
            if (maxClass >= 0) detected.add(maxClass)
        }

        result.close()
        return detected.map { CLASSES[it] }
    }

    private fun decodeBitmap(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= INPUT_SIZE &&
               bounds.outHeight / (sample * 2) >= INPUT_SIZE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, opts)
    }
}
