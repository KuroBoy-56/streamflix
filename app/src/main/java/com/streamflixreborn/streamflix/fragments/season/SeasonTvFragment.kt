package com.streamflixreborn.streamflix.fragments.season

import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentSeasonTvBinding
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.LoggingUtils
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SeasonTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentSeasonTvBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<SeasonTvFragmentArgs>()
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory {
        SeasonViewModel(
            args.seasonId,
            args.tvShowId,
            database,
        )
    }

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSeasonTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeSeason()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    SeasonViewModel.State.LoadingEpisodes -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }

                    is SeasonViewModel.State.SuccessLoadingEpisodes -> {
                        displaySeason(state.episodes)
                        binding.isLoading.root.visibility = View.GONE
                    }

                    is SeasonViewModel.State.FailedLoadingEpisodes -> {
                        // Auto clear cache on HTTP 409 and retry
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.getSeasonEpisodes(args.seasonId)
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
                            btnIsLoadingRetry.setOnClickListener { viewModel.getSeasonEpisodes(args.seasonId) }
                            btnIsLoadingClearCache.setOnClickListener {
                                CacheUtils.clearAppCache(requireContext())
                                android.widget.Toast.makeText(requireContext(), getString(com.streamflixreborn.streamflix.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
                                viewModel.getSeasonEpisodes(args.seasonId)
                            }
                            btnIsLoadingErrorDetails.setOnClickListener {
                                LoggingUtils.showErrorDialog(requireContext(), state.error)
                            }
                            btnIsLoadingRetry.requestFocus()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    private fun initializeSeason() {
        binding.tvSeasonTitle.text = args.tvShowTitle.ifEmpty { args.seasonTitle.orEmpty() }
        Glide.with(this).load(args.tvShowBanner).into(binding.ivSeasonBackdrop)

        binding.vgvEpisodes.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(8.dp(requireContext()))
        }
        binding.vgvSeasons.setItemSpacing(3.dp(requireContext()))

        viewLifecycleOwner.lifecycleScope.launch {
            val seasons = withContext(Dispatchers.IO) { database.seasonDao().getByTvShowId(args.tvShowId) }
                .sortedBy { it.number }
                .ifEmpty { listOf(Season(id = args.seasonId, number = args.seasonNumber, title = args.seasonTitle)) }
            binding.vgvSeasons.adapter = SeasonsAdapter(seasons)
            binding.vgvSeasons.selectedPosition = seasons.indexOfFirst { it.id == args.seasonId }.coerceAtLeast(0)
        }
    }

    /** Temporadas a la izquierda, como en Roku: al elegir una se abre en esta misma pantalla. */
    private inner class SeasonsAdapter(private val seasons: List<Season>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = seasons.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 35.dp(context))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp(context), 0, 10.dp(context), 0)
            textSize = 16f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColorStateList(context, R.color.season_row_text_tv))
            setBackgroundResource(R.drawable.bg_season_row_tv)
            isFocusable = true
            isFocusableInTouchMode = true
        }) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val season = seasons[position]
            val title = season.title ?: getString(R.string.season_number, season.number)
            (holder.itemView as TextView).apply {
                text = title
                isSelected = season.id == args.seasonId
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setOnClickListener {
                    if (season.id == args.seasonId) {
                        binding.vgvEpisodes.requestFocus()
                        return@setOnClickListener
                    }
                    findNavController().navigate(
                        R.id.season,
                        SeasonTvFragmentArgs(
                            tvShowId = args.tvShowId,
                            tvShowTitle = args.tvShowTitle,
                            tvShowPoster = args.tvShowPoster,
                            tvShowBanner = args.tvShowBanner,
                            seasonId = season.id,
                            seasonNumber = season.number,
                            seasonTitle = title,
                        ).toBundle(),
                        NavOptions.Builder().setPopUpTo(R.id.season, true).build(),
                    )
                }
            }
        }
    }

    private var focusedEpisodeIndex: Int? = null

    private fun displaySeason(episodes: List<Episode>) {
        val preparedEpisodes = episodes.onEach { episode ->
            episode.itemType = AppAdapter.Type.EPISODE_ROW_TV_ITEM
        }

        val lastWatchedIndex = episodes
            .filter { it.watchHistory != null }
            .sortedByDescending { it.watchHistory?.lastEngagementTimeUtcMillis }
            .firstOrNull()
            ?.let { episodes.indexOf(it) }
            ?: episodes.indexOfLast { it.isWatched }

        appAdapter.submitList(preparedEpisodes)

        if (focusedEpisodeIndex == null) {
            val scrollIndex = when {
                lastWatchedIndex == -1 -> 0
                lastWatchedIndex < episodes.lastIndex -> lastWatchedIndex + 1
                else -> lastWatchedIndex
            }
            binding.vgvEpisodes.scrollAndFocus(scrollIndex)
            focusedEpisodeIndex = scrollIndex
        }
    }

    private fun RecyclerView.scrollAndFocus(position: Int) {
        scrollToPosition(position)
        viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                viewTreeObserver.removeOnGlobalLayoutListener(this)
                findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
        })
    }



}