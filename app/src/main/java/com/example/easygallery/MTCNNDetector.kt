package com.example.easygallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Point
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MTCNNDetector(context: Context) {

    private val appContext = context.applicationContext

    // ─── Public result type ───────────────────────────────────────────────────

    data class Detection(
        val box: IntArray,          // [left, top, right, bottom] in bitmap pixels
        val score: Float,
        val landmarks: Array<Point> // [left_eye, right_eye, nose, left_mouth, right_mouth]
    )

    // ─── Internal box ─────────────────────────────────────────────────────────

    private class Box {
        val box = IntArray(4)       // [left, top, right, bottom]
        var score = 0f
        val bbr = FloatArray(4)
        var deleted = false
        val landmark = arrayOfNulls<Point>(5)

        fun left()   = box[0]
        fun top()    = box[1]
        fun right()  = box[2]
        fun bottom() = box[3]
        fun width()  = box[2] - box[0] + 1
        fun height() = box[3] - box[1] + 1
        fun area()   = width() * height()

        fun calibrate() {
            val w = box[2] - box[0] + 1
            val h = box[3] - box[1] + 1
            box[0] = (box[0] + w * bbr[0]).toInt()
            box[1] = (box[1] + h * bbr[1]).toInt()
            box[2] = (box[2] + w * bbr[2]).toInt()
            box[3] = (box[3] + h * bbr[3]).toInt()
            bbr.fill(0f)
        }

        fun toSquareShape() {
            val w = width(); val h = height()
            if (w > h) {
                box[1] -= (w - h) / 2
                box[3] += (w - h + 1) / 2
            } else {
                box[0] -= (h - w) / 2
                box[2] += (h - w + 1) / 2
            }
        }

        // Matches the reference Java implementation exactly (only shifts two corners per condition)
        fun limitSquare(imgW: Int, imgH: Int) {
            if (box[0] < 0 || box[1] < 0) {
                val len = max(-box[0], -box[1])
                box[0] += len; box[1] += len
            }
            if (box[2] >= imgW || box[3] >= imgH) {
                val len = max(box[2] - imgW + 1, box[3] - imgH + 1)
                box[2] -= len; box[3] -= len
            }
        }
    }

    // ─── TFLite interpreters ──────────────────────────────────────────────────

    private val pInterp: Interpreter
    private val rInterp: Interpreter
    private val oInterp: Interpreter

    init {
        val opts = Interpreter.Options().setNumThreads(2)
        pInterp = Interpreter(loadModel("pnet.tflite"), opts)
        rInterp = Interpreter(loadModel("rnet.tflite"), opts)
        oInterp = Interpreter(loadModel("onet.tflite"), opts)
        android.util.Log.d(TAG, "PNet out tensors: ${(0 until pInterp.outputTensorCount).map { pInterp.getOutputTensor(it).name() }}")
        android.util.Log.d(TAG, "RNet out tensors: ${(0 until rInterp.outputTensorCount).map { rInterp.getOutputTensor(it).name() }}")
        android.util.Log.d(TAG, "ONet out tensors: ${(0 until oInterp.outputTensorCount).map { oInterp.getOutputTensor(it).name() }}")
    }

    private fun loadModel(name: String): MappedByteBuffer {
        val file = File(appContext.filesDir, name)
        return FileInputStream(file).channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    @Synchronized
    fun detect(bitmap: Bitmap, minFaceSize: Int = 40): List<Detection> {
        var boxes = pNet(bitmap, minFaceSize)
        squareLimit(boxes, bitmap.width, bitmap.height)
        boxes = rNet(bitmap, boxes)
        squareLimit(boxes, bitmap.width, bitmap.height)
        boxes = oNet(bitmap, boxes)
        return boxes.mapNotNull { b ->
            val lms = b.landmark
            if (lms.any { it == null }) null
            else Detection(
                box = b.box.clone(),
                score = b.score,
                landmarks = Array(5) { lms[it]!! }
            )
        }
    }

    private fun squareLimit(boxes: MutableList<Box>, w: Int, h: Int) =
        boxes.forEach { b -> b.toSquareShape(); b.limitSquare(w, h) }

    // ─── PNet ─────────────────────────────────────────────────────────────────

    private fun pNet(bitmap: Bitmap, minFaceSize: Int): MutableList<Box> {
        val shortSide = min(bitmap.width, bitmap.height)
        var faceSize = minFaceSize.toFloat()
        val total = mutableListOf<Box>()

        while (faceSize <= shortSide) {
            val scale = 12f / faceSize
            val bm = scaleBitmap(bitmap, scale)
            val w = bm.width; val h = bm.height

            // normalizeImage → [H][W][3], transposeHWC → [W][H][3], batch → [1][W][H][3]
            val input = arrayOf(transposeHWC(normalizeImage(bm)))

            pInterp.resizeInput(0, intArrayOf(1, w, h, 3))
            pInterp.allocateTensors()

            val probIdx = pInterp.getOutputIndex("pnet/prob1")
            val bboxIdx = pInterp.getOutputIndex("pnet/conv4-2/BiasAdd")
            val probShape = pInterp.getOutputTensor(probIdx).shape()  // [1, outW, outH, 2]
            val outW = probShape[1]; val outH = probShape[2]

            val prob1   = Array(1) { Array(outW) { Array(outH) { FloatArray(2) } } }
            val bboxReg = Array(1) { Array(outW) { Array(outH) { FloatArray(4) } } }
            val outputs = HashMap<Int, Any>()
            outputs[probIdx] = prob1
            outputs[bboxIdx] = bboxReg

            @Suppress("UNCHECKED_CAST")
            pInterp.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs as Map<Int, Any>)

            // Transpose outputs from [1][outW][outH][C] back to [1][outH][outW][C]
            val prob1T   = transposeBatch(prob1)
            val bboxRegT = transposeBatch(bboxReg)

            val cur = generateBoxes(prob1T, bboxRegT, scale)
            nms(cur, 0.5f, NMS_UNION)
            total.addAll(cur.filter { !it.deleted })

            faceSize /= FACTOR
        }

        nms(total, 0.7f, NMS_UNION)
        total.forEach { it.calibrate() }
        return total.filter { !it.deleted }.toMutableList()
    }

    private fun generateBoxes(
        prob1: Array<Array<Array<FloatArray>>>,
        bboxReg: Array<Array<Array<FloatArray>>>,
        scale: Float
    ): MutableList<Box> {
        val h = prob1[0].size; val w = prob1[0][0].size
        val result = mutableListOf<Box>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val score = prob1[0][y][x][1]
                if (score > P_THRESH) {
                    val b = Box()
                    b.score = score
                    b.box[0] = (x * 2 / scale).roundToInt()
                    b.box[1] = (y * 2 / scale).roundToInt()
                    b.box[2] = ((x * 2 + 11) / scale).roundToInt()
                    b.box[3] = ((y * 2 + 11) / scale).roundToInt()
                    for (i in 0..3) b.bbr[i] = bboxReg[0][y][x][i]
                    result.add(b)
                }
            }
        }
        return result
    }

    // ─── RNet ─────────────────────────────────────────────────────────────────

    private fun rNet(bitmap: Bitmap, candidates: MutableList<Box>): MutableList<Box> {
        if (candidates.isEmpty()) return mutableListOf()
        val n = candidates.size
        val input = Array(n) { i -> transposeHWC(cropAndResize(bitmap, candidates[i], 24)) }

        rInterp.resizeInput(0, intArrayOf(n, 24, 24, 3))
        rInterp.allocateTensors()

        val prob1   = Array(n) { FloatArray(2) }
        val bboxReg = Array(n) { FloatArray(4) }
        val outputs = HashMap<Int, Any>()
        outputs[rInterp.getOutputIndex("rnet/prob1")] = prob1
        outputs[rInterp.getOutputIndex("rnet/conv5-2/conv5-2")] = bboxReg

        @Suppress("UNCHECKED_CAST")
        rInterp.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs as Map<Int, Any>)

        for (i in 0 until n) {
            candidates[i].score = prob1[i][1]
            for (j in 0..3) candidates[i].bbr[j] = bboxReg[i][j]
            if (candidates[i].score < R_THRESH) candidates[i].deleted = true
        }
        nms(candidates, 0.7f, NMS_UNION)
        candidates.filter { !it.deleted }.forEach { it.calibrate() }
        return candidates.filter { !it.deleted }.toMutableList()
    }

    // ─── ONet ─────────────────────────────────────────────────────────────────

    private fun oNet(bitmap: Bitmap, candidates: MutableList<Box>): MutableList<Box> {
        if (candidates.isEmpty()) return mutableListOf()
        val n = candidates.size
        val input = Array(n) { i -> transposeHWC(cropAndResize(bitmap, candidates[i], 48)) }

        oInterp.resizeInput(0, intArrayOf(n, 48, 48, 3))
        oInterp.allocateTensors()

        val prob1   = Array(n) { FloatArray(2) }
        val bboxReg = Array(n) { FloatArray(4) }
        val lmReg   = Array(n) { FloatArray(10) }
        val outputs = HashMap<Int, Any>()
        outputs[oInterp.getOutputIndex("onet/prob1")] = prob1
        outputs[oInterp.getOutputIndex("onet/conv6-2/conv6-2")] = bboxReg
        outputs[oInterp.getOutputIndex("onet/conv6-3/conv6-3")] = lmReg

        @Suppress("UNCHECKED_CAST")
        oInterp.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs as Map<Int, Any>)

        for (i in 0 until n) {
            candidates[i].score = prob1[i][1]
            for (j in 0..3) candidates[i].bbr[j] = bboxReg[i][j]
            // landmarks: first 5 values are x offsets, next 5 are y offsets
            for (j in 0..4) {
                val x = (candidates[i].left() + lmReg[i][j]     * candidates[i].width()).roundToInt()
                val y = (candidates[i].top()  + lmReg[i][j + 5] * candidates[i].height()).roundToInt()
                candidates[i].landmark[j] = Point(x, y)
            }
            if (candidates[i].score < O_THRESH) candidates[i].deleted = true
        }
        candidates.filter { !it.deleted }.forEach { it.calibrate() }
        nms(candidates, 0.7f, NMS_MIN)
        return candidates.filter { !it.deleted }.toMutableList()
    }

    // ─── NMS ──────────────────────────────────────────────────────────────────

    private fun nms(boxes: MutableList<Box>, thresh: Float, method: String) {
        for (i in boxes.indices) {
            val a = boxes[i]; if (a.deleted) continue
            for (j in i + 1 until boxes.size) {
                val b = boxes[j]; if (b.deleted) continue
                val ix1 = max(a.box[0], b.box[0]); val iy1 = max(a.box[1], b.box[1])
                val ix2 = min(a.box[2], b.box[2]); val iy2 = min(a.box[3], b.box[3])
                if (ix2 < ix1 || iy2 < iy1) continue
                val inter = ((ix2 - ix1 + 1) * (iy2 - iy1 + 1)).toFloat()
                val iou = if (method == NMS_UNION) inter / (a.area() + b.area() - inter)
                          else inter / min(a.area(), b.area()).toFloat()
                if (iou >= thresh) {
                    if (a.score > b.score) b.deleted = true else a.deleted = true
                }
            }
        }
    }

    // ─── Image helpers ────────────────────────────────────────────────────────

    // Returns [H][W][3], normalized to ~[-1, 1] using (pixel - 127.5) / 128
    private fun normalizeImage(bm: Bitmap): Array<Array<FloatArray>> {
        val h = bm.height; val w = bm.width
        val px = IntArray(w * h)
        bm.getPixels(px, 0, w, 0, 0, w, h)
        return Array(h) { y ->
            Array(w) { x ->
                val p = px[y * w + x]
                floatArrayOf(
                    ((p shr 16 and 0xFF) - 127.5f) / 128f,
                    ((p shr 8  and 0xFF) - 127.5f) / 128f,
                    ((p        and 0xFF) - 127.5f) / 128f
                )
            }
        }
    }

    // [H][W][C] → [W][H][C]: matches the Java transposeBatch swap of dim1↔dim2
    private fun transposeHWC(hwc: Array<Array<FloatArray>>): Array<Array<FloatArray>> {
        val h = hwc.size; val w = hwc[0].size
        return Array(w) { x -> Array(h) { y -> hwc[y][x].copyOf() } }
    }

    // [N][D1][D2][C] → [N][D2][D1][C]
    private fun transposeBatch(b: Array<Array<Array<FloatArray>>>): Array<Array<Array<FloatArray>>> {
        val n = b.size; val d1 = b[0].size; val d2 = b[0][0].size
        return Array(n) { ni -> Array(d2) { j -> Array(d1) { i -> b[ni][i][j].copyOf() } } }
    }

    private fun scaleBitmap(src: Bitmap, scale: Float): Bitmap {
        val m = Matrix().apply { postScale(scale, scale) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    // Crops box from src (clamped to image bounds) and scales to size×size, then normalizes
    private fun cropAndResize(src: Bitmap, box: Box, size: Int): Array<Array<FloatArray>> {
        val l = box.box[0].coerceIn(0, src.width  - 1)
        val t = box.box[1].coerceIn(0, src.height - 1)
        val r = box.box[2].coerceIn(0, src.width  - 1)
        val b = box.box[3].coerceIn(0, src.height - 1)
        val w = (r - l + 1).coerceAtLeast(1)
        val h = (b - t + 1).coerceAtLeast(1)
        val crop = Bitmap.createBitmap(src, l, t, w, h)
        val scaled = if (w == size && h == size) crop
                     else Bitmap.createScaledBitmap(crop, size, size, true)
        return normalizeImage(scaled)
    }

    companion object {
        private const val TAG = "MTCNNDetector"
        private const val FACTOR   = 0.709f
        private const val P_THRESH = 0.6f
        private const val R_THRESH = 0.7f
        private const val O_THRESH = 0.7f
        private const val NMS_UNION = "Union"
        private const val NMS_MIN   = "Min"
    }
}
