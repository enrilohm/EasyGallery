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
    private var faceRects: List<RectF> = emptyList()

    private val boxPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    fun attach(pv: PhotoView) {
        photoView = pv
        pv.setOnMatrixChangeListener { invalidate() }
    }

    fun setFaces(rects: List<RectF>) {
        faceRects = rects
        invalidate()
    }

    fun clear() {
        faceRects = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val displayRect = photoView?.displayRect ?: return
        for (face in faceRects) {
            canvas.drawRect(
                displayRect.left + face.left  * displayRect.width(),
                displayRect.top  + face.top   * displayRect.height(),
                displayRect.left + face.right * displayRect.width(),
                displayRect.top  + face.bottom * displayRect.height(),
                boxPaint
            )
        }
    }
}
