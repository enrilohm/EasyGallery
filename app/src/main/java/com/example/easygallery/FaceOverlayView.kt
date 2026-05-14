package com.example.easygallery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.github.chrisbanes.photoview.PhotoView

class FaceOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var photoView: PhotoView? = null
    private var normalRects: List<RectF> = emptyList()
    private var highlightRects: List<RectF> = emptyList()

    private val normalPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val highlightPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    fun attach(pv: PhotoView) {
        photoView = pv
        pv.setOnMatrixChangeListener { invalidate() }
    }

    fun setFaces(normal: List<RectF>, highlighted: List<RectF> = emptyList()) {
        normalRects = normal
        highlightRects = highlighted
        invalidate()
    }

    fun clear() {
        normalRects = emptyList()
        highlightRects = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val displayRect = photoView?.displayRect ?: return
        val dw = displayRect.width()
        val dh = displayRect.height()
        val dl = displayRect.left
        val dt = displayRect.top
        for (face in normalRects) {
            canvas.drawRect(dl + face.left * dw, dt + face.top * dh,
                            dl + face.right * dw, dt + face.bottom * dh, normalPaint)
        }
        for (face in highlightRects) {
            canvas.drawRect(dl + face.left * dw, dt + face.top * dh,
                            dl + face.right * dw, dt + face.bottom * dh, highlightPaint)
        }
    }
}
