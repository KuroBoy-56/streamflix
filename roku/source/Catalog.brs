' Catálogo TMDb, canales en vivo (M3U del panel), guía EPG y enlaces de video.
' Se ejecuta SOLO dentro de ApiTask.

' =======================================================================================
' TMDb (TmdbProvider)
' =======================================================================================

function TmdbKey() as string
    return RegRead("tmdb_api_key")
end function

function TmdbUrl(path as string, params = {} as object) as string
    url = "https://api.themoviedb.org/3/" + path
    sep = "?"
    if Instr(1, path, "?") > 0 then sep = "&"
    lang = CatalogLanguage()
    if lang = "es" then lang = "es-MX"
    url = url + sep + "language=" + lang
    key = TmdbKey()
    if key <> "" and Left(key, 3) <> "eyJ" then url = url + "&api_key=" + UrlEnc(key)
    for each name in params
        url = url + "&" + name + "=" + UrlEnc(AsStr(params[name]))
    end for
    return url
end function

function TmdbHeaders() as object
    headers = { Accept: "application/json" }
    key = TmdbKey()
    if Left(key, 3) = "eyJ" then headers.Authorization = "Bearer " + key
    return headers
end function

function TmdbGet(path as string, params = {} as object) as dynamic
    r = Http(TmdbUrl(path, params), { headers: TmdbHeaders(), timeout: 15000 })
    if r.code <> 200 then return invalid
    return JsonOf(r.body)
end function

' requests: [{ path, params }] en paralelo -> [json | invalid]
function TmdbGetMany(requests as object) as object
    urls = []
    for each req in requests
        params = req.params
        if params = invalid then params = {}
        urls.Push(TmdbUrl(req.path, params))
    end for
    out = []
    for each r in HttpParallel(urls, TmdbHeaders(), 25000)
        if r.code = 200 then out.Push(JsonOf(r.body)) else out.Push(invalid)
    end for
    return out
end function

function Rating1(v as dynamic) as string
    n = Int(ToFloat(v) * 10 + 0.5)
    if n <= 0 then return ""
    return (n \ 10).ToStr() + "." + (n mod 10).ToStr()
end function

function TmdbItem(r as dynamic, defaultType as string) as dynamic
    if r = invalid or GetInterface(r, "ifAssociativeArray") = invalid then return invalid
    mediaType = AsStr(r.media_type)
    if mediaType = "" then mediaType = defaultType
    if mediaType = "person" then
        return { itemId: AsStr(r.id), mediaType: "person", title: AsStr(r.name), overview: AsStr(r.character), poster: TmdbImage(r.profile_path, "w342"), popularity: ToFloat(r.popularity) }
    end if
    if mediaType <> "movie" and mediaType <> "tv" then return invalid
    title = AsStr(r.title)
    if title = "" then title = AsStr(r.name)
    date = AsStr(r.release_date)
    if date = "" then date = AsStr(r.first_air_date)
    return {
        itemId: AsStr(r.id)
        mediaType: mediaType
        title: title
        overview: AsStr(r.overview)
        poster: TmdbImage(r.poster_path, "w342")
        backdrop: TmdbImage(r.backdrop_path, "w1280")
        rating: Rating1(r.vote_average)
        year: Left(date, 4)
        popularity: ToFloat(r.popularity)
    }
end function

function TmdbResults(json as dynamic, defaultType as string, withPeople = false as boolean) as object
    out = []
    if json = invalid or GetInterface(json.results, "ifArray") = invalid then return out
    for each r in json.results
        item = TmdbItem(r, defaultType)
        if item <> invalid and (withPeople or item.mediaType <> "person") then out.Push(item)
    end for
    return out
end function

function MixByPopularity(a as object, b as object) as object
    all = []
    all.Append(a)
    all.Append(b)
    all.SortBy("popularity", "r")
    return all
end function

function NoTmdbKey() as object
    return { error: "El panel no envió una clave de TMDb para esta cuenta. Pídele a tu distribuidor que la configure." }
end function

