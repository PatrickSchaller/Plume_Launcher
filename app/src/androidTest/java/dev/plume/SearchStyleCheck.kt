package dev.plume

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.view.View

/** Shared search styling must keep remote navigation usable and stop drawing when the screen is still. */
object SearchStyleCheck {
    @JvmStatic fun run(activity: Activity) {
        val root = SearchStyle.root(activity)
        check((0 until root.childCount).all { !root.getChildAt(it).isFocusable }) {
            "Search background must not take D-pad focus."
        }
        val tile = object : View(activity) {
            var invalidations = 0
            override fun invalidate() { invalidations++; super.invalidate() }
        }
        var clicks = 0
        tile.setOnClickListener { clicks++ }
        SearchStyle.focus(tile)
        tile.performClick()
        check(clicks == 1) { "Shared focus styling replaced the tile action." }

        tile.background = SearchStyle.panel(activity, 16f, false)
        tile.layout(0, 0, 160, 80)
        val outline = Outline()
        tile.background.getOutline(outline)
        check(outline.canClip() && outline.radius > 0f) { "Search panel must clip artwork to rounded corners." }
        val pixels = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(pixels)
            tile.draw(canvas)
            val afterFirstPaint = tile.invalidations
            tile.draw(canvas)
            check(tile.invalidations == afterFirstPaint) {
                "A still search panel keeps invalidating itself."
            }
            val idleEdge = pixels.getPixel(1, 40)
            tile.background.state = intArrayOf(android.R.attr.state_focused)
            tile.draw(canvas)
            check(pixels.getPixel(1, 40) != idleEdge) { "Focused search panels need a visible rim." }
        } finally { pixels.recycle() }
    }
}
