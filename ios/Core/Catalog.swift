// Catálogo TMDb (TmdbProvider) y enlace de VixSrc: port de Catalog.brs del canal de Roku.
import Foundation

public enum Catalog {
    static let noKeyMessage = "El panel no envió una clave de TMDb para esta cuenta. Pídele a tu distribuidor que la configure."
    static let noCatalogMessage = "No se pudo cargar el catálogo. Revisa tu conexión."
    static let noInfoMessage = "No se pudo cargar la información."

    // MARK: - TMDb

    static func tmdbKey() -> String { Reg.read("tmdb_api_key") }

    static func tmdbURL(_ path: String, _ params: [String: String] = [:]) -> String {
        var url = "https://api.themoviedb.org/3/" + path
        var lang = catalogLanguage()
        if lang == "es" { lang = "es-MX" }
        url += (path.contains("?") ? "&" : "?") + "language=" + lang
        let key = tmdbKey()
        if !key.isEmpty && !key.hasPrefix("eyJ") { url += "&api_key=" + urlEnc(key) }
        for name in params.keys.sorted() { url += "&" + name + "=" + urlEnc(params[name] ?? "") }
        return url
    }

    static func tmdbHeaders() -> [String: String] {
        var headers = ["Accept": "application/json"]
        let key = tmdbKey()
        if key.hasPrefix("eyJ") { headers["Authorization"] = "Bearer " + key }
        return headers
    }

    static func tmdbGet(_ path: String, _ params: [String: String] = [:]) async -> JSON? {
        let r = await http(tmdbURL(path, params), headers: tmdbHeaders(), timeout: 15)
        return r.code == 200 ? jsonOf(r.body) as? JSON : nil
    }

    static func tmdbGetMany(_ requests: [(path: String, params: [String: String])]) async -> [JSON?] {
        let res = await httpParallel(requests.map { tmdbURL($0.path, $0.params) }, headers: tmdbHeaders(), timeout: 25)
        return res.map { $0.code == 200 ? jsonOf($0.body) as? JSON : nil }
    }

    /// Nota con un decimal ("7.4"); vacío si no hay.
    static func rating1(_ v: Any?) -> String {
        let n = Int((toDouble(v) * 10 + 0.5).rounded(.down))
        return n <= 0 ? "" : "\(n / 10).\(n % 10)"
    }

    /// Elemento con su popularidad (para ordenar); nil si no es película, serie ni persona.
    static func tmdbItem(_ value: Any?, _ defaultType: String) -> (item: Item, popularity: Double)? {
        guard let r = value as? JSON else { return nil }
        var mediaType = asStr(r["media_type"])
        if mediaType.isEmpty { mediaType = defaultType }
        let popularity = toDouble(r["popularity"])
        if mediaType == "person" {
            return (Item(itemId: asStr(r["id"]), mediaType: "person", title: asStr(r["name"]), overview: asStr(r["character"]),
                         poster: tmdbImage(r["profile_path"], "w342")), popularity)
        }
        guard mediaType == "movie" || mediaType == "tv" else { return nil }
        var title = asStr(r["title"])
        if title.isEmpty { title = asStr(r["name"]) }
        var date = asStr(r["release_date"])
        if date.isEmpty { date = asStr(r["first_air_date"]) }
        return (Item(itemId: asStr(r["id"]), mediaType: mediaType, title: title, overview: asStr(r["overview"]),
                     poster: tmdbImage(r["poster_path"], "w342"), backdrop: tmdbImage(r["backdrop_path"], "w1280"),
                     rating: rating1(r["vote_average"]), year: String(date.prefix(4))), popularity)
    }

    static func tmdbResults(_ json: Any?, _ defaultType: String, withPeople: Bool = false) -> [(item: Item, popularity: Double)] {
        guard let results = (json as? JSON)?["results"] as? [Any] else { return [] }
        return results.compactMap { tmdbItem($0, defaultType) }.filter { withPeople || $0.item.mediaType != "person" }
    }

    /// Orden por popularidad (mayor primero), estable como SortBy de Roku.
    static func byPopularity(_ list: [(item: Item, popularity: Double)]) -> [Item] {
        list.enumerated().sorted { $0.element.popularity != $1.element.popularity ? $0.element.popularity > $1.element.popularity : $0.offset < $1.offset }
            .map { $0.element.item }
    }

    static func mixByPopularity(_ a: [(item: Item, popularity: Double)], _ b: [(item: Item, popularity: Double)]) -> [Item] {
        byPopularity(a + b)
    }

