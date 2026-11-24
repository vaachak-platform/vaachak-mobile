package org.vaachak.reader.leisure.ui.comics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicZoomMathTest {
    @Test fun fitPageNeverCropsAtDefaultZoom() {
        val fit = ComicZoomMath.geometry(1000, 1500, 400f, 900f, ComicZoomFit.PAGE, 1f)
        assertEquals(1f, fit.scale, .0001f)
        assertEquals(0f, fit.maxPanX, .0001f)
        assertEquals(0f, fit.maxPanY, .0001f)
    }

    @Test fun fitWidthFillsWidthAndAllowsPanningWhenPageIsTall() {
        val fit = ComicZoomMath.geometry(600, 1500, 400f, 900f, ComicZoomFit.WIDTH, 1f)
        assertEquals(400f / 360f, fit.scale, .0001f)
        assertEquals(0f, fit.maxPanX, .0001f)
        assertTrue(fit.maxPanY > 0f)
    }

    @Test fun fillScreenEnlargesWithoutDistortingAndCanCropHorizontally() {
        val fit = ComicZoomMath.geometry(1000, 1400, 400f, 900f, ComicZoomFit.FILL, 1f)
        assertTrue(fit.scale > 1f)
        assertTrue(fit.maxPanX > 0f)
        assertEquals(0f, fit.maxPanY, .0001f)
    }

    @Test fun zoomIsCappedAndCanPanOnlyAsFarAsScaledImage() {
        val atMax = ComicZoomMath.geometry(1000, 1500, 400f, 900f, ComicZoomFit.PAGE, 5f)
        val beyond = ComicZoomMath.geometry(1000, 1500, 400f, 900f, ComicZoomFit.PAGE, 100f)
        assertEquals(atMax, beyond)
        assertTrue(atMax.maxPanX > 0f)
        assertTrue(atMax.maxPanY > 0f)
    }
}
