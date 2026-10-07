package com.streamflixreborn.streamflix.fragments.settings

import android.content.Context
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.Preference
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.sync.CloudSyncManager
import kotlinx.coroutines.launch

object CloudAccountSettingsController {
    fun bind(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        findPreference: (String) -> Preference?,
    ) {
        val status = findPreference("cloud_account_status") ?: return
        val signIn = findPreference("cloud_sign_in")
        val signUp = findPreference("cloud_sign_up")
        val signOut = findPreference("cloud_sign_out")
        val syncNow = findPreference("cloud_sync_now")

        fun refresh() {
            val prefs = fragment.requireContext().getSharedPreferences("SecureGatewayPrefs", Context.MODE_PRIVATE)
            val alphaToken = prefs.getString("alpha_token", null)
            val isSignedIn = !alphaToken.isNullOrEmpty()

            status.summary = if (isSignedIn) {
                "Sincronización Cloud Activa ($alphaToken)"
            } else {
                "Sesión no iniciada en el Gateway"
            }

            signIn?.isVisible = false
            signUp?.isVisible = false
            signOut?.isVisible = false
            syncNow?.isVisible = isSignedIn

            status.isEnabled = true
            syncNow?.isEnabled = isSignedIn
        }

        syncNow?.setOnPreferenceClickListener {
            scope.launch {
                try {
                    CloudSyncManager.syncLocalToCloud(fragment.requireContext())
                    Toast.makeText(fragment.requireContext(), "Sincronización con Firebase exitosa", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(fragment.requireContext(), "Error al sincronizar: ${e.message}", Toast.LENGTH_LONG).show()
                }
                refresh()
            }
            true
        }

        refresh()
    }
}