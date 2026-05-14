package com.example.easygallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object FaceDetector {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.05f)
            .build()
    )

    data class DetectedFace(
        val faceIndex: Int,
        val crop: Bitmap,
        val bounds: RectF,
        val yaw: Float,
        val pitch: Float,
        val roll: Float,
        val faceRelativeSize: Float,
        val blurScore: Float,
        val hasLandmarks: Boolean,
    )

    suspend fun detect(imagePath: String): List<DetectedFace> {
        val bitmap = decodeBitmap(imagePath) ?: return emptyList()
        return detect(bitmap)
    }

    suspend fun detect(bitmap: Bitmap): List<DetectedFace> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val faces: List<Face> = detector.process(image).await()
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        return faces.mapIndexed { index: Int, face: Face ->
            val box = face.boundingBox
            val pad = (max(box.width(), box.height()) * 0.2f).toInt()
            val left   = max(0, box.left - pad)
            val top    = max(0, box.top - pad)
            val right  = min(bitmap.width, box.right + pad)
            val bottom = min(bitmap.height, box.bottom + pad)
            val bounds = RectF(left / w, top / h, right / w, bottom / h)
            val roll = face.headEulerAngleZ
            val crop = cropFace(bitmap, box, roll)
            val hasLandmarks = face.getLandmark(FaceLandmark.LEFT_EYE) != null ||
                               face.getLandmark(FaceLandmark.RIGHT_EYE) != null
            DetectedFace(
                faceIndex       = index,
                crop            = crop,
                bounds          = bounds,
                yaw             = face.headEulerAngleY,
                pitch           = face.headEulerAngleX,
                roll            = roll,
                faceRelativeSize = box.width().toFloat() / w,
                blurScore       = laplacianVariance(crop),
                hasLandmarks    = hasLandmarks,
            )
        }
    }

    private fun cropFace(src: Bitmap, box: Rect, roll: Float): Bitmap {
        // Extra padding prevents the face from being clipped after rotation
        val base = max(box.width(), box.height())
        val pad = (base * if (abs(roll) > 20f) 0.35f else 0.20f).toInt()
        val left   = max(0, box.left - pad)
        val top    = max(0, box.top - pad)
        val right  = min(src.width, box.right + pad)
        val bottom = min(src.height, box.bottom + pad)
        val cropped = Bitmap.createBitmap(src, left, top, right - left, bottom - top)
        if (abs(roll) < 2f) return Bitmap.createScaledBitmap(cropped, 112, 112, true)
        val matrix = Matrix().apply {
            postRotate(-roll, cropped.width / 2f, cropped.height / 2f)
        }
        val rotated = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
        return Bitmap.createScaledBitmap(rotated, 112, 112, true)
    }

    private fun laplacianVariance(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val c = gray(pixels[y * w + x])
                val lap = (-4 * c
                    + gray(pixels[(y - 1) * w + x])
                    + gray(pixels[(y + 1) * w + x])
                    + gray(pixels[y * w + x - 1])
                    + gray(pixels[y * w + x + 1])).toDouble()
                sum += lap
                sumSq += lap * lap
                count++
            }
        }
        if (count == 0) return 0f
        val mean = sum / count
        return ((sumSq / count) - mean * mean).toFloat()
    }

    private fun gray(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    private fun decodeBitmap(path: String): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        val maxDim = max(opts.outWidth, opts.outHeight)
        opts.inSampleSize = max(1, maxDim / 640)
        opts.inJustDecodeBounds = false
        val raw = BitmapFactory.decodeFile(path, opts) ?: return null
        return applyExifOrientation(raw, path)
    }

    private fun applyExifOrientation(bitmap: Bitmap, path: String): Bitmap {
        val orientation = try {
            ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } catch (e: Exception) {
            return bitmap
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90    -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180   -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270   -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL   -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE    -> { matrix.postScale(-1f, 1f); matrix.postRotate(270f) }
            ExifInterface.ORIENTATION_TRANSVERSE   -> { matrix.postScale(-1f, 1f); matrix.postRotate(90f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
