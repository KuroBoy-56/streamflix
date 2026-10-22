' Búsqueda automática de servidores al reproducir (TmdbProvider.getServers en Android):
' el título se busca en los sitios latinos y se arma una lista de servidores en el orden del
' idioma de Ajustes. El reproductor prueba uno por uno hasta que alguno funciona.
' Se ejecuta SOLO dentro de ApiTask.
'
' shortcut: PelisPlus y SoloLatino no están (bloqueo Cloudflare y prueba de trabajo con navegador);
' agregarlos si dejan de exigir navegador.

' Dominio de cada sitio: el que el dueño puso en el panel (Dominios de proveedores) o el de la app.
function SiteHost(key as string, default as string) as string
    host = RegRead("domain_" + key)
    if host = "" then host = default
    return "https://" + host
end function

function SiteCuevana() as string
    return SiteHost("cuevana3", "wv3.cuevana3.eu")
end function

function SitePoseidon() as string
    return SiteHost("poseidonhd2", "www.poseidonhd2.co")
end function

function SiteCineCalidad() as string
    return SiteHost("cinecalidad", "www.cinecalidad.am")
end function

function NextData(html as string) as dynamic
    marker = "<script id=" + Chr(34) + "__NEXT_DATA__" + Chr(34) + " type=" + Chr(34) + "application/json" + Chr(34) + ">"
    start = Instr(1, html, marker)
    if start = 0 then return invalid
    start = start + Len(marker)
    finish = Instr(start, html, "</script>")
    if finish = 0 then return invalid
    json = ParseJson(Mid(html, start, finish - start))
    if json = invalid or json.props = invalid then return invalid
    return json.props.pageProps
end function

function SiteHeaders(base as string) as object
    return { Referer: base + "/" }
end function

' Cuevana y PoseidonHD2 (mismo sistema): resultados con el id de TMDb -> emparejado exacto.
function NextSiteFind(html as string, tmdbId as string, mediaType as string) as dynamic
    pp = NextData(html)
    if pp = invalid or GetInterface(pp.movies, "ifArray") = invalid then return invalid
    prefix = "movies/"
    if mediaType = "tv" then prefix = "series/"
    for each x in pp.movies
        slug = ""
        if x.url <> invalid then slug = AsStr(x.url.slug)
        if AsStr(x.TMDbId) = tmdbId and Left(slug, Len(prefix)) = prefix then
            name = ""
            if x.slug <> invalid and GetInterface(x.slug, "ifAssociativeArray") <> invalid then name = AsStr(x.slug.name)
            parts = slug.Split("/")
            if name = "" then name = parts[parts.Count() - 1]
            return { path: Mid(slug, Len(prefix) + 1), name: name }
        end if
    end for
    return invalid
end function

function CuevanaPage(found as object, p as object) as string
    if p.mediaType = "movie" then return SiteCuevana() + "/ver-pelicula/" + found.name
    return SiteCuevana() + "/episodio/" + found.name + "-temporada-" + AsStr(p.season) + "-episodio-" + AsStr(p.episode)
end function

function PoseidonPage(found as object, p as object) as string
    if p.mediaType = "movie" then return SitePoseidon() + "/pelicula/" + found.path
    return SitePoseidon() + "/serie/" + found.path + "/temporada/" + AsStr(p.season) + "/episodio/" + AsStr(p.episode)
end function

' Servidores de una página de Cuevana/Poseidon, separados por idioma.
sub NextSiteServers(html as string, siteName as string, base as string, out as object)
    pp = NextData(html)
    if pp = invalid then return
    holder = pp.thisMovie
    if holder = invalid then holder = pp.episode
    if holder = invalid then holder = pp.thisEpisode
    if holder = invalid or holder.videos = invalid then return
    langs = { latino: "lat", spanish: "cast", english: "sub" }
    for each key in langs
        list = holder.videos[key]
        if GetInterface(list, "ifArray") <> invalid then
            for each v in list
                url = AsStr(v.result)
                if url <> "" then out.Push({ name: siteName + " · " + AsStr(v.cyberlocker), url: url, referer: base + "/", lang: langs[key] })
            end for
        end if
    end for
end sub

