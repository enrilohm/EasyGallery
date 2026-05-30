package com.example.easygallery.gallery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.example.easygallery.R
import com.github.chrisbanes.photoview.PhotoView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Page container for the image detail view.
 *
 * Touch model (only when [selectionEnabled]):
 *  - one finger        -> PhotoView pan + ViewPager paging (untouched)
 *  - two-finger pinch  -> PhotoView zoom (untouched)
 *  - two-finger TAP    -> selects the rectangle spanned by the two fingers
 *                         (place a finger on each opposite corner and lift)
 *
 * Detection is passive: events are only observed in [onInterceptTouchEvent]
 * (which always returns false), so PhotoView keeps full control of zoom/pan.
 * A two-finger gesture is a "tap" only if neither finger moved beyond touch
 * slop — anything with movement is a pinch and is ignored here.
 *
 * The selected rectangle persists (anchored to the image, tracking zoom/pan)
 * and is exposed as [selectionCrop] in normalized image coords (0..1). Nothing
 * is searched automatically; the caller reads [selectionCrop] on button press.
 */
class CropFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var selectionEnabled = false

    var selectionCrop: RectF? = null
        set(value) {
            field = value
            invalidate()
        }

    private var photoView: PhotoView? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Live two-finger gesture state.
    private var twoFingerActive = false
    private var moved = false
    private var dx0 = 0f; private var dy0 = 0f   // finger 0 down position
    private var dx1 = 0f; private var dy1 = 0f   // finger 1 down position
    private val preview = RectF()

    // Single-finger tap state — a tap (no movement, never became multi-touch)
    // clears the current selection.
    private var singleDownX = 0f
    private var singleDownY = 0f
    private var singleTapCandidate = false

    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val dimPaint = Paint().apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        photoView = findViewById(R.id.photoView)
        photoView?.setOnMatrixChangeListener { if (!twoFingerActive) invalidate() }
    }

    // Observe in dispatch (not onInterceptTouchEvent): PhotoView calls
    // requestDisallowInterceptTouchEvent(true) during multi-touch, which would
    // suppress interception callbacks. dispatchTouchEvent always fires. We only
    // read the events and still pass them through, so zoom/pan are unaffected.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (selectionEnabled) observe(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun observe(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                singleDownX = ev.getX(0); singleDownY = ev.getY(0)
                singleTapCandidate = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                singleTapCandidate = false   // became multi-touch, not a single tap
                if (ev.pointerCount == 2) {
                    twoFingerActive = true
                    moved = false
                    dx0 = ev.getX(0); dy0 = ev.getY(0)
                    dx1 = ev.getX(1); dy1 = ev.getY(1)
                    preview.set(min(dx0, dx1), min(dy0, dy1), max(dx0, dx1), max(dy0, dy1))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (singleTapCandidate && ev.pointerCount == 1 &&
                    (abs(ev.getX(0) - singleDownX) > touchSlop ||
                            abs(ev.getY(0) - singleDownY) > touchSlop)
                ) {
                    // Moved — it's a pan, not a tap.
                    singleTapCandidate = false
                }
                if (twoFingerActive && ev.pointerCount >= 2) {
                    val cx0 = ev.getX(0); val cy0 = ev.getY(0)
                    val cx1 = ev.getX(1); val cy1 = ev.getY(1)
                    if (abs(cx0 - dx0) > touchSlop || abs(cy0 - dy0) > touchSlop ||
                        abs(cx1 - dx1) > touchSlop || abs(cy1 - dy1) > touchSlop
                    ) {
                        // It's a pinch, not a tap — don't make a selection.
                        moved = true
                    } else {
                        preview.set(min(cx0, cx1), min(cy0, cy1), max(cx0, cx1), max(cy0, cy1))
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (twoFingerActive) {
                    if (!moved) commitSelection()
                    twoFingerActive = false
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (twoFingerActive) {
                    if (!moved) commitSelection()
                    twoFingerActive = false
                    invalidate()
                } else if (singleTapCandidate && selectionCrop != null) {
                    // Single-finger tap with an active selection clears it.
                    selectionCrop = null
                }
                singleTapCandidate = false
            }
            MotionEvent.ACTION_CANCEL -> {
                twoFingerActive = false
                singleTapCandidate = false
                invalidate()
            }
        }
    }

    private fun commitSelection() {
        val display = photoView?.displayRect ?: return
        if (display.width() <= 0 || display.height() <= 0) return
        if (preview.width() < 24f || preview.height() < 24f) return
        val l = ((preview.left - display.left) / display.width()).coerceIn(0f, 1f)
        val t = ((preview.top - display.top) / display.height()).coerceIn(0f, 1f)
        val r = ((preview.right - display.left) / display.width()).coerceIn(0f, 1f)
        val b = ((preview.bottom - display.top) / display.height()).coerceIn(0f, 1f)
        selectionCrop = if (r - l > 0f && b - t > 0f) RectF(l, t, r, b) else null
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // Only the committed selection is drawn — no live preview while the
        // fingers are down.
        val crop = selectionCrop ?: return
        val d = photoView?.displayRect ?: return
        val rect = RectF(
            d.left + crop.left * d.width(), d.top + crop.top * d.height(),
            d.left + crop.right * d.width(), d.top + crop.bottom * d.height()
        )
        canvas.drawRect(0f, 0f, width.toFloat(), rect.top, dimPaint)
        canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dimPaint)
        canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, dimPaint)
        canvas.drawRect(rect, borderPaint)
    }
}
