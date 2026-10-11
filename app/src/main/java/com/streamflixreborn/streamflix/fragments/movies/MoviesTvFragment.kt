package com.streamflixreborn.streamflix.fragments.movies

import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.ui.HeroRowTvController
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentMoviesTvBinding
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.launch

class MoviesTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentMoviesTvBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory { MoviesViewModel(database) }

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMoviesTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeMovies()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    MoviesViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    MoviesViewModel.State.LoadingMore -> appAdapter.isLoading = true
                    is MoviesViewModel.State.SuccessLoadingRows -> {
                        displayRows(state.categories)
                        binding.vgvMovies.visibility = View.GONE
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is MoviesViewModel.State.SuccessLoading -> {
                        binding.hero.root.visibility = View.GONE
                        binding.vgvMovies.visibility = View.VISIBLE
                        displayMovies(state.movies, state.hasMore)
                        appAdapter.isLoading = false
                        binding.vgvMovies.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is MoviesViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            if (appAdapter.isLoading) appAdapter.isLoading = false
                            viewModel.getMovies()
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                pbIsLoading.visibility = View.GONE
                                gIsLoadingRetry.visibility = View.VISIBLE
                                btnIsLoadingRetry.setOnClickListener { viewModel.getMovies() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
                                    viewModel.getMovies()
                                }
                                binding.vgvMovies.visibility = View.GONE
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        hero?.release()
        hero = null
        rowsFocused = false
        _binding = null
    }


    private fun initializeMovies() {
        binding.vgvMovies.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(requireContext().resources.getDimension(R.dimen.movies_spacing).toInt())
        }

        binding.root.requestFocus()
    }

    private fun displayMovies(movies: List<Movie>, hasMore: Boolean) {
        appAdapter.submitList(movies.onEach {
            it.itemType = AppAdapter.Type.MOVIE_GRID_TV_ITEM
        })

        if (hasMore) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMoreMovies() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }

    // ---- Filas por género (póster grande + una fila, con "Ver todo") ----
    private var hero: HeroRowTvController? = null
    private var rowsFocused = false

    private fun displayRows(categories: List<Category>) {
        val controller = hero ?: HeroRowTvController(this, binding.hero).also { hero = it }
        categories.forEach { category ->
            category.list.forEach { item ->
                when (item) {
                    is Movie -> item.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                    is TvShow -> item.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                    is Genre -> item.itemType = AppAdapter.Type.GENRE_SEE_ALL_TV_ITEM
                }
            }
            category.itemType = AppAdapter.Type.CATEGORY_TV_ITEM
        }
        binding.hero.root.visibility = View.VISIBLE
        controller.submitRows(categories, requestFocus = !rowsFocused)
        rowsFocused = true
    }

    /** Atrás: primero vuelve a la primera fila. */
    fun onBackPressed(): Boolean = hero?.resetToFirstRow() ?: false
}