' CineCalidad (sitio latino): búsqueda por título y año; las series listan sus episodios.
function CineCalidadFind(html as string, title as string, year as string, mediaType as string) as string
    kind = "/ver-pelicula/"
    if mediaType = "tv" then kind = "/ver-serie/"
    q = Chr(34)
    reArticle = CreateObject("roRegex", "<article id=" + q + "post-\d+" + q + " class=" + q + "item [^" + q + "]*" + q + "(.*?)</article>", "s")
    reHref = CreateObject("roRegex", "<a href=" + q + "([^" + q + "]+)" + q, "")
    reAlt = CreateObject("roRegex", "alt=" + q + "([^" + q + "]*)" + q, "")
    reYear = CreateObject("roRegex", "<p>(\d{4})</p>", "")
    for each hit in reArticle.MatchAll(html)
        block = hit[1]
        href = FirstGroup(reHref, block)
        name = XmlText(FirstGroup(reAlt, block))
        found = FirstGroup(reYear, block)
        yearOk = year = "" or found = "" or Abs(found.ToInt() - year.ToInt()) <= 1
        if Instr(1, href, kind) > 0 and yearOk and TitleMatches(name, title) then return href
    end for
    return ""
end function

function CineCalidadEpisode(html as string, season as string, episode as string) as string
    q = Chr(34)
    re = CreateObject("roRegex", "numerando" + q + ">S" + season + "-E" + episode + "</div>\s*<div class=" + q + "episodiotitle" + q + ">\s*<a href=" + q + "([^" + q + "]+)" + q, "s")
    return FirstGroup(re, html)
end function

sub CineCalidadServers(html as string, out as object)
    q = Chr(34)
    re = CreateObject("roRegex", "<li[^>]*class=" + q + "dooplay_player_option[^" + q + "]*" + q + "[^>]*data-option=" + q + "([^" + q + "]+)" + q, "")
    for each hit in re.MatchAll(html)
        url = hit[1]
        if Instr(1, url, "youtube.com") = 0 then out.Push({ name: "CineCalidad · " + HostOf(url), url: url, referer: SiteCineCalidad() + "/", lang: "lat" })
    end for
end sub

function HostOf(url as string) as string
    s = url
    i = Instr(1, s, "://")
    if i > 0 then s = Mid(s, i + 3)
    j = Instr(1, s, "/")
    if j > 0 then s = Left(s, j - 1)
    return LCase(s)
end function

function OriginOf(url as string) as string
    i = Instr(1, url, "://")
    if i = 0 then return ""
    return Left(url, i + 2) + HostOf(url)
end function

' p: { mediaType, id (TMDb), season, episode } -> { servers: [{ name, url, referer, lang }] }
' Busca el título (por id de TMDb) en los sitios. Devuelve { found, year }.
function FindOnSites(p as object) as object
    ' Título en español latino y original, y año, para buscar en los sitios.
    info = TmdbGet(p.mediaType + "/" + AsStr(p.id))
    titles = []
    year = ""
    if info <> invalid then
        for each t in [AsStr(info.title), AsStr(info.name), AsStr(info.original_title), AsStr(info.original_name)]
            if t <> "" and not ArrayHas(titles, t) then titles.Push(t)
        end for
        year = Left(AsStr(info.release_date) + AsStr(info.first_air_date), 4)
    end if

    ' Solo los sitios que el panel asignó a la cuenta.
    sites = AssignedSites()
    found = {}
    for each title in titles
        q = UrlEnc(title)
        urls = []
        names = []
        if sites.cuevana = true and found.cuevana = invalid then
            urls.Push(SiteCuevana() + "/search?q=" + q)
            names.Push("cuevana")
        end if
        if sites.poseidon = true and found.poseidon = invalid then
            urls.Push(SitePoseidon() + "/search?q=" + q)
            names.Push("poseidon")
        end if
        if sites.cine = true and found.cine = invalid then
            urls.Push(SiteCineCalidad() + "/?s=" + q.Replace("%20", "+"))
            names.Push("cine")
        end if
        if urls.Count() > 0 then
            res = HttpParallel(urls, invalid, 20000)
            for i = 0 to names.Count() - 1
                body = res[i].body
                if names[i] = "cuevana" then found.cuevana = NextSiteFind(body, AsStr(p.id), p.mediaType)
                if names[i] = "poseidon" then found.poseidon = NextSiteFind(body, AsStr(p.id), p.mediaType)
                if names[i] = "cine" then
                    href = CineCalidadFind(body, title, year, p.mediaType)
                    if href <> "" then found.cine = href
                end if
            end for
        end if
    end for
    return { found: found, year: year }
