package com.streamflixreborn.streamflix.providers

import com.google.gson.annotations.SerializedName
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

object FanpelisProvider : Provider {
    private const val URL = "https://fanpelis.to/"
    private const val API_URL = "https://tmdb.fanpelis.to/"

    override val baseUrl = URL
    override val name = "Fanpelis"
    override val logo = "https://fanpelis.to/logo-fanpelis.png"
    override val language = "es"

    private val service = Service.build()

    override suspend fun getHome(): List<Category> {
        val movies = service.now("movie").items
        val shows = service.now("tvshow").items
        return listOf(
            Category(Category.FEATURED, movies.take(6).map(::toMovie)),
            Category("Últimas películas", movies.map(::toMovie)),
            Category("Últimas series", shows.map(::toTvShow)),
        ).filter { it.list.isNotEmpty() }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        return service.search(query, page, 16).items.mapNotNull { item ->
            when (item.kind) {
                "movie" -> toMovie(item)
                "tvshow" -> toTvShow(item)
                else -> toMovie(item)
            }
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> =
        service.items("movie", page, 16).items.map(::toMovie)

    override suspend fun getTvShows(page: Int): List<TvShow> =
        service.items("tvshow", page, 16).items.map(::toTvShow)

    override suspend fun getMovie(id: String): Movie {
        val item = service.item("movie", id).item ?: return Movie(id = id)
        return toMovie(item)
    }

    override suspend fun getTvShow(id: String): TvShow {
        val item = service.item("tvshow", id).item ?: return TvShow(id = id)
        return toTvShow(item).withSeasons(item.tmdbId)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split('|', limit = 2)
        val showId = parts.firstOrNull()?.toIntOrNull() ?: return emptyList()
        val seasonNumber = parts.getOrNull(1)?.toIntOrNull() ?: return emptyList()
        return service.seasonEpisodes("tvshow", showId, seasonNumber).season?.episodes.orEmpty()
            .sortedBy { it.episode }
            .map { toEpisode(showId, seasonNumber, it) }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val response = service.taxonomyItems("genre", id, page, 16)
        return Genre(id = id, name = id, shows = response.items.mapNotNull(::toShow))
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val response = service.personItems(id, page, 16)
        return People(id = id, name = response.person?.name ?: id, filmography = response.items.mapNotNull(::toShow))
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val playback = when (videoType) {
            is Video.Type.Movie -> {
                val tmdbId = id.toIntOrNull() ?: return emptyList()
                service.playbackMovie("movie", tmdbId)
            }
            is Video.Type.Episode -> {
                val tmdbId = videoType.tvShow.id.toIntOrNull() ?: return emptyList()
                service.playbackEpisode("tvshow", tmdbId, videoType.season.number, videoType.number)
            }
        }

        return playback.embeds.mapIndexed { index, embed ->
            val serverName = listOfNotNull(
                embed.host?.takeIf { it.isNotBlank() },
                embed.lang?.takeIf { it.isNotBlank() },
                embed.quality?.takeIf { it.isNotBlank() }
            ).joinToString(" | ").ifBlank { "Server ${index + 1}" }

            Video.Server(
                id = embed.url,
                name = serverName,
                src = embed.url,
            )
        }.filter { it.src.isNotBlank() }.distinctBy { it.src }
    }

    override suspend fun getVideo(server: Video.Server): Video =
        Extractor.extract(server.src.ifBlank { server.id }, server)

    private suspend fun TvShow.withSeasons(tmdbId: Int): TvShow {
        val seasonsResp = try {
            service.seasons("tvshow", tmdbId)
        } catch (e: Exception) {
            null
        }
        val seasons = seasonsResp?.seasons.orEmpty().map { s ->
            Season(
                id = "$tmdbId|${s.season}",
                number = s.season,
                title = s.name.ifBlank { "Temporada ${s.season}" },
                poster = image(s.posterPath),
            )
        }
        return copy(seasons = seasons)
    }

    private fun toShow(item: FanItem): Show? = when (item.kind) {
        "movie" -> toMovie(item)
        "tvshow" -> toTvShow(item)
        else -> toMovie(item)
    }

    private fun toMovie(item: FanItem) = Movie(
        id = item.tmdbId.toString(),
        title = item.title,
        overview = item.overview,
        released = item.releaseDate,
        runtime = item.runtime,
        trailer = item.trailerYoutubeKey?.takeIf(String::isNotBlank)?.let { "https://www.youtube.com/watch?v=$it" },
        quality = item.quality,
        rating = item.voteAverage,
        poster = image(item.posterPath),
        banner = image(item.backdropPath),
        genres = item.genres.map { Genre(id = it.slug ?: it.title, name = it.title) },
    )

    private fun toTvShow(item: FanItem) = TvShow(
        id = item.tmdbId.toString(),
        title = item.title,
        overview = item.overview,
        released = item.firstAirDate ?: item.releaseDate,
        runtime = item.runtime,
        trailer = item.trailerYoutubeKey?.takeIf(String::isNotBlank)?.let { "https://www.youtube.com/watch?v=$it" },
        quality = item.quality,
        rating = item.voteAverage,
        poster = image(item.posterPath),
        banner = image(item.backdropPath),
        genres = item.genres.map { Genre(id = it.slug ?: it.title, name = it.title) },
    )

    private fun toEpisode(showId: Int, seasonNumber: Int, item: FanEpisode) = Episode(
        id = "$showId|$seasonNumber|${item.episode}",
        number = item.episode,
        title = item.title,
        overview = item.overview,
        poster = image(item.stillPath),
        released = item.airDate,
    )

    private fun image(path: String?): String? = path?.takeIf(String::isNotBlank)?.let {
        if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/original${if (it.startsWith('/')) it else "/$it"}"
    }

    private data class ItemsResponse(val items: List<FanItem> = emptyList())
    private data class ItemDetailResponse(val item: FanItem? = null)
    private data class SeasonsResponse(val seasons: List<FanSeason> = emptyList())
    private data class SeasonDetailResponse(val season: FanSeasonDetail? = null)
    private data class FanSeasonDetail(val episodes: List<FanEpisode> = emptyList())
    private data class PlaybackResponse(val embeds: List<Embed> = emptyList())
    private data class PersonItemsResponse(val person: FanPerson? = null, val items: List<FanItem> = emptyList())
    private data class FanPerson(val name: String? = null)

    private data class Embed(
        val url: String = "",
        val host: String? = null,
        val lang: String? = null,
        val quality: String? = null
    )

    private data class FanGenre(
        val id: Int? = null,
        val slug: String? = null,
        val title: String = ""
    )

    private data class FanSeason(
        val season: Int = 1,
        val name: String = "",
        @SerializedName("poster_path") val posterPath: String? = null
    )

    private data class FanItem(
        @SerializedName("tmdb_id") val tmdbId: Int = 0,
        val title: String = "",
        val overview: String? = null,
        val kind: String = "movie",
        val slug: String = "",
        @SerializedName("poster_path") val posterPath: String? = null,
        @SerializedName("backdrop_path") val backdropPath: String? = null,
        @SerializedName("trailer_youtube_key") val trailerYoutubeKey: String? = null,
        @SerializedName("vote_average") val voteAverage: Double? = null,
        val genres: List<FanGenre> = emptyList(),
        val quality: String? = null,
        @SerializedName("release_date") val releaseDate: String? = null,
        @SerializedName("first_air_date") val firstAirDate: String? = null,
        val runtime: Int? = null,
    )

    private data class FanEpisode(
        @SerializedName("tmdb_id") val tmdbId: Int = 0,
        val title: String = "",
        val overview: String? = null,
        val episode: Int = 0,
        @SerializedName("still_path") val stillPath: String? = null,
        @SerializedName("air_date") val airDate: String? = null,
    )

    private interface Service {
        companion object {
            fun build(): Service = Retrofit.Builder()
                .baseUrl(API_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .client(
                    com.streamflixreborn.streamflix.utils.PanelDomains.newBuilder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .dns(DnsResolver.doh)
                        .build(),
                )
                .build()
                .create(Service::class.java)
        }

        @GET("v1/now")
        suspend fun now(@Query("kind") kind: String): ItemsResponse

        @GET("v1/items")
        suspend fun items(@Query("kind") kind: String, @Query("page") page: Int, @Query("limit") limit: Int): ItemsResponse

        @GET("v1/search")
        suspend fun search(@Query("q") query: String, @Query("page") page: Int, @Query("limit") limit: Int): ItemsResponse

        @GET("v1/items/{kind}/{id}")
        suspend fun item(@Path("kind") kind: String, @Path("id") id: String): ItemDetailResponse

        @GET("v1/items/{kind}/{id}/seasons")
        suspend fun seasons(@Path("kind") kind: String, @Path("id") id: Int): SeasonsResponse

        @GET("v1/items/{kind}/{id}/seasons/{season}")
        suspend fun seasonEpisodes(
            @Path("kind") kind: String,
            @Path("id") id: Int,
            @Path("season") season: Int
        ): SeasonDetailResponse

        @GET("v1/playback/{kind}/{id}")
        suspend fun playbackMovie(
            @Path("kind") kind: String,
            @Path("id") id: Int
        ): PlaybackResponse

        @GET("v1/playback/{kind}/{id}")
        suspend fun playbackEpisode(
            @Path("kind") kind: String,
            @Path("id") id: Int,
            @Query("season") season: Int,
            @Query("episode") episode: Int
        ): PlaybackResponse

        @GET("v1/taxonomies/{tax}/{term}/items")
        suspend fun taxonomyItems(
            @Path("tax") tax: String,
            @Path("term") term: String,
            @Query("page") page: Int,
            @Query("limit") limit: Int
        ): ItemsResponse

        @GET("v1/people/{id}/items")
        suspend fun personItems(
            @Path("id") id: String,
            @Query("page") page: Int,
            @Query("limit") limit: Int
        ): PersonItemsResponse
    }
}
