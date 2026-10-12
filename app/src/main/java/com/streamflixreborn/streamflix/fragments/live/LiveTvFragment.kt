package com.streamflixreborn.streamflix.fragments.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.databinding.FragmentLiveTvBinding
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.ui.HeroRowTvController
import com.streamflixreborn.streamflix.utils.AssignedProviders
import com.streamflixreborn.streamflix.utils.LoggingUtils
import kotlinx.coroutines.launch

/** Sección "En Vivo" para TV: mismo diseño hero + una fila que el inicio. */
class LiveTvFragment : Fragment() {

    private var _binding: FragmentLiveTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModels<LiveViewModel>()
    private var hero: HeroRowTvController? = null
    private var hasFocusedContent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LiveSession.enter(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLiveTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val provider = LiveSession.enter(requireContext())
        if (provider == null) {
            binding.tvLiveEmpty.visibility = View.VISIBLE
            binding.isLoading.root.visibility = View.GONE
            return
        }

        hasFocusedContent = false
        hero = HeroRowTvController(this, binding.hero, isLiveContent = { true }, compactRow = false)

        val liveProviders = AssignedProviders.live(requireContext())
        if (liveProviders.size > 1) {
            binding.llLiveProviders.visibility = View.VISIBLE
            LiveSession.bindProviderChips(binding.llLiveProviders, liveProviders, provider) { selected ->
                LiveSession.select(selected)
                hasFocusedContent = false
                viewModel.load(selected)
            }
        }

        viewModel.load(provider)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    LiveViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is LiveViewModel.State.SuccessLoading -> {
                        binding.isLoading.root.visibility = View.GONE
                        display(state.categories)
                    }
                    is LiveViewModel.State.FailedLoading -> {
                        Toast.makeText(requireContext(), state.error.message ?: "", Toast.LENGTH_SHORT).show()
                        binding.isLoading.apply {
                            pbIsLoading.visibility = View.GONE
                            gIsLoadingRetry.visibility = View.VISIBLE
                            btnIsLoadingRetry.setOnClickListener { viewModel.load(state.provider, force = true) }
                            btnIsLoadingErrorDetails.setOnClickListener {
                                LoggingUtils.showErrorDialog(requireContext(), state.error)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun display(categories: List<Category>) {
        categories.forEach { category ->
            category.list.forEach { item ->
                when (item) {
                    is TvShow -> item.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                    is Movie -> item.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                    is Episode -> item.itemType = AppAdapter.Type.EPISODE_TV_ITEM
                }
            }
            category.itemSpacing = resources.getDimension(R.dimen.home_spacing).toInt()
            category.itemType = AppAdapter.Type.CATEGORY_TV_ITEM
        }

        binding.tvLiveEmpty.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE
        hero?.submitRows(categories, requestFocus = !hasFocusedContent)
        if (categories.isNotEmpty()) hasFocusedContent = true
    }

    /** Atrás: primero vuelve a la primera fila. */
    fun onBackPressed(): Boolean = hero?.resetToFirstRow() ?: false

    override fun onDestroyView() {
        super.onDestroyView()
        hero?.release()
        hero = null
        _binding = null
    }
}
