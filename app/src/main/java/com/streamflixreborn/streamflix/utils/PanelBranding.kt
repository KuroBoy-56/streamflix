package com.streamflixreborn.streamflix.utils

import android.content.Context
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.streamflixreborn.streamflix.R

/**
 * Textos del panel en Ajustes: la nota del administrador/dueño (a pantalla completa) en lugar
 * de los ajustes del proveedor, que no se muestran en la app.
 */
object PanelBranding {

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(AssignedProviders.PREFS_NAME, Context.MODE_PRIVATE)

    fun note(context: Context): String = prefs(context).getString("settings_note", "").orEmpty().trim()

    fun brand(context: Context): String? = prefs(context).getString("brand_name", "").orEmpty().trim().ifEmpty { null }

    /** "Aviso": la nota del panel en Ajustes; al elegirla se lee a pantalla completa. */
    fun bindNotePreference(fragment: PreferenceFragmentCompat) {
        val context = fragment.requireContext()
        val pref = fragment.findPreference<Preference>("p_settings_note") ?: return
        val note = note(context)
        pref.isVisible = note.isNotEmpty()
        if (note.isEmpty()) return
        brand(context)?.let { pref.title = it }
        pref.summary = note
        pref.setOnPreferenceClickListener {
            showNote(fragment.requireActivity(), pref.title?.toString().orEmpty(), note)
            true
        }
    }

    private fun showNote(activity: android.app.Activity, title: String, note: String) {
        val dialog = android.app.Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val dp = activity.resources.displayMetrics.density
        val column = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding((32 * dp).toInt(), (40 * dp).toInt(), (32 * dp).toInt(), (40 * dp).toInt())
        }
        android.widget.ImageView(activity).also { logo ->
            logo.adjustViewBounds = true
            if (BrandAssets.load(logo, BrandAssets.Kind.LOGO) { it.fitCenter() }) {
                column.addView(logo, android.widget.LinearLayout.LayoutParams((180 * dp).toInt(), (64 * dp).toInt()))
            }
        }
        column.addView(android.widget.TextView(activity).apply {
            text = title
            setTextColor(android.graphics.Color.WHITE)
            textSize = 26f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(0, (16 * dp).toInt(), 0, (20 * dp).toInt())
        })
        column.addView(android.widget.TextView(activity).apply {
            text = note
            setTextColor(android.graphics.Color.parseColor("#E5E5E5"))
            textSize = 20f
            setLineSpacing(0f, 1.25f)
            gravity = android.view.Gravity.CENTER
            setTextIsSelectable(true)
            android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS or android.text.util.Linkify.PHONE_NUMBERS or android.text.util.Linkify.EMAIL_ADDRESSES)
        })
        dialog.setContentView(android.widget.ScrollView(activity).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#141414"))
            isFillViewport = true
            isFocusable = true // TV: el control desplaza el texto y Atrás cierra
            addView(column)
        })
        dialog.show()
    }

    /** Resumen de "Ajustes del proveedor": la nota del panel, o la marca, sin nombre de proveedor. */
    fun providerSummary(context: Context): String =
        note(context).ifEmpty {
            brand(context)?.let { context.getString(R.string.settings_screen_provider_summary_with_name, it) }
                ?: context.getString(R.string.settings_screen_provider_summary)
        }
}
