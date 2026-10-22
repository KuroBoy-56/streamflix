// Búsqueda automática de servidores al reproducir (TmdbProvider.getServers en Android): port de
// Sources.brs. El título se busca en los sitios latinos y se arma una lista de servidores en el
// orden del idioma de Ajustes. El reproductor prueba uno por uno hasta que alguno funciona.
//
// shortcut: PelisPlus y SoloLatino no están (bloqueo Cloudflare y prueba de trabajo con navegador);
// agregarlos si dejan de exigir navegador.
import Foundation

public enum Sources {
    // Dominio de cada sitio: el que el dueño puso en el panel (Dominios de proveedores) o el de la app.
    static var siteCuevana: String { siteHost("cuevana3", "wv3.cuevana3.eu") }
    static var sitePoseidon: String { siteHost("poseidonhd2", "www.poseidonhd2.co") }
    static var siteCineCalidad: String { siteHost("cinecalidad", "www.cinecalidad.am") }

    static func siteHost(_ key: String, _ fallback: String) -> String {
        let host = Reg.read("domain_" + key)
        return "https://" + (host.isEmpty ? fallback : host)
    }

    /// Título encontrado en Cuevana/Poseidon: ruta tras "movies/"/"series/" y nombre (slug).
    struct NextFound: Equatable { var path: String; var name: String }

    struct Found { var cuevana: NextFound?; var poseidon: NextFound?; var cine: String? }

    static func nextData(_ html: String) -> JSON? {
        let marker = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
        guard let start = html.range(of: marker),
              let finish = html.range(of: "</script>", range: start.upperBound..<html.endIndex) else { return nil }
        let json = jsonOf(String(html[start.upperBound..<finish.lowerBound])) as? JSON
        return (json?["props"] as? JSON)?["pageProps"] as? JSON
    }

    /// Cuevana y PoseidonHD2 (mismo sistema): resultados con el id de TMDb -> emparejado exacto.
    static func nextSiteFind(_ html: String, _ tmdbId: String, _ mediaType: String) -> NextFound? {
        guard let movies = nextData(html)?["movies"] as? [Any] else { return nil }
        let prefix = mediaType == "tv" ? "series/" : "movies/"
        for x in movies {
            let x = x as? JSON
            let slug = asStr((x?["url"] as? JSON)?["slug"])
            if asStr(x?["TMDbId"]) == tmdbId && slug.hasPrefix(prefix) {
                var name = asStr((x?["slug"] as? JSON)?["name"])
                if name.isEmpty { name = slug.components(separatedBy: "/").last ?? "" }
                return NextFound(path: String(slug.dropFirst(prefix.count)), name: name)
            }
        }
        return nil
    }

    static func cuevanaPage(_ found: NextFound, _ p: PlaybackRequest) -> String {
        if p.mediaType == "movie" { return siteCuevana + "/ver-pelicula/" + found.name }
        return siteCuevana + "/episodio/" + found.name + "-temporada-" + String(p.season) + "-episodio-" + String(p.episode)
    }

    static func poseidonPage(_ found: NextFound, _ p: PlaybackRequest) -> String {
        if p.mediaType == "movie" { return sitePoseidon + "/pelicula/" + found.path }
        return sitePoseidon + "/serie/" + found.path + "/temporada/" + String(p.season) + "/episodio/" + String(p.episode)
    }

    /// Servidores de una página de Cuevana/Poseidon, separados por idioma.
    static func nextSiteServers(_ html: String, _ siteName: String, _ base: String, _ out: inout [Server]) {
        guard let pp = nextData(html) else { return }
        guard let holder = [pp["thisMovie"], pp["episode"], pp["thisEpisode"]].lazy.compactMap({ $0 as? JSON }).first,
              let videos = holder["videos"] as? JSON else { return }
        // Roku recorre las claves en orden alfabético; da igual porque luego se ordena por idioma.
        for (key, lang) in [("english", "sub"), ("latino", "lat"), ("spanish", "cast")] {
            for v in (videos[key] as? [Any]) ?? [] {
                let v = v as? JSON
                let url = asStr(v?["result"])
                if !url.isEmpty { out.append(Server(name: siteName + " · " + asStr(v?["cyberlocker"]), url: url, referer: base + "/", lang: lang)) }
            }
        }
    }