' Inicio (TmdbProvider.getHome): tendencias, populares, anime y por plataforma.
function ApiHome() as object
    if TmdbKey() = "" then return NoTmdbKey()
    lang = CatalogLanguage()
    region = UCase(lang)
    if lang = "en" then region = "US"
    anime = { with_keywords: "210024|222243" }
    platforms = [
        { title: "Popular en Netflix", ids: "8" }
        { title: "Popular en Amazon", ids: "9|10|119" }
        { title: "Popular en Disney+", ids: "337" }
        { title: "Popular en Hulu", ids: "15" }
        { title: "Popular en Apple TV+", ids: "350" }
        { title: "Popular en HBO", ids: "384|1899" }
    ]
    requests = [
        { path: "trending/all/day", params: { page: 1 } }
        { path: "movie/popular", params: { page: 1 } }
        { path: "tv/popular", params: { page: 1 } }
        { path: "discover/movie", params: anime }
        { path: "discover/tv", params: anime }
    ]
    for each p in platforms
        params = { with_watch_providers: p.ids, watch_region: region, sort_by: "popularity.desc" }
        requests.Push({ path: "discover/movie", params: params })
        requests.Push({ path: "discover/tv", params: params })
    end for
    res = TmdbGetMany(requests)
    if res[0] = invalid and res[1] = invalid and res[2] = invalid then return { error: "No se pudo cargar el catálogo. Revisa tu conexión." }

    root = CreateObject("roSGNode", "ContentNode")
    AddRow(root, "Tendencias", TmdbResults(res[0], "movie"), { path: "trending/all/day", params: {}, page: 2, mediaType: "" })
    AddRow(root, "Películas populares", TmdbResults(res[1], "movie"), { path: "movie/popular", params: {}, page: 2, mediaType: "movie" })
    AddRow(root, "Series populares", TmdbResults(res[2], "tv"), { path: "tv/popular", params: {}, page: 2, mediaType: "tv" })
    AddRow(root, "Anime populares", MixByPopularity(TmdbResults(res[3], "movie"), TmdbResults(res[4], "tv")), invalid)
    for i = 0 to platforms.Count() - 1
        AddRow(root, platforms[i].title, MixByPopularity(TmdbResults(res[5 + i * 2], "movie"), TmdbResults(res[6 + i * 2], "tv")), invalid)
    end for
    return { content: root }
end function

sub AddRow(root as object, title as string, items as object, paging as dynamic)
    if items.Count() = 0 then return
    root.appendChild(RowNode(title, items, paging))
end sub

' Películas o Series (MoviesTvFragment / TvShowsTvFragment): listas y una fila por género.
function ApiCatalog(mediaType as string) as object
    if TmdbKey() = "" then return NoTmdbKey()
    if mediaType = "movie" then
        lists = [["Populares", "movie/popular"], ["Mejor valoradas", "movie/top_rated"], ["En cines", "movie/now_playing"], ["Próximamente", "movie/upcoming"]]
        genreIds = ["28", "12", "16", "35", "80", "99", "18", "10751", "14", "27", "10749", "878", "53"]
    else
        lists = [["Populares", "tv/popular"], ["Mejor valoradas", "tv/top_rated"], ["Al aire", "tv/on_the_air"], ["Emitiéndose hoy", "tv/airing_today"]]
        genreIds = ["10759", "16", "35", "80", "99", "18", "10751", "10762", "9648", "10765", "10768"]
    end if
    requests = []
    for each l in lists
        requests.Push({ path: l[1], params: { page: 1 } })
    end for
    for each g in genreIds
        requests.Push({ path: "discover/" + mediaType, params: { with_genres: g, sort_by: "popularity.desc" } })
    end for
    requests.Push({ path: "genre/" + mediaType + "/list", params: {} })
    res = TmdbGetMany(requests)

    names = {}
    genresJson = res[res.Count() - 1]
    if genresJson <> invalid and GetInterface(genresJson.genres, "ifArray") <> invalid then
        for each g in genresJson.genres
            names[AsStr(g.id)] = AsStr(g.name)
        end for
    end if

    root = CreateObject("roSGNode", "ContentNode")
    for i = 0 to lists.Count() - 1
        AddRow(root, lists[i][0], TmdbResults(res[i], mediaType), { path: lists[i][1], params: {}, page: 2, mediaType: mediaType })
    end for
    for i = 0 to genreIds.Count() - 1
        id = genreIds[i]
        title = names[id]
        if title = invalid or title = "" then title = "Género " + id
        AddRow(root, title, TmdbResults(res[lists.Count() + i], mediaType), { path: "discover/" + mediaType, params: { with_genres: id, sort_by: "popularity.desc" }, page: 2, mediaType: mediaType })
    end for
    if root.getChildCount() = 0 then return { error: "No se pudo cargar el catálogo. Revisa tu conexión." }
    return { content: root }
end function