    static func addRow(_ rows: inout [Row], _ title: String, _ items: [Item], _ paging: Paging?) {
        if !items.isEmpty { rows.append(Row(title: title, items: items, paging: paging)) }
    }

    // MARK: - API

    /// Inicio (TmdbProvider.getHome): tendencias, populares, anime y por plataforma.
    public static func home() async throws -> [Row] {
        if tmdbKey().isEmpty { throw CoreError(noKeyMessage) }
        let lang = catalogLanguage()
        let region = lang == "en" ? "US" : lang.uppercased()
        let anime = ["with_keywords": "210024|222243"]
        let platforms = [("Popular en Netflix", "8"), ("Popular en Amazon", "9|10|119"), ("Popular en Disney+", "337"),
                         ("Popular en Hulu", "15"), ("Popular en Apple TV+", "350"), ("Popular en HBO", "384|1899")]
        var requests: [(path: String, params: [String: String])] = [
            ("trending/all/day", ["page": "1"]), ("movie/popular", ["page": "1"]), ("tv/popular", ["page": "1"]),
            ("discover/movie", anime), ("discover/tv", anime),
        ]
        for p in platforms {
            let params = ["with_watch_providers": p.1, "watch_region": region, "sort_by": "popularity.desc"]
            requests.append(("discover/movie", params))
            requests.append(("discover/tv", params))
        }
        let res = await tmdbGetMany(requests)
        if res[0] == nil && res[1] == nil && res[2] == nil { throw CoreError(noCatalogMessage) }

        var rows: [Row] = []
        addRow(&rows, "Tendencias", tmdbResults(res[0], "movie").map(\.item), Paging(path: "trending/all/day", mediaType: ""))
        addRow(&rows, "Películas populares", tmdbResults(res[1], "movie").map(\.item), Paging(path: "movie/popular", mediaType: "movie"))
        addRow(&rows, "Series populares", tmdbResults(res[2], "tv").map(\.item), Paging(path: "tv/popular", mediaType: "tv"))
        addRow(&rows, "Anime populares", mixByPopularity(tmdbResults(res[3], "movie"), tmdbResults(res[4], "tv")), nil)
        for (i, p) in platforms.enumerated() {
            addRow(&rows, p.0, mixByPopularity(tmdbResults(res[5 + i * 2], "movie"), tmdbResults(res[6 + i * 2], "tv")), nil)
        }
        return rows
    }

    /// Películas o Series (MoviesTvFragment / TvShowsTvFragment): listas y una fila por género.
    public static func catalog(_ mediaType: String) async throws -> [Row] {
        if tmdbKey().isEmpty { throw CoreError(noKeyMessage) }
        let lists: [(String, String)]
        let genreIds: [String]
        if mediaType == "movie" {
            lists = [("Populares", "movie/popular"), ("Mejor valoradas", "movie/top_rated"), ("En cines", "movie/now_playing"), ("Próximamente", "movie/upcoming")]
            genreIds = ["28", "12", "16", "35", "80", "99", "18", "10751", "14", "27", "10749", "878", "53"]
        } else {
            lists = [("Populares", "tv/popular"), ("Mejor valoradas", "tv/top_rated"), ("Al aire", "tv/on_the_air"), ("Emitiéndose hoy", "tv/airing_today")]
            genreIds = ["10759", "16", "35", "80", "99", "18", "10751", "10762", "9648", "10765", "10768"]
        }
        var requests: [(path: String, params: [String: String])] = lists.map { ($0.1, ["page": "1"]) }
        for g in genreIds { requests.append(("discover/" + mediaType, ["with_genres": g, "sort_by": "popularity.desc"])) }
        requests.append(("genre/" + mediaType + "/list", [:]))
        let res = await tmdbGetMany(requests)

        var names: [String: String] = [:]
        for g in (res.last??["genres"] as? [Any]) ?? [] {
            let g = g as? JSON
            names[asStr(g?["id"])] = asStr(g?["name"])
        }

        var rows: [Row] = []
        for (i, l) in lists.enumerated() {
            addRow(&rows, l.0, tmdbResults(res[i], mediaType).map(\.item), Paging(path: l.1, mediaType: mediaType))
        }
        for (i, id) in genreIds.enumerated() {
            var title = names[id] ?? ""
            if title.isEmpty { title = "Género " + id }
            let params = ["with_genres": id, "sort_by": "popularity.desc"]
            addRow(&rows, title, tmdbResults(res[lists.count + i], mediaType).map(\.item),
                   Paging(path: "discover/" + mediaType, params: params, mediaType: mediaType))
        }
        if rows.isEmpty { throw CoreError(noCatalogMessage) }
        return rows
    }

