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
    private val contacts = TouchContacts { id, point, down, move -> onTouch(id, point, down, move) }
    var onTouch: (Int, Point, Boolean, Boolean) -> Unit = { _, _, _, _ -> }
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
    fun releaseTouch() { contacts.release() }
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
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) releaseTouch()
                val i = event.actionIndex
                val p = g.toPanel(event.getX(i), event.getY(i), width, height)
                if (p != null) contacts.down(event.getPointerId(i), p)
                if (event.actionMasked == MotionEvent.ACTION_DOWN && p == null) return false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val p = g.toPanel(event.getX(i), event.getY(i), width, height, true) ?: continue
                    contacts.move(event.getPointerId(i), p)
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val i = event.actionIndex
                val p = g.toPanel(event.getX(i), event.getY(i), width, height, true)
                contacts.up(event.getPointerId(i), p)
                if (event.actionMasked == MotionEvent.ACTION_UP) { releaseTouch(); performClick() }
            }
            MotionEvent.ACTION_CANCEL -> releaseTouch()
        }
        return true
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { releaseTouch(); super.onSizeChanged(w, h, oldw, oldh) }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { releaseTouch(); super.onDetachedFromWindow() }
}