' Siguiente página de una fila ("Ver más" al llegar al final).
function ApiPage(p as object) as object
    params = {}
    if p.params <> invalid then params.Append(p.params)
    params.page = ToInt(p.page, 2)
    json = TmdbGet(p.path, params)
    items = TmdbResults(json, AsStr(p.mediaType))
    totalPages = 1
    if json <> invalid then totalPages = ToInt(json.total_pages, 1)
    return { items: items, page: params.page, done: params.page >= totalPages or items.Count() = 0 }
end function

' Ficha de película o serie (getMovie / getTvShow) con reparto, temporadas y recomendaciones.
function ApiDetails(p as object) as object
    if TmdbKey() = "" then return NoTmdbKey()
    mediaType = p.mediaType
    if p.match = true then
        id = TmdbMatch(mediaType, AsStr(p.title), AsStr(p.year))
        if id = "" then return { error: "No se encontró este título en el catálogo." }
        p.id = id
    end if
    json = TmdbGet(mediaType + "/" + AsStr(p.id), { append_to_response: "credits,recommendations,external_ids" })
    if json = invalid then return { error: "No se pudo cargar la información." }
    item = TmdbItem(json, mediaType)
    item.poster = TmdbImage(json.poster_path, "w500")
    genres = []
    if GetInterface(json.genres, "ifArray") <> invalid then
        for each g in json.genres
            genres.Push(AsStr(g.name))
        end for
    end if
    runtime = ToInt(json.runtime)
    if runtime = 0 and GetInterface(json.episode_run_time, "ifArray") <> invalid and json.episode_run_time.Count() > 0 then runtime = ToInt(json.episode_run_time[0])

    cast = []
    if json.credits <> invalid and GetInterface(json.credits.cast, "ifArray") <> invalid then
        for each c in json.credits.cast
            if cast.Count() < 20 then cast.Push({ itemId: AsStr(c.id), mediaType: "person", title: AsStr(c.name), overview: AsStr(c.character), poster: TmdbImage(c.profile_path, "w342") })
        end for
    end if

    seasons = []
    specials = invalid
    if GetInterface(json.seasons, "ifArray") <> invalid then
        for each s in json.seasons
            season = { number: ToInt(s.season_number), title: AsStr(s.name), poster: TmdbImage(s.poster_path, "w342"), episodeCount: ToInt(s.episode_count) }
            if season.number = 0 then specials = season else seasons.Push(season)
        end for
    end if
    if specials <> invalid then seasons.Push(specials)

    return {
        item: item
        genres: genres.Join(", ")
        runtime: runtime
        tagline: AsStr(json.tagline)
        imdbId: AsStr(json.imdb_id)
        cast: cast
        recommendations: TmdbResults(json.recommendations, mediaType)
        seasons: seasons
    }
end function

' Portadas de TMDb (con la clave de la cuenta) para títulos guardados en la nube con las imágenes
' de otro sitio o sin ellas. p.items: [{ mediaType, title }] -> { art: { ArtKey: { poster, backdrop } } }
function ApiArtwork(p as object) as object
    requests = []
    for each it in p.items
        requests.Push({ path: "search/" + it.mediaType, params: { query: it.title, include_adult: "false" } })
    end for
    art = {}
    results = TmdbGetMany(requests)
    for i = 0 to p.items.Count() - 1
        it = p.items[i]
        list = TmdbResults(results[i], it.mediaType)
        pick = invalid
        for each r in list
            if pick = invalid and TitleKey(r.title) = TitleKey(it.title) then pick = r
        end for
        if pick = invalid and list.Count() > 0 then pick = list[0]
        if pick <> invalid then art[ArtKey(it.mediaType, it.title)] = { poster: pick.poster, backdrop: pick.backdrop }
    end for
    return { art: art }
end function

'' Id de TMDb de un título guardado por Android con el id de otro sitio: mismo nombre (y año).
function TmdbMatch(mediaType as string, title as string, year as string) as string
    params = { query: title, include_adult: "false" }
    if year <> "" and mediaType = "movie" then params.primary_release_year = year
    if year <> "" and mediaType = "tv" then params.first_air_date_year = year
    json = TmdbGet("search/" + mediaType, params)
    results = TmdbResults(json, mediaType)
    if results.Count() = 0 and year <> "" then results = TmdbResults(TmdbGet("search/" + mediaType, { query: title, include_adult: "false" }), mediaType)
    for each r in results
        if TitleKey(r.title) = TitleKey(title) then return r.itemId
    end for
    if results.Count() > 0 then return results[0].itemId
    return ""
end function