    static func xmlText(_ s: String) -> String {
        s.replacingOccurrences(of: "&amp;", with: "&").replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&apos;", with: "'")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// CineCalidad (sitio latino): búsqueda por título y año; las series listan sus episodios.
    static func cineCalidadFind(_ html: String, _ title: String, _ year: String, _ mediaType: String) -> String {
        let kind = mediaType == "tv" ? "/ver-serie/" : "/ver-pelicula/"
        for hit in allMatches("<article id=\"post-\\d+\" class=\"item [^\"]*\"(.*?)</article>", html, options: .dotMatchesLineSeparators) {
            let block = hit[1]
            let href = firstGroup("<a href=\"([^\"]+)\"", block)
            let name = xmlText(firstGroup("alt=\"([^\"]*)\"", block))
            let found = firstGroup("<p>(\\d{4})</p>", block)
            let yearOk = year.isEmpty || found.isEmpty || abs(toInt(found) - toInt(year)) <= 1
            if href.contains(kind) && yearOk && titleMatches(name, title) { return href }
        }
        return ""
    }

    static func cineCalidadEpisode(_ html: String, _ season: String, _ episode: String) -> String {
        firstGroup("numerando\">S" + season + "-E" + episode + "</div>\\s*<div class=\"episodiotitle\">\\s*<a href=\"([^\"]+)\"",
                   html, options: .dotMatchesLineSeparators)
    }

    static func cineCalidadServers(_ html: String, _ out: inout [Server]) {
        for hit in allMatches("<li[^>]*class=\"dooplay_player_option[^\"]*\"[^>]*data-option=\"([^\"]+)\"", html) {
            let url = hit[1]
            if !url.contains("youtube.com") { out.append(Server(name: "CineCalidad · " + hostOf(url), url: url, referer: siteCineCalidad + "/", lang: "lat")) }
        }
    }

    static func hostOf(_ url: String) -> String {
        var s = url
        if let r = s.range(of: "://") { s = String(s[r.upperBound...]) }
        if let j = s.firstIndex(of: "/") { s = String(s[..<j]) }
        return s.lowercased()
    }

    static func originOf(_ url: String) -> String {
        guard let r = url.range(of: "://") else { return "" }
        return String(url[..<r.upperBound]) + hostOf(url)
    }

    /// Sitios que conoce la app: "cuevana", "poseidon", "cine" y "vix" (VixSrc, del proveedor TMDb).
    static let allSites: Set<String> = ["cuevana", "poseidon", "cine", "vix"]

    /// Solo los proveedores que el panel asignó (assigned_servers, nombre en minúsculas y solo
    /// [a-z0-9]). Si no asignó ninguno de los que conoce la app, se usan todos para que reproduzca.
    static func assignedSites(_ assigned: String) -> Set<String> {
        let known = ["cuevana3": "cuevana", "poseidonhd2": "poseidon", "cinecalidad": "cine"]
        var out = Set<String>()
        for name in assigned.split(separator: ",") {
            let key = titleKey(String(name))
            if let site = known[key] { out.insert(site) }
            if key.hasPrefix("tmdb") { out.insert("vix") }
        }
        return out.isEmpty ? allSites : out
    }

    /// Busca el título (por id de TMDb) en los sitios pedidos.
    static func findOnSites(_ p: PlaybackRequest, sites: Set<String> = Sources.allSites) async -> Found {
        // Título en español latino y original, y año, para buscar en los sitios.
        let info = await Catalog.tmdbGet(p.mediaType + "/" + p.id)
        var titles: [String] = []
        var year = ""
        if let info {
            for key in ["title", "name", "original_title", "original_name"] {
                let t = asStr(info[key])
                if !t.isEmpty && !titles.contains(t) { titles.append(t) }
            }
            year = String((asStr(info["release_date"]) + asStr(info["first_air_date"])).prefix(4))
        }

        var found = Found()
        let useCuevana = sites.contains("cuevana"), usePoseidon = sites.contains("poseidon"), useCine = sites.contains("cine")
        for title in titles where (useCuevana && found.cuevana == nil) || (usePoseidon && found.poseidon == nil) || (useCine && found.cine == nil) {
            let q = urlEnc(title)
            // Una URL vacía no se pide (http devuelve error al instante).
            let cuevanaURL: String = useCuevana ? siteCuevana + "/search?q=" + q : ""
            let poseidonURL: String = usePoseidon ? sitePoseidon + "/search?q=" + q : ""
            let cineURL: String = useCine ? siteCineCalidad + "/?s=" + q.replacingOccurrences(of: "%20", with: "+") : ""
            let res = await httpParallel([cuevanaURL, poseidonURL, cineURL], timeout: 20)
            if useCuevana && found.cuevana == nil { found.cuevana = nextSiteFind(res[0].body, p.id, p.mediaType) }
            if usePoseidon && found.poseidon == nil { found.poseidon = nextSiteFind(res[1].body, p.id, p.mediaType) }
            if useCine && found.cine == nil {
                let href = cineCalidadFind(res[2].body, title, year, p.mediaType)
                if !href.isEmpty { found.cine = href }
            }
        }
        return found
    }

    /// IDs del título en cada sitio, con el formato que usa la app Android en la nube (clave =
    /// nombre del proveedor en Android), para escribir en los mismos registros que el celular.
    static func siteLinks(_ found: Found, _ mediaType: String) -> [String: SiteLink] {
        var links: [String: SiteLink] = [:]
        if let c = found.cuevana {
            links["Cuevana_3"] = SiteLink(style: "cuevana", id: (mediaType == "tv" ? "ver-serie/" : "ver-pelicula/") + c.name, slug: c.name)
        }
        if let ps = found.poseidon { links["Poseidonhd2"] = SiteLink(style: "poseidon", id: ps.path) }
        if let cine = found.cine {
            let slug = cine.components(separatedBy: "/").last(where: { !$0.isEmpty }) ?? ""
            links["CineCalidad"] = SiteLink(style: "cine", id: cine, slug: slug, base: siteCineCalidad)
        }
        return links
    }

    /// ApiLinks: ids del título en Cuevana, PoseidonHD2 y CineCalidad.
    public static func links(_ p: PlaybackRequest) async -> [String: SiteLink] {
        siteLinks(await findOnSites(p), p.mediaType)
    }

    /// ApiServers: servidores en orden de intento según el idioma de audio de Ajustes, solo de
    /// los proveedores asignados por el panel.
    public static func servers(_ p: PlaybackRequest) async -> (servers: [Server], links: [String: SiteLink]) {
        let lang = audioLanguage()
        let sites = assignedSites(Reg.read("assigned_servers"))
        let vix = Server(name: "VixSrc", url: "vixsrc", referer: "", lang: "multi")
        var servers: [Server] = []
        if lang == "en" && sites.contains("vix") { servers.append(vix) }
        let found = await findOnSites(p, sites: sites)
        var links = siteLinks(found, p.mediaType)

        var pages: [String] = []
        var names: [String] = []
        if let c = found.cuevana { pages.append(cuevanaPage(c, p)); names.append("cuevana") }
        if let ps = found.poseidon { pages.append(poseidonPage(ps, p)); names.append("poseidon") }
        if let cine = found.cine { pages.append(cine); names.append("cine") }
        var found2: [Server] = []
        let res = await httpParallel(pages, timeout: 20)
        for (i, name) in names.enumerated() {
            var html = res[i].body
            switch name {
            case "cuevana": nextSiteServers(html, "Cuevana", siteCuevana, &found2)
            case "poseidon": nextSiteServers(html, "PoseidonHD", sitePoseidon, &found2)
            default:
                if p.mediaType == "tv" {
                    let ep = cineCalidadEpisode(html, String(p.season), String(p.episode))
                    if !ep.isEmpty { links["CineCalidad"]?.episodeId = ep }
                    html = ep.isEmpty ? "" : await http(ep, headers: ["Referer": siteCineCalidad + "/"]).body
                }
                cineCalidadServers(html, &found2)
            }
        }
        return (orderServers(found2, lang: lang, initial: servers, vix: sites.contains("vix") ? vix : nil), links)
    }

    /// Orden por idioma (español latino por defecto): latino, castellano, VixSrc (multiaudio) y
    /// subtitulado. En inglés: VixSrc, subtitulado, latino y castellano.
    static func orderServers(_ found: [Server], lang: String, initial: [Server], vix: Server?) -> [Server] {
        var servers = initial
        for tag in lang == "en" ? ["sub", "lat", "cast"] : ["lat", "cast", "vix", "sub"] {
            if tag == "vix" { if let vix { servers.append(vix) }; continue }
            servers += found.filter { $0.lang == tag && !unsupported($0.name + " " + $0.url) }
        }
        return servers
    }

    /// Nombre visible del servidor: sin el sitio, con el idioma ("Servidor 2 · Castellano").
    public static func label(_ index: Int, _ server: Server) -> String {
        let lang = ["lat": "Latino", "cast": "Castellano", "sub": "Subtitulado", "multi": "Multiaudio", "en": "Inglés"][server.lang] ?? ""
        return "Servidor \(index + 1)" + (lang.isEmpty ? "" : " · " + lang)
    }

    /// Servidores que no se pueden abrir sin navegador (Filemoon pide una firma del navegador;
    /// Netu, YouTube y similares solo funcionan dentro de un navegador).
    static func unsupported(_ text: String) -> Bool {
        let t = text.lowercased()
        return ["filemoon", "byse", "netu", "hqq.", "waaw", "youtube", "streamsb", "1fichier"].contains { t.contains($0) }
    }
}
