package org.lior.sixstep

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import androidx.core.view.WindowInsetsControllerCompat

/** The look of the user's layout draft (design/app_layout.free, 2026-10-28): flat pastel boxes with black outlines,
 *  the colours taken from the draft. */
object Ui {
    val LAUNCHER_BG = Color.rgb(0xF7, 0xE6, 0xE8)
    val PAGE_BG = Color.rgb(0xF7, 0xD1, 0xD5)      // explore and the heart page
    val TITLE = Color.rgb(0xF9, 0xE2, 0x97)        // the draft's FFDD00 at 35 % over the launcher's pink
    val DAILY = Color.rgb(0xBF, 0x81, 0x9E)
    val EXPLORE = Color.rgb(0xB4, 0xC7, 0xDC)
    val HEART = Color.rgb(0xFF, 0xA6, 0xA6)
    val GEAR = Color.rgb(0xDB, 0xDB, 0xDB)
    val HELP = Color.rgb(0xBE, 0xD1, 0xBA)
    val ARROW = Color.rgb(0x00, 0x38, 0x69)        // the help view's arrows
    val BAR = Color.rgb(0xD3, 0xD3, 0xD3)          // the dance floor's bars
    val ROW = Color.rgb(0xF7, 0xC1, 0xC7)          // the explore rows' names (the draft, revised 2026-10-28)...
    val ROW_EDGE = Color.argb(0x75, 0x61, 0x56, 0x56)   // ...and their thin outline
    val VERSION = Color.rgb(0xE6, 0xAE, 0xB5)      // an explore row's version box (where it starts and ends)
    val NOTE = Color.rgb(0xBA, 0xB1, 0x86)         // the annotations in the dance floor's bottom bar
    val HEART_BOX = Color.rgb(0xF8, 0xBE, 0xC2)

    fun dp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    /** a box: a fill, a black outline, slightly rounded corners (or each its own: top left, top right, bottom right,
     *  bottom left); with a ripple when it is something to press */
    fun box(ctx: Context, fill: Int, strokeDp: Float = 2.5f, radiusDp: Float = 6f, stroke: Int = Color.BLACK, ripple: Boolean = true,
            radiiDp: FloatArray? = null): Drawable {
        val g = GradientDrawable()
        g.setColor(fill)
        if (radiiDp != null) g.cornerRadii = FloatArray(8) { dp(ctx, radiiDp[it / 2]) } else g.cornerRadius = dp(ctx, radiusDp)
        if (strokeDp > 0f) g.setStroke(dp(ctx, strokeDp).toInt().coerceAtLeast(1), stroke)
        return if (ripple) RippleDrawable(ColorStateList.valueOf(0x33000000), g, null) else g
    }

    /** a raised box, something to press: the fill with a black outline over a solid black shadow `offsetDp` down and to
     *  the right (option A of the explore picker, chosen by the user 2026-10-29). The view's padding leaves room for the
     *  shadow at the right and the bottom. */
    fun raised(ctx: Context, fill: Int, radiusDp: Float = 12f, offsetDp: Float = 3f, ripple: Boolean = true): Drawable {
        val r = dp(ctx, radiusDp); val off = dp(ctx, offsetDp).toInt()
        val shadow = GradientDrawable(); shadow.setColor(Color.BLACK); shadow.cornerRadius = r
        val face = GradientDrawable(); face.setColor(fill); face.cornerRadius = r; face.setStroke(dp(ctx, 2f).toInt(), Color.BLACK)
        val layers = LayerDrawable(arrayOf<Drawable>(shadow, face))
        layers.setLayerInset(0, off, off, 0, 0)
        layers.setLayerInset(1, 0, 0, off, off)
        return if (ripple) RippleDrawable(ColorStateList.valueOf(0x33000000), layers, null) else layers
    }

    /** the status and navigation bars in the page's colour, with dark icons */
    fun bars(a: Activity, color: Int) {
        a.window.statusBarColor = color
        a.window.navigationBarColor = color
        val c = WindowInsetsControllerCompat(a.window, a.window.decorView)
        c.isAppearanceLightStatusBars = true
        c.isAppearanceLightNavigationBars = true
    }
}
