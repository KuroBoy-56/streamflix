package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Logo, splash y fondos que sube cada administrador (o el dueño) en el panel.
 * Se guardan como archivos propios de la app (no se borran al limpiar la caché de imágenes)
 * y Glide los mantiene en memoria; solo se vuelven a descargar cuando el panel cambia la URL.
 */
object BrandAssets {

    enum class Kind(val prefKey: String) {
        LOGO("custom_logo_url"),
        SPLASH("custom_splash_url"),
        BG_MOBILE("custom_background_url"),
        BG_TV("custom_background_tv_url"),
    }

    private const val PREFS = "SecureGatewayPrefs"
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun url(context: Context, kind: Kind): String = when (kind) {
        Kind.LOGO -> UserPreferences.customLogoUrl
        else -> prefs(context).getString(kind.prefKey, "").orEmpty()
    }.replace("null", "").trim()

    private fun file(context: Context, kind: Kind) = File(context.filesDir, "brand/${kind.name.lowercase()}")

    /** Archivo local si corresponde a la URL actual; si no, la URL (y se descarga luego). */
    private fun model(context: Context, kind: Kind, url: String): Any {
        val f = file(context, kind)
        return if (f.length() > 0 && prefs(context).getString("brand_file_${kind.name}", "") == url) f else url
    }

    /** Carga la imagen de marca en [view]. Devuelve false si el panel no configuró ninguna. */
    fun load(view: ImageView, kind: Kind, configure: (RequestBuilder<*>) -> RequestBuilder<*> = { it }): Boolean {
        val context = view.context
        val url = url(context, kind)
        if (url.isEmpty()) return false
        val request = Glide.with(view)
            .load(model(context, kind, url))
            .signature(ObjectKey(url))
            .diskCacheStrategy(DiskCacheStrategy.ALL)
        configure(request).into(view)
        return true
    }

    /** Imagen de marca como bitmap (para el acceso directo). Llamar fuera del hilo principal. */
    fun bitmap(context: Context, kind: Kind, size: Int): android.graphics.Bitmap? {
        val url = url(context, kind)
        if (url.isEmpty()) return null
        return runCatching {
            Glide.with(context.applicationContext).asBitmap()
                .load(model(context, kind, url))
                .signature(ObjectKey(url))
                .centerInside()
                .submit(size, size)
                .get()
        }.getOrNull()
    }

    /** Fondo de inicio de sesión según el equipo (TV horizontal, celular vertical). */
    fun loginBackground(context: Context, isTv: Boolean): Kind? {
        val tv = url(context, Kind.BG_TV).isNotEmpty()
        val mobile = url(context, Kind.BG_MOBILE).isNotEmpty()
        return when {
            isTv && tv -> Kind.BG_TV
            !isTv && mobile -> Kind.BG_MOBILE
            tv -> Kind.BG_TV
            mobile -> Kind.BG_MOBILE
            else -> null
        }
    }

    /** Descarga las imágenes nuevas o cambiadas a archivos locales (se llama al iniciar sesión y en el vigilante). */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        Kind.entries.forEach { kind ->
            val url = url(context, kind)
            val f = file(context, kind)
            if (url.isEmpty()) {
                f.delete()
                prefs(context).edit().remove("brand_file_${kind.name}").apply()
                return@forEach
            }
            if (f.length() > 0 && prefs(context).getString("brand_file_${kind.name}", "") == url) return@forEach
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    val bytes = response.body?.bytes()
                    if (!response.isSuccessful || bytes == null || bytes.isEmpty()) return@use
                    f.parentFile?.mkdirs()
                    val tmp = File(f.parentFile, "${f.name}.tmp")
                    tmp.writeBytes(bytes)
                    if (tmp.renameTo(f) || runCatching { tmp.copyTo(f, overwrite = true); tmp.delete() }.isSuccess) {
                        prefs(context).edit().putString("brand_file_${kind.name}", url).apply()
                    }
                }
            }
            // También en memoria para que aparezca al instante.
            runCatching {
                Glide.with(context.applicationContext).load(model(context, kind, url)).signature(ObjectKey(url))
                    .diskCacheStrategy(DiskCacheStrategy.ALL).preload()
            }
        }
    }
}
