package com.example.easygallery.faces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max
import kotlin.math.min

object FaceDetector {

    // Canonical 5-point reference for 112×112 ArcFace alignment (insightface arcface_dst)
    private val ARC_DST_X = floatArrayOf(38.2946f, 73.5318f, 56.0252f, 41.5493f, 70.7299f)
    private val ARC_DST_Y = floatArrayOf(51.6963f, 51.5014f, 71.7366f, 92.3655f, 92.2041f)

    private var mtcnn: MTCNNDetector? = null

    fun init(context: Context) {
        if (mtcnn == null) {
            mtcnn = MTCNNDetector(context.applicationContext)
        }
    }

    data class DetectedFace(
        val faceIndex: Int,
        val crop: Bitmap,       // 112×112 aligned crop ready for MobileFaceNet
        val bounds: RectF,      // normalized [0..1] bounding box in original image
        val yaw: Float,
        val pitch: Float,
        val roll: Float,
        val faceRelativeSize: Float,
        val blurScore: Float,
        val hasLandmarks: Boolean,
        val detectionScore: Float,
    )

    fun detect(imagePath: String): List<DetectedFace> {
        val bitmap = decodeBitmap(imagePath) ?: return emptyList()
        return detect(bitmap)
    }

    fun detect(bitmap: Bitmap): List<DetectedFace> {
        val detector = mtcnn ?: return emptyList()
        val detections = detector.detect(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        return detections.mapIndexed { index, det ->
            val aligned = alignFace(bitmap, det.landmarks)
            DetectedFace(
                faceIndex        = index,
                crop             = aligned,
                bounds           = RectF(
                    det.box[0] / w, det.box[1] / h,
                    det.box[2] / w, det.box[3] / h
                ),
                yaw              = 0f,
                pitch            = 0f,
                roll             = 0f,
                faceRelativeSize = (det.box[2] - det.box[0]).toFloat() / w,
                blurScore        = laplacianVariance(aligned),
                hasLandmarks     = true,
                detectionScore   = det.score,
            )
        }
    }

    /**
     * Computes the least-squares similarity transform M = [[a, -b, tx], [b, a, ty]]
     * that maps MTCNN's 5 source landmarks to the ArcFace 112×112 reference points,
     * then warps the source bitmap into a 112×112 aligned crop.
     *
     * Closed-form derivation: for centred points (x', y') and (u', v'),
     *   a = Σ(u'x' + v'y') / Σ(x'² + y'²)
     *   b = Σ(v'x' - u'y') / Σ(x'² + y'²)
     */
    private fun alignFace(src: Bitmap, landmarks: Array<Point>): Bitmap {
        val n = 5
        val sx = FloatArray(n) { landmarks[it].x.toFloat() }
        val sy = FloatArray(n) { landmarks[it].y.toFloat() }

        val msx = sx.average().toFloat(); val msy = sy.average().toFloat()
        val mdx = ARC_DST_X.average().toFloat(); val mdy = ARC_DST_Y.average().toFloat()

        var den = 0f
        for (i in 0 until n) {
            val dx = sx[i] - msx; val dy = sy[i] - msy
            den += dx * dx + dy * dy
        }

        var numA = 0f; var numB = 0f
        for (i in 0 until n) {
            val srcX = sx[i] - msx; val srcY = sy[i] - msy
            val dstX = ARC_DST_X[i] - mdx; val dstY = ARC_DST_Y[i] - mdy
            numA += dstX * srcX + dstY * srcY
            numB += dstY * srcX - dstX * srcY
        }

        val a = if (den > 1e-6f) numA / den else 1f
        val b = if (den > 1e-6f) numB / den else 0f
        val tx = mdx - a * msx + b * msy
        val ty = mdy - b * msx - a * msy

        // Android Matrix row-major 3×3: [[a, -b, tx], [b, a, ty], [0, 0, 1]]
        val m = Matrix()
        m.setValues(floatArrayOf(a, -b, tx, b, a, ty, 0f, 0f, 1f))

        val output = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }

    private fun laplacianVariance(bitmap: Bitmap): Float {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var sum = 0.0; var sumSq = 0.0; var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val c = gray(pixels[y * w + x])
                val lap = (-4 * c
                    + gray(pixels[(y - 1) * w + x])
                    + gray(pixels[(y + 1) * w + x])
                    + gray(pixels[y * w + x - 1])
                    + gray(pixels[y * w + x + 1])).toDouble()
                sum += lap; sumSq += lap * lap; count++
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
        } catch (e: Exception) { return bitmap }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90      -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180     -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270     -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL   -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postScale(-1f, 1f); matrix.postRotate(270f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postScale(-1f, 1f); matrix.postRotate(90f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
