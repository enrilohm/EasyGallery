package com.example.easygallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.max
import kotlin.math.min

object FaceDetector {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.1f)
            .build()
    )

    data class DetectedFace(val faceIndex: Int, val crop: Bitmap)

    suspend fun detect(imagePath: String): List<DetectedFace> {
        val bitmap = decodeBitmap(imagePath) ?: return emptyList()
        return detect(bitmap)
    }

    /** Returns face bounding boxes normalized to [0,1] relative to image dimensions. */
    suspend fun detectBoxes(imagePath: String): List<RectF> {
        val bitmap = decodeBitmap(imagePath) ?: return emptyList()
        val image = InputImage.fromBitmap(bitmap, 0)
        val faces: List<Face> = detector.process(image).await()
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        return faces.map { face ->
            val box = face.boundingBox
            RectF(
                box.left / w,
                box.top / h,
                box.right / w,
                box.bottom / h
            )
        }
    }

    suspend fun detect(bitmap: Bitmap): List<DetectedFace> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val faces: List<Face> = detector.process(image).await()
        return faces.mapIndexed { index: Int, face: Face ->
            DetectedFace(index, cropFace(bitmap, face.boundingBox))
        }
    }

    private fun cropFace(src: Bitmap, box: Rect): Bitmap {
        val pad = (max(box.width(), box.height()) * 0.2f).toInt()
        val left   = max(0, box.left - pad)
        val top    = max(0, box.top - pad)
        val right  = min(src.width, box.right + pad)
        val bottom = min(src.height, box.bottom + pad)
        val cropped = Bitmap.createBitmap(src, left, top, right - left, bottom - top)
        return Bitmap.createScaledBitmap(cropped, 112, 112, true)
    }

    private fun decodeBitmap(path: String): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        val maxDim = max(opts.outWidth, opts.outHeight)
        opts.inSampleSize = max(1, maxDim / 640)
        opts.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(path, opts)
    }
}
