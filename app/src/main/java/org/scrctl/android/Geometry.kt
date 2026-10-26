package org.scrctl.android

import kotlin.math.min

data class Point(val x: Float, val y: Float)
data class FitRect(val left: Float, val top: Float, val width: Float, val height: Float)
data class Geometry(val width: Int, val height: Int, val panelWidth: Int, val panelHeight: Int,
                    val rotation: Int?, val screenshot: Boolean = false) {
    val cropWidth: Int get() = if (screenshot) width else min(width, panelWidth.coerceAtLeast(1))
    val cropHeight: Int get() = if (screenshot) height else min(height, panelHeight.coerceAtLeast(1))
    val displayRotation: Int get() = if (screenshot) 0 else rotation ?: 0
    val swapped: Boolean get() = displayRotation == 90 || displayRotation == 270
    fun fit(viewWidth: Int, viewHeight: Int): FitRect {
        val w = (if (swapped) cropHeight else cropWidth).coerceAtLeast(1).toFloat()
        val h = (if (swapped) cropWidth else cropHeight).coerceAtLeast(1).toFloat()
        val scale = min(viewWidth / w, viewHeight / h)
        return FitRect((viewWidth - w * scale) / 2, (viewHeight - h * scale) / 2, w * scale, h * scale)
    }
    fun toPanel(x: Float, y: Float, viewWidth: Int, viewHeight: Int, clamp: Boolean = false): Point? {
        if (rotation == null || panelWidth <= 0 || panelHeight <= 0) return null
        val pixelSwapped = rotation == 90 || rotation == 270
        if (screenshot && (width != (if (pixelSwapped) panelHeight else panelWidth) ||
                    height != (if (pixelSwapped) panelWidth else panelHeight))) return null
        if (!screenshot && (width < panelWidth || height < panelHeight)) return null
        val rect = fit(viewWidth, viewHeight)
        if (rect.width <= 0 || rect.height <= 0) return null
        val u = (x - rect.left) / rect.width
        val v = (y - rect.top) / rect.height
        if (!clamp && (u < 0 || u > 1 || v < 0 || v > 1)) return null
        return inverseRotation(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f), rotation)
    }
    companion object {
        fun inverseRotation(u: Float, v: Float, degrees: Int): Point = when (degrees) {
            90 -> Point(v, 1 - u)
            180 -> Point(1 - u, 1 - v)
            270 -> Point(1 - v, u)
            else -> Point(u, v)
        }
        fun rotation(value: String): Int? = when (value) {
            "rot0" -> 0; "rot90" -> 90; "rot180" -> 180; "rot270" -> 270; else -> null
        }
    }
}
