package org.scrctl.android

import org.junit.Assert.*
import org.junit.Test

class GeometryTest {
    private fun assertPoint(expected: Point, actual: Point?) {
        assertNotNull(actual)
        assertEquals(expected.x, actual!!.x, 0.00001f)
        assertEquals(expected.y, actual.y, 0.00001f)
    }
    @Test fun portraitPaddingAndLetterbox() {
        val g = Geometry(1136, 2464, 1125, 2436, 0)
        assertEquals(1125, g.cropWidth)
        assertEquals(2436, g.cropHeight)
        assertNull(g.toPanel(0f, 500f, 1000, 1000))
        assertPoint(Point(.5f, .5f), g.toPanel(500f, 500f, 1000, 1000))
    }
    @Test fun rotatedCornersMatchPhoneAxes() {
        val expected = mapOf(0 to Point(0f,0f), 90 to Point(0f,1f),
            180 to Point(1f,1f), 270 to Point(1f,0f))
        expected.forEach { (degrees, corner) ->
            val g = Geometry(1136,2464,1125,2436,degrees)
            val fit = g.fit(1000,1000)
            assertPoint(corner, g.toPanel(fit.left,fit.top,1000,1000))
            assertPoint(Point(.5f,.5f),g.toPanel(500f,500f,1000,1000))
        }
    }
    @Test fun unknownOrInconsistentGeometryCannotControl() {
        assertNull(Geometry(1136,2464,1125,2436,null).toPanel(1f,1f,100,100))
        assertNull(Geometry(100,200,1125,2436,0).toPanel(50f,50f,100,100))
        assertNull(Geometry(1125,2436,1125,2436,90,true).toPanel(50f,50f,100,100))
    }
    @Test fun uprightScreenshotInvertsOnlyItsPixelRotation() {
        val g=Geometry(2436,1125,1125,2436,90,true)
        assertEquals(0,g.displayRotation)
        val fit=g.fit(1000,1000)
        assertPoint(Point(0f,1f),g.toPanel(fit.left,fit.top,1000,1000))
        assertPoint(Point(.5f,.5f),g.toPanel(500f,500f,1000,1000))
    }
    @Test fun activeDragClampsOutsideViewButDoesNotStartInBars() {
        val g=Geometry(100,200,100,200,0)
        assertNull(g.toPanel(-100f,500f,1000,1000))
        assertPoint(Point(0f,.5f),g.toPanel(-100f,500f,1000,1000,true))
    }
}
