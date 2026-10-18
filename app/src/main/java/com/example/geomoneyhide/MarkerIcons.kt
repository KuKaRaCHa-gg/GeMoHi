package com.example.geomoneyhide

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Icônes de marqueurs dessinées à la main (Canvas), mises en cache.
 *
 * La pièce est centrée dans son bitmap : utiliser `anchor(0.5f, 0.5f)` sur le MarkerOptions.
 * À n'appeler qu'une fois la carte prête (BitmapDescriptorFactory doit être initialisée).
 */
object MarkerIcons {

    @Volatile
    private var coinDescriptor: BitmapDescriptor? = null

    /** Pièce d'or : dégradé radial, tranche, liseré, étoile en relief et ombre douce. */
    fun coin(context: Context): BitmapDescriptor {
        coinDescriptor?.let { return it }
        return synchronized(this) {
            coinDescriptor ?: BitmapDescriptorFactory
                .fromBitmap(drawCoin(context.applicationContext))
                .also { coinDescriptor = it }
        }
    }

    private fun drawCoin(context: Context): Bitmap {
        val density = context.resources.displayMetrics.density
        val diameter = context.resources.getDimension(R.dimen.marker_coin_size)
        val pad = 5f * density                       // place pour l'ombre et la tranche
        val sizePx = (diameter + 2f * pad).roundToInt()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val r = diameter / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Ombre portée douce (décalée vers le bas)
        paint.color = Color.argb(110, 0, 0, 0)
        paint.maskFilter = BlurMaskFilter(3f * density, BlurMaskFilter.Blur.NORMAL)
        canvas.drawCircle(cx, cy + 2.5f * density, r, paint)
        paint.maskFilter = null

        // 2. Tranche de la pièce (épaisseur)
        paint.color = 0xFF8A5A00.toInt()
        canvas.drawCircle(cx, cy + 1.5f * density, r, paint)

        // 3. Face : dégradé radial or
        paint.shader = RadialGradient(
            cx - r * 0.35f, cy - r * 0.4f, r * 1.45f,
            intArrayOf(0xFFFFF4C2.toInt(), 0xFFFFC83D.toInt(), 0xFFE09A00.toInt(), 0xFFB97800.toInt()),
            floatArrayOf(0f, 0.38f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null

        // 4. Liseré extérieur sombre (contraste sur la carte) + anneau intérieur gravé
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        stroke.strokeWidth = 1.4f * density
        stroke.color = 0xFF7A4E00.toInt()
        canvas.drawCircle(cx, cy, r - stroke.strokeWidth / 2f, stroke)

        val innerR = r * 0.74f
        stroke.strokeWidth = 1.2f * density
        stroke.color = 0x99A86E00.toInt()
        canvas.drawCircle(cx, cy + 0.6f * density, innerR, stroke)
        stroke.color = 0x99FFF1B0.toInt()
        canvas.drawCircle(cx, cy - 0.6f * density, innerR, stroke)

        // 5. Étoile en relief (ombre sombre puis face claire)
        val starOuter = r * 0.46f
        val starInner = starOuter * 0.42f
        paint.color = 0xFF9A6400.toInt()
        canvas.drawPath(starPath(cx, cy + 1.1f * density, starOuter, starInner), paint)
        paint.shader = LinearGradient(
            cx, cy - starOuter, cx, cy + starOuter,
            0xFFFFFBE6.toInt(), 0xFFFFD766.toInt(),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(starPath(cx, cy, starOuter, starInner), paint)
        paint.shader = null

        // 6. Reflet brillant en haut à gauche
        stroke.strokeWidth = 1.8f * density
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = Color.argb(170, 255, 255, 255)
        val hr = r * 0.86f
        canvas.drawArc(RectF(cx - hr, cy - hr, cx + hr, cy + hr), 200f, 55f, false, stroke)

        return bitmap
    }

    private fun starPath(cx: Float, cy: Float, outer: Float, inner: Float): Path {
        val path = Path()
        for (i in 0 until 10) {
            val angle = Math.toRadians(-90.0 + i * 36.0)
            val radius = if (i % 2 == 0) outer else inner
            val x = cx + (radius * cos(angle)).toFloat()
            val y = cy + (radius * sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }
}