end function


' IDs del título en cada sitio, con el formato que usa la app Android en la nube (clave =
' nombre del proveedor en Android). Así Roku escribe en los mismos registros que el celular.
function SiteLinks(found as object, mediaType as string) as object
    links = {}
    if found.cuevana <> invalid then
        prefix = "ver-pelicula/"
        if mediaType = "tv" then prefix = "ver-serie/"
        links["Cuevana_3"] = { style: "cuevana", id: prefix + found.cuevana.name, slug: found.cuevana.name }
    end if
    if found.poseidon <> invalid then links["Poseidonhd2"] = { style: "poseidon", id: found.poseidon.path }
    if found.cine <> invalid then
        parts = found.cine.Split("/")
        slug = ""
        for each part in parts
            if part <> "" then slug = part
        end for
        links["CineCalidad"] = { style: "cine", id: found.cine, slug: slug, base: SiteCineCalidad() }
    end if
    return links
end function

function ApiLinks(p as object) as object
    return { links: SiteLinks(FindOnSites(p).found, p.mediaType) }
end function

function ApiServers(p as object) as object
    lang = AudioLanguage()
    servers = []
    search = FindOnSites(p)
    found = search.found
    links = SiteLinks(found, p.mediaType)

    pages = []
    names = []
    if found.cuevana <> invalid then
        pages.Push(CuevanaPage(found.cuevana, p))
        names.Push("cuevana")
    end if
    if found.poseidon <> invalid then
        pages.Push(PoseidonPage(found.poseidon, p))
        names.Push("poseidon")
    end if
    if found.cine <> invalid then
        pages.Push(found.cine)
        names.Push("cine")
    end if
    found2 = []
    res = HttpParallel(pages, invalid, 20000)
    for i = 0 to names.Count() - 1
        html = res[i].body
        if names[i] = "cuevana" then NextSiteServers(html, "Cuevana", SiteCuevana(), found2)
        if names[i] = "poseidon" then NextSiteServers(html, "PoseidonHD", SitePoseidon(), found2)
        if names[i] = "cine" then
            if p.mediaType = "tv" then
                ep = CineCalidadEpisode(html, AsStr(p.season), AsStr(p.episode))
                if ep <> "" then links["CineCalidad"].episodeId = ep
                if ep <> "" then html = Http(ep, { headers: SiteHeaders(SiteCineCalidad()) }).body else html = ""
            end if
            CineCalidadServers(html, found2)
        end if
    end for

    ' Orden por idioma: en español (por defecto) latino, castellano y subtitulado. En inglés:
    ' primero el audio original (subtitulado).
    if lang = "en" then
        order = ["sub", "lat", "cast"]
    else
        order = ["lat", "cast", "sub"]
    end if
    for each tag in order
        for each s in found2
            if s.lang = tag and not Unsupported(s.name + " " + s.url) then servers.Push(s)
        end for
    end for
    ' VixSrc al final, solo si está marcado en el panel: se prueba si ninguno de los sitios reproduce.
    if AssignedSites().DoesExist("vixsrc") then servers.Push({ name: "VixSrc", url: "vixsrc", referer: "", lang: "multi" })
    return { servers: servers, links: links }
end function

function ArrayHas(list as object, value as string) as boolean
    for each v in list
        if v = value then return true
    end for
    return false
end function

' Servidores que en Roku no se pueden abrir (Filemoon pide una firma del navegador; Netu,
' YouTube y similares solo funcionan dentro de un navegador).
function Unsupported(text as string) as boolean
    t = LCase(text)
    for each word in ["filemoon", "byse", "netu", "hqq.", "waaw", "youtube", "streamsb", "1fichier"]
        if Instr(1, t, word) > 0 then return true
    end for
    return false
end function
