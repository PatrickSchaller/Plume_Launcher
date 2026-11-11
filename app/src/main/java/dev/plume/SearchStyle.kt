package dev.plume

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import java.lang.ref.WeakReference

/** Search shares the home's existing pixels; it never decodes or retains another full-size wallpaper. */
object SearchStyle {
    private var wallpaper = WeakReference<Bitmap>(null)
    private var frost = WeakReference<Bitmap>(null)

    @JvmStatic fun capture(drawable: Drawable?, blur: Bitmap?) {
        wallpaper = WeakReference(bitmap(drawable)?.takeUnless { it.isRecycled })
        frost = WeakReference(blur?.takeUnless { it.isRecycled })
    }

    @JvmStatic fun root(activity: Activity): FrameLayout = FrameLayout(activity).apply {
        setBackgroundColor(if (dark(activity)) 0xFF08090B.toInt() else 0xFFF3F5F7.toInt())
        wallpaper.get()?.takeUnless { it.isRecycled }?.let { image ->
            addView(ImageView(activity).apply {
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(image)
            }, FrameLayout.LayoutParams(-1, -1))
        }
        addView(View(activity).apply {
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = ColorDrawable(if (dark(activity)) 0x8806090D.toInt() else 0xA6F3F5F7.toInt())
        }, FrameLayout.LayoutParams(-1, -1))
    }

    @JvmStatic fun dark(context: Context): Boolean = prefersDark(context.getSharedPreferences("home", Context.MODE_PRIVATE))
    @JvmStatic fun ink(context: Context): Int = if (dark(context)) 0xFFF5F6F8.toInt() else 0xFF111214.toInt()
    @JvmStatic fun muted(context: Context): Int = if (dark(context)) 0xFFB8BDC5.toInt() else 0xFF515965.toInt()
    @JvmStatic fun dp(context: Context, value: Float): Int = (value * context.resources.displayMetrics.density + .5f).toInt()
    @JvmStatic fun panel(context: Context, radiusDp: Float, selected: Boolean): Drawable = Panel(context, radiusDp, selected)

    @JvmStatic fun focus(view: View) {
        // TV scroll containers clip zoomed edges; the panel's lit surface and rim carry focus.
        view.isFocusable = true
    }

    private fun bitmap(drawable: Drawable?): Bitmap? = when (drawable) {
        is BitmapDrawable -> drawable.bitmap
        is LayerDrawable -> (drawable.numberOfLayers - 1 downTo 0).firstNotNullOfOrNull { bitmap(drawable.getDrawable(it)) }
        else -> null
    }

    private class Panel(private val context: Context, private val radiusDp: Float, private val selected: Boolean) : Drawable() {
        private var host = WeakReference<View>(null)
        private var glass: GlassDrawable? = null
        private var focused = false
        private var appliedLook: GlassLook? = null
        private var appliedLit = false
        private var appliedBlur: Bitmap? = null
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rimBounds = RectF()

        override fun getOutline(outline: Outline) { outline.setRoundRect(bounds, dp(context, radiusDp).toFloat()) }

        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean {
            val next = android.R.attr.state_focused in state
            if (next == focused) return false
            focused = next
            invalidateSelf()
            return true
        }

        override fun draw(canvas: Canvas) {
            var owner = callback
            while (owner is Drawable) owner = owner.callback
            val view = owner as? View ?: return
            if (host.get() !== view) {
                host = WeakReference(view)
                glass = GlassDrawable(view)
                appliedLook = null
                appliedBlur = null
            }
            val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
            val isDark = dark(context)
            val look = GlassLook(GlassStyle.of(prefs.getString("glass_style", null), prefs.getBoolean("glass", true)),
                isDark, if (wallpaper.get() == null) Backdrop.PLAIN else Backdrop.PICTURE,
                prefs.getInt("glass_hue", GLASS_GREY), prefs.getString("theme", null) == "wallpaper")
            val lit = focused || selected
            val blur = if (look.active == null) null else frost.get()?.takeUnless { it.isRecycled }
            val surface = glass!!
            if (look != appliedLook || lit != appliedLit) {
                surface.round = dp(context, radiusDp).toFloat()
                surface.ref = (context as? Activity)?.findViewById(android.R.id.content)
                surface.wash = if (lit) (look.wash() and 0xFFFFFF) or (0xD9 shl 24) else look.wash()
                surface.highlight = if (lit) { if (isDark) 0x38FFFFFF else 0xAAFFFFFF.toInt() }
                    else if (isDark) 0x26FFFFFF else 0x66FFFFFF
                appliedLook = look
                appliedLit = lit
            }
            // setBlur invalidates the host, so calling it on every draw would keep the screen redrawing.
            if (blur !== appliedBlur) { surface.setBlur(blur); appliedBlur = blur }
            surface.bounds = bounds
            surface.draw(canvas)
            if (focused || selected) {
                rim.strokeWidth = dp(context, if (focused) 2f else 1f).toFloat()
                rim.color = if (isDark) { if (focused) 0xE6FFFFFF.toInt() else 0x88FFFFFF.toInt() }
                    else if (focused) 0xE6111214.toInt() else 0x88111214.toInt()
                rimBounds.set(bounds)
                val inset = rim.strokeWidth / 2f
                rimBounds.inset(inset, inset)
                val radius = (surface.round - inset).coerceAtLeast(0f)
                canvas.drawRoundRect(rimBounds, radius, radius, rim)
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(filter: ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