    /// Siguiente página de una fila ("Ver más" al llegar al final). next es nil cuando no hay más.
    public static func page(_ paging: Paging) async -> (items: [Item], next: Paging?) {
        var params = paging.params
        params["page"] = String(paging.page)
        let json = await tmdbGet(paging.path, params)
        let items = tmdbResults(json, paging.mediaType).map(\.item)
        let totalPages = json == nil ? 1 : toInt(json?["total_pages"], 1)
        if paging.page >= totalPages || items.isEmpty { return (items, nil) }
        var next = paging
        next.page += 1
        return (items, next)
    }

    /// Ficha de película o serie (getMovie / getTvShow) con reparto, temporadas y recomendaciones.
    /// match: el id no es de TMDb (título guardado por Android con el id de otro sitio): se busca por título y año.
    public static func details(mediaType: String, id: String, match: Bool = false, title: String = "", year: String = "") async throws -> Details {
        if tmdbKey().isEmpty { throw CoreError(noKeyMessage) }
        var id = id
        if match {
            id = await tmdbMatch(mediaType, title, year)
            if id.isEmpty { throw CoreError("No se encontró este título en el catálogo.") }
        }
        guard let json = await tmdbGet(mediaType + "/" + id, ["append_to_response": "credits,recommendations,external_ids"]),
              var item = tmdbItem(json, mediaType)?.item else { throw CoreError(noInfoMessage) }
        item.poster = tmdbImage(json["poster_path"], "w500")
        let genres = ((json["genres"] as? [Any]) ?? []).map { asStr(($0 as? JSON)?["name"]) }
        var runtime = toInt(json["runtime"])
        if runtime == 0, let times = json["episode_run_time"] as? [Any], !times.isEmpty { runtime = toInt(times[0]) }

        var cast: [Item] = []
        for c in ((json["credits"] as? JSON)?["cast"] as? [Any]) ?? [] where cast.count < 20 {
            let c = c as? JSON
            cast.append(Item(itemId: asStr(c?["id"]), mediaType: "person", title: asStr(c?["name"]),
                             overview: asStr(c?["character"]), poster: tmdbImage(c?["profile_path"], "w342")))
        }

        var seasons: [SeasonInfo] = []
        var specials: SeasonInfo?
        for s in (json["seasons"] as? [Any]) ?? [] {
            let s = s as? JSON
            let season = SeasonInfo(number: toInt(s?["season_number"]), title: asStr(s?["name"]),
                                    poster: tmdbImage(s?["poster_path"], "w342"), episodeCount: toInt(s?["episode_count"]))
            if season.number == 0 { specials = season } else { seasons.append(season) }
        }
        if let specials { seasons.append(specials) }

        return Details(item: item, genres: genres.joined(separator: ", "), runtime: runtime, tagline: asStr(json["tagline"]),
                       imdbId: asStr(json["imdb_id"]), cast: cast,
                       recommendations: tmdbResults(json["recommendations"], mediaType).map(\.item), seasons: seasons)
    }

    /// Portada y banner de TMDb (con la clave de la cuenta) para un título guardado en la nube con
    /// las imágenes de otro sitio o sin ellas. nil si TMDb no lo encuentra.
    public static func artwork(_ mediaType: String, _ title: String) async -> (poster: String, backdrop: String)? {
        let results = tmdbResults(await tmdbGet("search/" + mediaType, ["query": title, "include_adult": "false"]), mediaType)
        guard let r = results.first(where: { titleKey($0.item.title) == titleKey(title) }) ?? results.first else { return nil }
        return (r.item.poster, r.item.backdrop)
    }

    /// Id de TMDb de un título guardado por Android con el id de otro sitio: mismo nombre (y año).
    static func tmdbMatch(_ mediaType: String, _ title: String, _ year: String) async -> String {
        var params = ["query": title, "include_adult": "false"]
        if !year.isEmpty && mediaType == "movie" { params["primary_release_year"] = year }
        if !year.isEmpty && mediaType == "tv" { params["first_air_date_year"] = year }
        var results = tmdbResults(await tmdbGet("search/" + mediaType, params), mediaType)
        if results.isEmpty && !year.isEmpty {
            results = tmdbResults(await tmdbGet("search/" + mediaType, ["query": title, "include_adult": "false"]), mediaType)
        }
        if let same = results.first(where: { titleKey($0.item.title) == titleKey(title) }) { return same.item.itemId }
        return results.first?.item.itemId ?? ""
    }

