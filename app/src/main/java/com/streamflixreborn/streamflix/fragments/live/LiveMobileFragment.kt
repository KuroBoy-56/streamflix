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
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.databinding.FragmentLiveMobileBinding
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.ui.SpacingItemDecoration
import com.streamflixreborn.streamflix.utils.AssignedProviders
import com.streamflixreborn.streamflix.utils.LoggingUtils
import com.streamflixreborn.streamflix.utils.dp
import kotlinx.coroutines.launch

/** Sección "En Vivo" para celular (barra inferior). */
class LiveMobileFragment : Fragment() {

    private var _binding: FragmentLiveMobileBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModels<LiveViewModel>()
    private val appAdapter = AppAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LiveSession.enter(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLiveMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvLive.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            if (itemDecorationCount == 0) addItemDecoration(SpacingItemDecoration(20.dp(requireContext())))
        }

        val provider = LiveSession.enter(requireContext())
        if (provider == null) {
            binding.tvLiveEmpty.visibility = View.VISIBLE
            binding.isLoading.root.visibility = View.GONE
            return
        }

        val liveProviders = AssignedProviders.live(requireContext())
        if (liveProviders.size > 1) {
            binding.hsvLiveProviders.visibility = View.VISIBLE
            LiveSession.bindProviderChips(binding.llLiveProviders, liveProviders, provider) { selected ->
                LiveSession.select(selected)
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
        val spacing = 10.dp(requireContext())
        categories.forEach { category ->
            category.list.forEach { item ->
                when (item) {
                    is TvShow -> item.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
                    is Movie -> item.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
                    is Episode -> item.itemType = AppAdapter.Type.EPISODE_MOBILE_ITEM
                }
            }
            category.itemSpacing = spacing
            category.itemType = AppAdapter.Type.CATEGORY_MOBILE_ITEM
        }
        binding.tvLiveEmpty.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE
        appAdapter.submitList(categories)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
