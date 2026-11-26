package org.vaachak.reader.leisure.ui.comics

import kotlin.math.max
import kotlin.math.min

/** IMAGE scaling modes; no mode stretches or changes the artwork's aspect ratio. */
enum class ComicZoomFit { PAGE, WIDTH, FILL }

/** Viewport calculations are pure Kotlin so edge cases can be tested without Compose. */
internal object ComicZoomMath {
    const val MAX_ZOOM = 5f

    data class Geometry(val scale: Float, val maxPanX: Float, val maxPanY: Float)

    fun geometry(
        imageWidth: Int, imageHeight: Int,
        viewportWidth: Float, viewportHeight: Float,
        fit: ComicZoomFit, zoom: Float
    ): Geometry {
        require(imageWidth > 0 && imageHeight > 0 && viewportWidth > 0f && viewportHeight > 0f)
        val nativeFit = min(viewportWidth / imageWidth, viewportHeight / imageHeight)
        val fittedWidth = imageWidth * nativeFit
        val fittedHeight = imageHeight * nativeFit
        val extraScale = when (fit) {
            ComicZoomFit.PAGE -> 1f
            ComicZoomFit.WIDTH -> viewportWidth / fittedWidth
            ComicZoomFit.FILL -> max(viewportWidth / fittedWidth, viewportHeight / fittedHeight)
        }
        val scale = extraScale * zoom.coerceIn(1f, MAX_ZOOM)
        return Geometry(
            scale = scale,
            maxPanX = max(0f, (fittedWidth * scale - viewportWidth) / 2f).let { if (it < 0.5f) 0f else it },
            maxPanY = max(0f, (fittedHeight * scale - viewportHeight) / 2f).let { if (it < 0.5f) 0f else it }
        )
    }
}
