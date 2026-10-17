package com.streamflixreborn.streamflix.fragments.live

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.ui.LiveBrowser
import kotlinx.coroutines.launch

/** Sección "En Vivo" (TV y celular): vista estilo TiviMate de [LiveBrowser]. */
abstract class LiveBrowserFragment : Fragment() {

    private val viewModel by viewModels<LiveViewModel>()
    private var browser: LiveBrowser? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LiveSession.enter(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        LiveBrowser(this) { provider -> viewModel.load(provider) }.also { browser = it }.view

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = browser ?: return
        if (!b.start()) {
            b.view.addView(TextView(requireContext()).apply {
                setText(R.string.live_empty)
                setTextColor(Color.parseColor("#B3FFFFFF"))
                textSize = 16f
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    LiveViewModel.State.Loading -> Unit
                    is LiveViewModel.State.SuccessLoading -> b.onCategories(state.provider, state.categories)
                    is LiveViewModel.State.FailedLoading -> b.onFailed(state.provider, state.error.message.orEmpty())
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        browser?.onPause()
    }

    override fun onResume() {
        super.onResume()
        browser?.onResume()
    }

    /** Atrás: desde canales o vista previa vuelve a las carpetas. */
    fun onBackPressed(): Boolean = browser?.onBackPressed() ?: false

    override fun onDestroyView() {
        super.onDestroyView()
        browser?.release()
        browser = null
    }
}
