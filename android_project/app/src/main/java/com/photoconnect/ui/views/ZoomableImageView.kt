package com.photoconnect.ui.views

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val baseMatrix = Matrix()
    private val drawMatrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val displayRect = RectF()

    private var userScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false

    private val minScale = 1f
    private val maxScale = 4f

    init {
        super.setScaleType(ScaleType.MATRIX)
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { resetZoom() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            post { resetZoom() }
        }
    }

    fun resetZoom() {
        val d = drawable ?: return
        val viewW = width - paddingLeft - paddingRight
        val viewH = height - paddingTop - paddingBottom
        val drawableW = max(1, d.intrinsicWidth)
        val drawableH = max(1, d.intrinsicHeight)
        if (viewW <= 0 || viewH <= 0) return

        baseMatrix.reset()
        val scale = min(viewW.toFloat() / drawableW.toFloat(), viewH.toFloat() / drawableH.toFloat())
        val dx = paddingLeft + (viewW - drawableW * scale) / 2f
        val dy = paddingTop + (viewH - drawableH * scale) / 2f
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(dx, dy)

        userScale = 1f
        drawMatrix.set(baseMatrix)
        imageMatrix = drawMatrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (drawable == null) return super.onTouchEvent(event)

        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(userScale > minScale)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && userScale > minScale) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (!isDragging || dx != 0f || dy != 0f) {
                        drawMatrix.postTranslate(dx, dy)
                        fixTranslation()
                        imageMatrix = drawMatrix
                        lastX = event.x
                        lastY = event.y
                        isDragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(userScale > minScale)
                isDragging = false
            }
        }

        return true
    }

    private fun fixTranslation() {
        val rect = getDisplayRect() ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()

        var deltaX = 0f
        var deltaY = 0f

        if (rect.width() <= viewW) {
            deltaX = (viewW - rect.width()) / 2f - rect.left
        } else {
            if (rect.left > 0f) deltaX = -rect.left
            if (rect.right < viewW) deltaX = viewW - rect.right
        }

        if (rect.height() <= viewH) {
            deltaY = (viewH - rect.height()) / 2f - rect.top
        } else {
            if (rect.top > 0f) deltaY = -rect.top
            if (rect.bottom < viewH) deltaY = viewH - rect.bottom
        }

        drawMatrix.postTranslate(deltaX, deltaY)
    }

    private fun getDisplayRect(): RectF? {
        val d = drawable ?: return null
        displayRect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        drawMatrix.mapRect(displayRect)
        return displayRect
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val current = userScale
            val target = (current * detector.scaleFactor).coerceIn(minScale, maxScale)
            val factor = target / current
            drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            userScale = target
            fixTranslation()
            imageMatrix = drawMatrix
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
    }
}