    /// Episodios de una temporada (getEpisodesBySeason).
    public static func season(tvId: String, number: Int) async throws -> [Episode] {
        guard let json = await tmdbGet("tv/" + tvId + "/season/" + String(number)) else {
            throw CoreError("No se pudieron cargar los episodios.")
        }
        return ((json["episodes"] as? [Any]) ?? []).map { e in
            let e = e as? JSON
            return Episode(id: asStr(e?["id"]), number: toInt(e?["episode_number"]), title: asStr(e?["name"]),
                           overview: asStr(e?["overview"]), poster: tmdbImage(e?["still_path"], "w300"),
                           airDate: asStr(e?["air_date"]), runtime: toInt(e?["runtime"]))
        }
    }

    /// Búsqueda de películas, series y personas. Sin clave de TMDb devuelve una lista vacía
    /// (Roku devuelve el error NoTmdbKey; la firma aquí no lanza).
    public static func search(_ query: String) async -> [Item] {
        if tmdbKey().isEmpty { return [] }
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if q.isEmpty { return [] }
        let json = await tmdbGet("search/multi", ["query": q, "page": "1", "include_adult": "false"])
        return tmdbResults(json, "movie", withPeople: true).map(\.item)
    }

    /// Filmografía de una persona del reparto (PeopleTvFragment).
    public static func person(_ id: String) async throws -> PersonPage {
        guard let json = await tmdbGet("person/" + id, ["append_to_response": "combined_credits"]) else { throw CoreError(noInfoMessage) }
        var items: [(item: Item, popularity: Double)] = []
        var seen = Set<String>()
        for c in ((json["combined_credits"] as? JSON)?["cast"] as? [Any]) ?? [] {
            if let found = tmdbItem(c, "movie"), found.item.mediaType != "person",
               seen.insert(found.item.mediaType + found.item.itemId).inserted {
                items.append(found)
            }
        }
        return PersonPage(title: asStr(json["name"]), overview: asStr(json["biography"]),
                          poster: tmdbImage(json["profile_path"], "w500"), items: byPopularity(items))
    }

    // MARK: - VixSrc (TmdbProvider.getServers -> VixSrcExtractor)

    /// ApiResolve: enlace HLS de VixSrc con el audio del idioma de Ajustes.
    public static func resolveVixSrc(_ p: PlaybackRequest) async -> Result<Stream, CoreError> {
        let lang = audioLanguage()
        let base = "https://vixsrc.to"
        let apiPath = p.mediaType == "movie" ? "/api/movie/" + p.id : "/api/tv/" + p.id + "/" + String(p.season) + "/" + String(p.episode)
        let apiURL = base + apiPath + "?lang=" + lang
        let jsonHeaders = ["Accept": "application/json, text/plain, */*", "X-Requested-With": "XMLHttpRequest", "Referer": base]
        let pageHeaders = ["Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", "X-Requested-With": "XMLHttpRequest", "Referer": base]

        var embed = ""
        var page = HTTPResult.failed
        // 410 = enlace vencido: se pide uno nuevo una vez (igual que Android).
        for _ in 1...2 {
            let api = await http(apiURL, headers: jsonHeaders)
            let src = asStr((jsonOf(api.body) as? JSON)?["src"])
            if api.code != 200 || src.isEmpty { return .failure(CoreError("Este título no está disponible en el servidor.")) }
            embed = src
            while embed.hasPrefix("/") { embed.removeFirst() }
            page = await http(base + "/" + embed, headers: pageHeaders)
            if page.code != 410 { break }
        }
        if page.code != 200 { return .failure(CoreError("El servidor no respondió (HTTP \(page.code)).")) }

        let script = page.body
        let trim: (String) -> String = { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        let videoId = trim(subBefore(subAfter(subAfter(script, "window.video = {"), "id: '"), "',"))
        let master = subAfter(script, "window.masterPlaylist")
        let token = trim(subBefore(subAfter(master, "'token': '"), "',"))
        let expires = trim(subBefore(subAfter(master, "'expires': '"), "',"))
        let hasB = subBefore(subAfter(script, "url:"), ",").contains("b=1")
        let canFhd = script.contains("window.canPlayFHD = true")
        if videoId.isEmpty || token.isEmpty { return .failure(CoreError("No se encontró el video en el servidor.")) }

        var url = base + "/playlist/" + videoId + "?token=" + urlEnc(token) + "&expires=" + urlEnc(expires)
        if hasB { url += "&b=1" }
        if canFhd { url += "&h=1" }
        url += "&lang=" + lang
        return .success(Stream(url: url, format: "hls", headers: ["Referer": base + "/" + embed, "User-Agent": browserUA], server: "VixSrc"))
    }
}