' Episodios de una temporada (getEpisodesBySeason).
function ApiSeason(p as object) as object
    json = TmdbGet("tv/" + AsStr(p.id) + "/season/" + AsStr(p.number))
    if json = invalid then return { error: "No se pudieron cargar los episodios." }
    episodes = []
    if GetInterface(json.episodes, "ifArray") <> invalid then
        for each e in json.episodes
            episodes.Push({
                id: AsStr(e.id)
                number: ToInt(e.episode_number)
                title: AsStr(e.name)
                overview: AsStr(e.overview)
                poster: TmdbImage(e.still_path, "w300")
                airDate: AsStr(e.air_date)
                runtime: ToInt(e.runtime)
            })
        end for
    end if
    return { number: ToInt(p.number), episodes: episodes }
end function

function ApiSearch(p as object) as object
    if TmdbKey() = "" then return NoTmdbKey()
    query = AsStr(p.query).Trim()
    if query = "" then return { items: [] }
    json = TmdbGet("search/multi", { query: query, page: 1, include_adult: "false" })
    return { query: query, items: TmdbResults(json, "movie", true) }
end function

' Filmografía de una persona del reparto (PeopleTvFragment).
function ApiPerson(p as object) as object
    json = TmdbGet("person/" + AsStr(p.id), { append_to_response: "combined_credits" })
    if json = invalid then return { error: "No se pudo cargar la información." }
    items = []
    seen = {}
    if json.combined_credits <> invalid and GetInterface(json.combined_credits.cast, "ifArray") <> invalid then
        for each c in json.combined_credits.cast
            item = TmdbItem(c, "movie")
            if item <> invalid and item.mediaType <> "person" and not seen.DoesExist(item.mediaType + item.itemId) then
                seen[item.mediaType + item.itemId] = true
                items.Push(item)
            end if
        end for
    end if
    items.SortBy("popularity", "r")
    return { title: AsStr(json.name), overview: AsStr(json.biography), poster: TmdbImage(json.profile_path, "w500"), items: items }
end function

' =======================================================================================
' Enlaces de video (TmdbProvider.getServers -> VixSrcExtractor)
' =======================================================================================

' p: { mediaType, id, season, episode } -> { ok, url, format, headers, server } o { ok: false, error }
function ApiResolve(p as object) as object
    lang = AudioLanguage()
    base = "https://vixsrc.to"
    if p.mediaType = "movie" then
        apiPath = "/api/movie/" + AsStr(p.id)
    else
        apiPath = "/api/tv/" + AsStr(p.id) + "/" + AsStr(p.season) + "/" + AsStr(p.episode)
    end if
    apiUrl = base + apiPath + "?lang=" + lang
    jsonHeaders = { Accept: "application/json, text/plain, */*", "X-Requested-With": "XMLHttpRequest", Referer: base }
    pageHeaders = { Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", "X-Requested-With": "XMLHttpRequest", Referer: base }

    embed = ""
    page = { code: -1, body: "" }
    ' 410 = enlace vencido: se pide uno nuevo una vez (igual que Android).
    for attempt = 1 to 2
        api = Http(apiUrl, { headers: jsonHeaders })
        json = JsonOf(api.body)
        if api.code <> 200 or json = invalid or AsStr(json.src) = "" then return { ok: false, error: "Este título no está disponible en el servidor." }
        embed = AsStr(json.src)
        while Left(embed, 1) = "/"
            embed = Mid(embed, 2)
        end while
        page = Http(base + "/" + embed, { headers: pageHeaders })
        if page.code <> 410 then exit for
    end for
    if page.code <> 200 then return { ok: false, error: "El servidor no respondió (HTTP " + page.code.ToStr() + ")." }

    script = page.body
    videoId = SubBefore(SubAfter(SubAfter(script, "window.video = {"), "id: '"), "',").Trim()
    master = SubAfter(script, "window.masterPlaylist")
    token = SubBefore(SubAfter(master, "'token': '"), "',").Trim()
    expires = SubBefore(SubAfter(master, "'expires': '"), "',").Trim()
    hasB = Instr(1, SubBefore(SubAfter(script, "url:"), ","), "b=1") > 0
    canFhd = Instr(1, script, "window.canPlayFHD = true") > 0
    if videoId = "" or token = "" then return { ok: false, error: "No se encontró el video en el servidor." }

    url = base + "/playlist/" + videoId + "?token=" + UrlEnc(token) + "&expires=" + UrlEnc(expires)
    if hasB then url = url + "&b=1"
    if canFhd then url = url + "&h=1"
    url = url + "&lang=" + lang
    return { ok: true, server: "VixSrc", url: url, format: "hls", headers: ["Referer: " + base + "/" + embed, "User-Agent: " + BrowserUA()] }
end function
