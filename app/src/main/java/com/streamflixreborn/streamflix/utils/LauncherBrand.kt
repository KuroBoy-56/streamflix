package com.streamflixreborn.streamflix.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.activities.sync.SyncGatewayActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Icono y nombre del lanzador según la red de la cuenta: en el manifiesto, ".Launcher" es el del
 * dueño y cada ".Admin<APP ID>" el de un administrador con marca blanca. El cambio se guarda y
 * se aplica cuando la app pasa a segundo plano: cambiarlo con la app abierta puede cerrarla.
 */
object LauncherBrand {
    private const val PREFS = "SecureGatewayPrefs"
    private const val KEY_PENDING = "launcher_pending_app_id"
    private const val KEY_SHORTCUT = "launcher_shortcut_app_id"

    private class Alias(val appId: Int, val component: ComponentName, val manifestEnabled: Boolean)

    private fun aliases(context: Context): List<Alias> {
        val target = SyncGatewayActivity::class.java.name
        @Suppress("DEPRECATION")
        return context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES or PackageManager.GET_DISABLED_COMPONENTS)
            .activities.orEmpty()
            .filter { it.targetActivity == target && it.name.startsWith("$target.") }
            .mapNotNull { info ->
                val suffix = info.name.removePrefix("$target.")
                val id = if (suffix == "Launcher") 0 else suffix.removePrefix("Admin").takeIf { suffix.startsWith("Admin") }?.toIntOrNull()
                id?.let { Alias(it, ComponentName(context, info.name), info.enabled) }
            }
    }

    private fun wanted(aliases: List<Alias>, appId: Int): Alias? =
        aliases.firstOrNull { it.appId == appId && appId != 0 } ?: aliases.firstOrNull { it.appId == 0 }

    // Estado real: lo elegido en el equipo o, si nunca se cambió, lo del manifiesto.
    private fun isOn(pm: PackageManager, alias: Alias) = when (pm.getComponentEnabledSetting(alias.component)) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> alias.manifestEnabled
        else -> false
    }

    /** El icono actual no es el de esta red y el cambio aún no está programado. */
    fun needsChange(context: Context, appId: Int): Boolean {
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PENDING, -1) == appId) return false
        return runCatching {
            val all = aliases(context)
            val target = wanted(all, appId)
            target != null && !all.all { isOn(context.packageManager, it) == (it === target) }
        }.getOrDefault(false)
    }

    fun schedule(context: Context, appId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_PENDING, appId).apply()
    }

    /** Llamar con la app en segundo plano. */
    fun applyPending(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val appId = prefs.getInt(KEY_PENDING, -1)
        if (appId < 0) return
        prefs.edit().remove(KEY_PENDING).apply()
        runCatching {
            val pm = context.packageManager
            val all = aliases(context)
            val target = wanted(all, appId) ?: return
            pm.setComponentEnabledSetting(target.component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            all.filter { it !== target }.forEach {
                pm.setComponentEnabledSetting(it.component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }.onFailure { it.printStackTrace() }
    }

    // ---------------------------------------------------------------------------------------
    // Celular: Android no deja usar una imagen descargada como icono de la app, pero sí un
    // acceso directo en la pantalla de inicio con el logo y el nombre que el administrador puso
    // en "Logo App" del panel. El sistema pide confirmarlo ("Agregar a pantalla de inicio").
    // ---------------------------------------------------------------------------------------

    private fun isTv(context: Context) = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** Falta ofrecer el acceso directo con la marca de esta red (solo celular y con logo en el panel). */
    fun shortcutPending(context: Context, appId: Int): Boolean =
        appId != 0 && !isTv(context) &&
            BrandAssets.url(context, BrandAssets.Kind.LOGO).isNotEmpty() &&
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SHORTCUT, -1) != appId &&
            ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    private suspend fun shortcut(context: Context, appId: Int): ShortcutInfoCompat? {
        val logo = withContext(Dispatchers.IO) { BrandAssets.bitmap(context, BrandAssets.Kind.LOGO, 192) } ?: return null
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("brand_name", "").orEmpty()
            .ifBlank { context.getString(R.string.app_name) }
        return ShortcutInfoCompat.Builder(context, "brand_$appId")
            .setShortLabel(name)
            .setLongLabel(name)
            .setIcon(IconCompat.createWithBitmap(logo))
            .setIntent(Intent(context, SyncGatewayActivity::class.java).setAction(Intent.ACTION_MAIN))
            .build()
    }

    /** Pide al sistema agregar el acceso directo. Devuelve true si se mostró la confirmación. */
    suspend fun requestShortcut(context: Context, appId: Int): Boolean {
        val info = shortcut(context, appId) ?: return false
        val shown = runCatching { ShortcutManagerCompat.requestPinShortcut(context, info, null) }.getOrDefault(false)
        if (shown) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SHORTCUT, appId).apply()
        return shown
    }

    /** Si el administrador cambia el logo o el nombre en el panel, el acceso directo se actualiza. */
    suspend fun updateShortcut(context: Context) {
        val appId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SHORTCUT, -1)
        if (appId <= 0 || appId != UserPreferences.savedAppId) return
        val info = shortcut(context, appId) ?: return
        runCatching { ShortcutManagerCompat.updateShortcuts(context, listOf(info)) }
    }
}
