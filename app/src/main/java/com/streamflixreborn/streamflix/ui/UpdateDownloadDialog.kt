package com.streamflixreborn.streamflix.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.utils.ErrorReporter
import com.streamflixreborn.streamflix.utils.InAppUpdater
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Aviso de actualización (TV y celular, igual): descarga el APK dentro de la app con barra de
 * porcentaje y al terminar abre el instalador. Nunca hay dos avisos a la vez (la revisión al
 * abrir y la de cada 30 min pueden encontrar la misma actualización).
 */
object UpdateDownloadDialog {

    @Volatile private var showing = false

    fun show(activity: Activity, downloadUrl: String, notes: String) {
        if (showing || activity.isFinishing || activity.isDestroyed || downloadUrl.isBlank()) return
        showing = true
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val message = TextView(activity).apply {
            text = activity.getString(R.string.update_required_message) + if (notes.isBlank()) "" else "\n\n$notes"
            textSize = 15f
        }
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        val status = TextView(activity).apply {
            textSize = 14f
            visibility = View.GONE
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(message)
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad })
            addView(status)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_required_title)
            .setView(content)
            .setCancelable(false)
            .setPositiveButton(R.string.update_download, null)
            .create()
        dialog.setOnDismissListener { showing = false }
        val app = activity.applicationContext
        var apk: File? = null
        dialog.setOnShowListener {
            val button = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            button.requestFocus() // TV: el botón queda listo para el control remoto
            button.setOnClickListener {
                // Ya descargado: solo se abre el instalador (p. ej. tras dar el permiso).
                apk?.let { file ->
                    if (!InAppUpdater.installApkFile(app, file)) status.setText(R.string.update_allow_install)
                    return@setOnClickListener
                }
                button.isEnabled = false
                progress.visibility = View.VISIBLE
                progress.isIndeterminate = true
                status.visibility = View.VISIBLE
                status.text = activity.getString(R.string.update_downloading, 0)
                val scope = (activity as? LifecycleOwner)?.lifecycleScope
                val work: suspend () -> Unit = {
                    try {
                        val file = InAppUpdater.downloadApk(app, downloadUrl) { percent ->
                            if (percent >= 0) {
                                progress.isIndeterminate = false
                                progress.progress = percent
                                status.text = activity.getString(R.string.update_downloading, percent)
                            }
                        }
                        apk = file
                        progress.isIndeterminate = false
                        progress.progress = 100
                        status.setText(R.string.update_ready)
                        button.setText(R.string.update_install)
                        button.isEnabled = true
                        button.requestFocus()
                        if (!InAppUpdater.installApkFile(app, file)) status.setText(R.string.update_allow_install)
                    } catch (e: Exception) {
                        ErrorReporter.report("Update.Descarga", e.message, e)
                        progress.visibility = View.GONE
                        status.setText(R.string.update_failed)
                        button.setText(R.string.update_retry)
                        button.isEnabled = true
                        button.requestFocus()
                    }
                }
                @Suppress("OPT_IN_USAGE")
                (scope ?: GlobalScope).launch(Dispatchers.Main) { work() }
            }
        }
        dialog.show()
    }
}
