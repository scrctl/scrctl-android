package org.scrctl.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import java.util.concurrent.atomic.AtomicReference

data class VideoFrame(val bitmap: Bitmap, val geometry: Geometry, val generation: Long)
class MirrorView(context: Context) : View(context) {
    var acceptsFrame: (Long) -> Boolean = { false }
    private val pending = AtomicReference<VideoFrame?>()
    private var displayed: VideoFrame? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var contact: Point? = null
    var onTouch: (Point, Boolean, Boolean) -> Unit = { _, _, _ -> }
    init { setBackgroundColor(Color.BLACK); contentDescription = "iPhone mirror" }
    fun submit(frame: VideoFrame) {
        pending.getAndSet(frame)?.bitmap?.recycle()
        postInvalidateOnAnimation()
    }
    fun clear() {
        releaseTouch()
        pending.getAndSet(null)?.bitmap?.recycle()
        displayed = null
        invalidate()
    }
    fun releaseTouch() { contact?.let { onTouch(it, false, false) }; contact = null }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        pending.getAndSet(null)?.let {
            if (!acceptsFrame(it.generation)) { it.bitmap.recycle(); return@let }
            if (displayed?.geometry != it.geometry) releaseTouch()
            displayed = it
        }
        val frame = displayed ?: return
        val g = frame.geometry
        val fit = g.fit(width, height)
        val targetWidth = if (g.swapped) fit.height else fit.width
        val targetHeight = if (g.swapped) fit.width else fit.height
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.rotate(g.displayRotation.toFloat())
        canvas.drawBitmap(frame.bitmap, Rect(0, 0, g.cropWidth, g.cropHeight),
            RectF(-targetWidth / 2, -targetHeight / 2, targetWidth / 2, targetHeight / 2), paint)
        canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val g = displayed?.geometry ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val p = g.toPanel(event.x, event.y, width, height) ?: return false
                parent.requestDisallowInterceptTouchEvent(true)
                contact = p; onTouch(p, true, false)
            }
            MotionEvent.ACTION_MOVE -> {
                if (contact == null) return false
                val p = g.toPanel(event.x, event.y, width, height, true) ?: return true
                contact = p; onTouch(p, true, true)
            }
            MotionEvent.ACTION_UP -> { releaseTouch(); performClick() }
            MotionEvent.ACTION_CANCEL -> releaseTouch()
        }
        return true
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { releaseTouch(); super.onSizeChanged(w, h, oldw, oldh) }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { releaseTouch(); super.onDetachedFromWindow() }
}
