package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.MenuItem
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.streamflixreborn.streamflix.profiles.ProfileManager

/**
 * El icono de "Ajustes" del menú pasa a ser el avatar del perfil activo (redondo, con borde).
 * Se vuelve a aplicar al volver a la pantalla, así cambia junto con el perfil.
 */
object ProfileAvatarIcon {

    fun apply(context: Context, item: MenuItem?) {
        item ?: return
        val url = ProfileManager.activeProfile(context)?.avatar?.takeIf { it.isNotBlank() } ?: return
        val app = context.applicationContext
        val size = (48 * app.resources.displayMetrics.density).toInt()
        Glide.with(app).asBitmap().load(url).circleCrop().into(object : CustomTarget<Bitmap>(size, size) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                item.icon = AvatarDrawable(resource, (24 * app.resources.displayMetrics.density).toInt())
            }
            override fun onLoadCleared(placeholder: Drawable?) = Unit
        })
    }

    /** Avatar circular que no acepta el tinte del menú (si no, quedaría de un solo color). */
    private class AvatarDrawable(bitmap: Bitmap, private val sizePx: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
        private val bitmapSize = bitmap.width.toFloat()

        override fun draw(canvas: Canvas) {
            val r = minOf(bounds.width(), bounds.height()) / 2f
            canvas.save()
            canvas.translate(bounds.exactCenterX() - r, bounds.exactCenterY() - r)
            canvas.save()
            canvas.scale(2 * r / bitmapSize, 2 * r / bitmapSize)
            canvas.drawCircle(bitmapSize / 2, bitmapSize / 2, bitmapSize / 2, paint)
            canvas.restore()
            ring.strokeWidth = r * 0.12f
            canvas.drawCircle(r, r, r - ring.strokeWidth / 2, ring)
            canvas.restore()
        }

        override fun getIntrinsicWidth() = sizePx
        override fun getIntrinsicHeight() = sizePx
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; ring.alpha = alpha }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        override fun setTintList(tint: ColorStateList?) = Unit
        override fun setTintMode(tintMode: PorterDuff.Mode?) = Unit
    }
}
