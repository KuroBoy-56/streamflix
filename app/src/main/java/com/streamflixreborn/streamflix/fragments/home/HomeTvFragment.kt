package com.streamflixreborn.streamflix.fragments.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentHomeTvBinding
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.ui.HeroRowTvController
import com.streamflixreborn.streamflix.utils.CacheUtils
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.streamflixreborn.streamflix.utils.LoggingUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier

class HomeTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentHomeTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by lazy {
        val providerKey = UserPreferences.currentProvider?.name ?: "default"
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel(AppDatabase.getInstance(requireContext())) as T
            }
        }
        ViewModelProvider(this, factory).get(providerKey, HomeViewModel::class.java)
    }

    private var hero: HeroRowTvController? = null
    private var hasRequestedInitialFocus = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        hasRequestedInitialFocus = false
        hero = HeroRowTvController(this, binding.hero) { UserPreferences.currentProvider is IptvProvider }.apply {
            rowTitle = { category ->
                when (category.name) {
                    Category.FEATURED -> getString(R.string.home_featured)
                    else -> category.name
                }
            }
        }

        // Lightweight refresh when provider changes
        viewLifecycleOwner.lifecycleScope.launch {
            ProviderChangeNotifier.providerChangeFlow.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect {
                viewModel.getHome()
            }
        }

        // Initial load
        viewModel.getHome()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    HomeViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is HomeViewModel.State.SuccessLoading -> {
                        displayHome(state.categories)
                        binding.hero.root.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is HomeViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            Toast.makeText(requireContext(), getString(R.string.clear_cache_done_409), Toast.LENGTH_SHORT).show()
                            viewModel.getHome()
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        binding.isLoading.apply {
                            pbIsLoading.visibility = View.GONE
                            gIsLoadingRetry.visibility = View.VISIBLE
                            btnIsLoadingRetry.setOnClickListener { viewModel.getHome() }
                            btnIsLoadingClearCache.setOnClickListener {
                                CacheUtils.clearAppCache(requireContext())
                                Toast.makeText(requireContext(), getString(R.string.clear_cache_done), Toast.LENGTH_SHORT).show()
                                viewModel.getHome()
                            }
                            btnIsLoadingErrorDetails.setOnClickListener {
                                LoggingUtils.showErrorDialog(requireContext(), state.error)
                            }
                            binding.hero.root.visibility = View.GONE
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
        _binding = null
    }

    /** Atrás en el inicio: primero vuelve a la primera fila. */
    fun onBackPressed(): Boolean = hero?.resetToFirstRow() ?: false

    // API usada por los view holders: el póster grande ahora sigue a la portada seleccionada,
    // por lo que estos métodos se mantienen por compatibilidad.
    @Suppress("UNUSED_PARAMETER")
    fun updateBackground(uri: String?, swiperHasFocus: Boolean? = false) {}

    @Suppress("UNUSED_PARAMETER")
    fun pinBackground(uri: String?) {}

    fun releasePinnedBackground() {}

    fun resetSwiperSchedule() {}

    private fun displayHome(categories: List<Category>) {
        categories
            .find { it.name == Category.CONTINUE_WATCHING }
            ?.also {
                it.name = getString(R.string.home_continue_watching)
            }

        categories
            .find { it.name == Category.FAVORITE_MOVIES }
            ?.also { it.name = getString(R.string.home_favorite_movies) }

        categories
            .find { it.name == Category.FAVORITE_TV_SHOWS }
            ?.also { it.name = getString(R.string.home_favorite_tv_shows) }

        // ⚡️ FILTRO PARA ELIMINAR "Vistos Recientemente", "Soporte", "Ayuda", "Telegram"
        val blacklisted = listOf("soporte", "ayuda", "support", "help", "telegram", "discord", "whatsapp")

        val rows = categories
            .filter { category ->
                category.list.isNotEmpty() &&
                        category.name != Category.RECENTLY_WATCHED &&
                        !blacklisted.any { category.name.contains(it, ignoreCase = true) }
            }
            .onEach { category ->
                val isContinueWatching = category.name == getString(R.string.home_continue_watching)
                category.list.forEach { show ->
                    when (show) {
                        is Episode -> show.itemType = if (isContinueWatching) AppAdapter.Type.EPISODE_CONTINUE_WATCHING_TV_ITEM else AppAdapter.Type.EPISODE_TV_ITEM
                        is Movie -> show.itemType = if (isContinueWatching) AppAdapter.Type.MOVIE_CONTINUE_WATCHING_TV_ITEM else AppAdapter.Type.MOVIE_TV_ITEM
                        is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                    }
                }
                category.itemSpacing = resources.getDimension(R.dimen.home_spacing).toInt()
                category.itemType = AppAdapter.Type.CATEGORY_TV_ITEM
            }

        val focusNow = !hasRequestedInitialFocus && rows.isNotEmpty() &&
                activity?.currentFocus?.let { focused -> isInNavigation(focused) } != true
        hero?.submitRows(rows, requestFocus = focusNow)
        if (rows.isNotEmpty()) hasRequestedInitialFocus = true
    }

    private fun isInNavigation(view: View): Boolean {
        var parent: View? = view
        while (parent != null) {
            if (parent.id == R.id.nav_main) return true
            parent = parent.parent as? View
        }
        return false
    }
}
