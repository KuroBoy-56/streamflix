' Canales en vivo de las listas M3U del panel (PanelM3uProvider / PanelLiveProvider) y guía
' de programación (LiveEpg). Se ejecuta SOLO dentro de ApiTask.
'
' - La línea por defecto del usuario es la base; las demás líneas solo suman los canales que
'   la base no tiene, en la categoría que les corresponde.
' - Un canal que está en varias líneas guarda las otras URLs como respaldo en el reproductor.
' - Se quitan entradas sin URL válida, duplicados y canales para adultos.

function LiveSources() as object
    urls = []
    seen = {}
    ' Líneas del panel, URL personal y proveedores de canales marcados (Pluto TV, IPTV-Org).
    for each line in SplitLines(RegRead("panel_channel_lines") + Chr(10) + RegRead("personal_provider_url") + Chr(10) + RegRead("live_provider_lines"))
        if IsHttpUrl(line) and not seen.DoesExist(line) then
            seen[line] = true
            urls.Push(line)
        end if
    end for
    preferred = RegRead("panel_default_channel_line").Trim()
    if seen.DoesExist(preferred) then
        ordered = [preferred]
        for each u in urls
            if u <> preferred then ordered.Push(u)
        end for
        return ordered
    end if
    return urls
end function

function LiveConst() as object
    if m.liveConst <> invalid then return m.liveConst
    q = Chr(34)
    c = {
        reLogo: CreateObject("roRegex", "tvg-logo=" + q + "([^" + q + "]*)" + q, "i")
        reGroup: CreateObject("roRegex", "group-title=" + q + "([^" + q + "]*)" + q, "i")
        reUa: CreateObject("roRegex", "http-user-agent=" + q + "([^" + q + "]*)" + q, "i")
        reCountry: CreateObject("roRegex", "tvg-country=" + q + "([^" + q + "]*)" + q, "i")
        reId: CreateObject("roRegex", "tvg-id=" + q + "([^" + q + "]*)" + q, "i")
        reEpg: CreateObject("roRegex", "(?:url-tvg|x-tvg-url)=" + q + "([^" + q + "]*)" + q, "i")
        reName: CreateObject("roRegex", ",([^,]*)$", "")
        rePrefix: CreateObject("roRegex", "^\s*[\[(|]?\s*([A-Za-z]{2,3})\s*[\])|:\-]+\s*", "")
        reSuffix: CreateObject("roRegex", "\(([A-Za-z]{2,3})\)\s*$", "")
        reIdCountry: CreateObject("roRegex", "\.([a-z]{2})(@.*)?$", "")
        reAdult: CreateObject("roRegex", "\b(xxx|adult[oa]?s?|porno?|erotic[oa]?s?)\b|18\+|\+18", "i")
        reSpaces: CreateObject("roRegex", "\s+", "")
        reGroupSplit: CreateObject("roRegex", "[;|/:]", "")
        reBrackets: CreateObject("roRegex", "\[.*?\]|\(.*?\)", "")
        reQuality: CreateObject("roRegex", "\b(hd|fhd|uhd|sd|4k|1080p?|720p?|480p?|backup|alt)\b", "")
        reNonAlnum: CreateObject("roRegex", "[^a-z0-9]+", "")
        countries: { mx: "México", ar: "Argentina", co: "Colombia", cl: "Chile", pe: "Perú", ve: "Venezuela", ec: "Ecuador", uy: "Uruguay", py: "Paraguay", bo: "Bolivia", cr: "Costa Rica", pa: "Panamá", gt: "Guatemala", hn: "Honduras", sv: "El Salvador", ni: "Nicaragua", do: "Rep. Dominicana", pr: "Puerto Rico", cu: "Cuba", us: "Estados Unidos", ca: "Canadá", es: "España", pt: "Portugal", br: "Brasil", it: "Italia", fr: "Francia", de: "Alemania", gb: "Reino Unido" }
        aliases: { usa: "us", uk: "gb", mex: "mx", arg: "ar", col: "co", chi: "cl", esp: "es" }
        countryWords: { mexico: "mx", argentina: "ar", colombia: "co", chile: "cl", peru: "pe", venezuela: "ve", ecuador: "ec", uruguay: "uy", paraguay: "py", bolivia: "bo", "costa rica": "cr", panama: "pa", guatemala: "gt", honduras: "hn", "el salvador": "sv", nicaragua: "ni", dominicana: "do", "puerto rico": "pr", cuba: "cu", "estados unidos": "us", usa: "us", canada: "ca", espana: "es", spain: "es", portugal: "pt", brasil: "br", brazil: "br", italia: "it", italy: "it", francia: "fr", france: "fr", alemania: "de", germany: "de", "reino unido": "gb" }
        groupNames: { undefined: "Otros", general: "General", news: "Noticias", sports: "Deportes", movies: "Películas", series: "Series", music: "Música", entertainment: "Entretenimiento", kids: "Infantil", animation: "Animación", documentary: "Documentales", religious: "Religión", education: "Educación", culture: "Cultura", legislative: "Gobierno", lifestyle: "Estilo de vida", comedy: "Comedia", cooking: "Cocina", family: "Familia", travel: "Viajes", outdoor: "Aire libre", business: "Negocios", classic: "Clásicos", science: "Ciencia", weather: "Clima", shop: "Compras", auto: "Motor", relax: "Relax", public: "Públicos", sport: "Deportes", deportes: "Deportes", deporte: "Deportes", futbol: "Deportes", noticias: "Noticias", infantil: "Infantil", infantiles: "Infantil", ninos: "Infantil", cartoons: "Infantil", peliculas: "Películas", cine: "Películas", musica: "Música", documentales: "Documentales", documentaries: "Documentales", entretenimiento: "Entretenimiento" }
        keywords: [
            ["Deportes", [" espn", " fox sports", " tudn", " dazn", " bein", " sky sports", " tyc", " win sports", " gol ", " futbol", " nba", " nfl", " f1 ", " ufc", " wwe", " deport", " sport", " claro sports", " vix "]]
            ["Noticias", [" cnn", " noticias", " news", " milenio", " n+", " euronews", " dw ", " bbc", " rt ", " 24h", " 24 horas"]]
            ["Infantil", [" disney", " nick", " cartoon", " discovery kids", " baby", " kids", " infantil", " boomerang", " junior", " jr "]]
            ["Películas", [" hbo", " cinemax", " tnt", " star ", " cine", " movie", " film", " golden", " space", " paramount", " universal", " warner", " amc", " sony"]]
            ["Música", [" mtv", " music", " musica", " vh1", " telehit", " htv", " radio"]]
            ["Documentales", [" discovery", " history", " nat geo", " national geographic", " animal planet", " a&e", " investigation"]]
        ]
    }
    words = []
    for each w in c.countryWords
        words.Push(w)
    end for
    c.reCountryWords = CreateObject("roRegex", "\b(" + words.Join("|") + ")\b", "")
    m.liveConst = c
    return c
end function

function FirstGroup(re as object, text as string) as string
    found = re.Match(text)
    if found.Count() > 1 then return found[1]
    return ""
end function

function CountryAlias(code as string) as string
    c = LCase(code)
    a = LiveConst().aliases[c]
    if a <> invalid then return a
    return c
end function

function ParseM3u(raw as string, epgOut as object) as object
    k = LiveConst()
    out = []
    name = "" : logo = "" : group = "" : ua = "" : referer = "" : tvgCountry = "" : tvgId = ""
    for each line in raw.Split(Chr(10))
        t = line.Trim()
        if StartsWithI(t, "#EXTM3U") then
            for each src in FirstGroup(k.reEpg, t).Split(",")
                if IsHttpUrl(src.Trim()) then epgOut.Push(src.Trim())
            end for
        else if StartsWithI(t, "#EXTINF") then
            name = FirstGroup(k.reName, t).Trim()
            if Instr(1, t, ",") = 0 then name = t
            logo = FirstGroup(k.reLogo, t)
            group = FirstGroup(k.reGroup, t)
            ua = FirstGroup(k.reUa, t)
            referer = ""
            tvgCountry = FirstGroup(k.reCountry, t)
            tvgId = FirstGroup(k.reId, t)
        else if StartsWithI(t, "#EXTGRP:") then
            if group.Trim() = "" then group = Mid(t, 9).Trim()
        else if StartsWithI(t, "#EXTVLCOPT:http-user-agent=") then
            ua = SubAfter(t, "=").Trim()
        else if StartsWithI(t, "#EXTVLCOPT:http-referrer=") then
            referer = SubAfter(t, "=").Trim()
        else if StartsWithI(t, "http") then
            if name.Trim() <> "" then out.Push(WithCountry({ name: name, url: t, logo: logo, group: group, ua: ua, referer: referer, country: "", epgId: tvgId.Trim() }, tvgCountry, tvgId))
            name = "" : logo = "" : group = "" : ua = "" : referer = "" : tvgCountry = "" : tvgId = ""
        end if
    end for
    return out
end function

' Formato simple "Nombre,URL" o solo URL por línea.
function ParsePlain(raw as string) as object
    out = []
    re = CreateObject("roRegex", "https?://\S+", "")
    for each line in raw.Split(Chr(10))
        t = line.Trim()
        found = re.Match(t)
        if found.Count() > 0 then
            url = found[0]
            title = Left(t, Instr(1, t, url) - 1).Trim()
            while Len(title) > 0 and Instr(1, ",|;", Right(title, 1)) > 0
                title = Left(title, Len(title) - 1).Trim()
            end while
            if title = "" then
                title = url
                if Instr(1, title, "?") > 0 then title = Left(title, Instr(1, title, "?") - 1)
                parts = title.Split("/")
                title = parts[parts.Count() - 1]
                if Instr(1, title, ".") > 0 then title = Left(title, Instr(1, title, ".") - 1)
            end if
            out.Push(WithCountry({ name: title, url: url, logo: "", group: "", ua: "", referer: "", country: "", epgId: "" }, "", ""))
        end if
    end for
    return out
end function

' País del canal y nombre sin el prefijo de país ("MX: Canal 5" -> "Canal 5", México).
function WithCountry(ch as object, tvgCountry as string, tvgId as string) as object
    k = LiveConst()
    code = ""
    for each part in CreateObject("roRegex", "[,; ]", "").Split(tvgCountry)
        p = LCase(part.Trim())
        if code = "" and k.countries.DoesExist(p) then code = p
    end for
    if code = "" then
        c = FirstGroup(k.reIdCountry, LCase(tvgId))
        if k.countries.DoesExist(c) then code = c
    end if
    found = k.rePrefix.Match(ch.name)
    if found.Count() > 1 then
        c = CountryAlias(found[1])
        if k.countries.DoesExist(c) then
            if code = "" then code = c
            rest = Mid(ch.name, Len(found[0]) + 1).Trim()
            if rest <> "" then ch.name = rest
        end if
    end if
    if code = "" then
        c = CountryAlias(FirstGroup(k.reSuffix, ch.name))
        if k.countries.DoesExist(c) then code = c
    end if
    if code = "" then
        word = FirstGroup(k.reCountryWords, Normalize(ch.group))
        if word <> "" then code = k.countryWords[word]
        if code = "" then
            c = CountryAlias(FirstGroup(k.rePrefix, ch.group))
            if k.countries.DoesExist(c) then code = c
        end if
    end if
    if code <> "" then ch.country = k.countries[code]
    return ch
end function

' "Animation;Kids" -> "Animación". Vacío, solo país o "Undefined": se deduce del nombre (ESPN -> Deportes).
function CleanGroup(raw as string, channelName as string) as string
    k = LiveConst()
    for each part in k.reGroupSplit.Split(raw)
        p = part.Trim()
        n = Normalize(p)
        if p <> "" and not k.countryWords.DoesExist(n) and not k.countries.DoesExist(CountryAlias(n)) then
            named = k.groupNames[n]
            if named = invalid then named = UCase(Left(p, 1)) + Mid(p, 2)
            if named <> "Otros" then return named
            exit for
        end if
    end for
    n = " " + Normalize(channelName) + " "
    for each entry in k.keywords
        for each w in entry[1]
            if Instr(1, n, w) > 0 then return entry[0]
        end for
    end for
    return "Otros"
end function

function CleanChannels(list as object) as object
    k = LiveConst()
    out = []
    seen = {}
    for each ch in list
        if StartsWithI(ch.url, "http") and ch.name.Trim() <> "" and not seen.DoesExist(ch.url) then
            if not k.reAdult.IsMatch(Normalize(ch.name)) and not k.reAdult.IsMatch(Normalize(ch.group)) then
                seen[ch.url] = true
                ch.name = k.reSpaces.ReplaceAll(ch.name, " ").Trim()
                ch.group = CleanGroup(ch.group, ch.name)
                out.Push(ch)
            end if
        end if
    end for
    return out
end function

' "ESPN HD", "ESPN (720p)", "espn" -> "espn".
function ChannelKey(name as string) as string
    k = LiveConst()
    s = k.reBrackets.ReplaceAll(Normalize(name), " ")
    s = k.reQuality.ReplaceAll(s, " ")
    return k.reNonAlnum.ReplaceAll(s, "")
end function

' Primera palabra: "ESPN 2 HD" -> "espn".
function BrandOf(name as string) as string
    for each part in LiveConst().reNonAlnum.Split(Normalize(name))
        if part <> "" then return part
    end for
    return ""
end function

function MergeLines(lines as object) as object
    result = []
    seen = {}
    if lines.Count() > 0 then
        for each ch in lines[0]
            key = ChannelKey(ch.name)
            if key <> "" and not seen.DoesExist(key) then
                seen[key] = true
                result.Push(ch)
            end if
        end for
    end if

    ' Marca -> categoría más usada en la base, si tiene 2+ canales ("espn" -> "Deportes").
    brandCounts = {}
    baseGroups = {}
    for each ch in result
        b = BrandOf(ch.name)
        baseGroups[LCase(ch.group)] = ch.group
        if Len(b) >= 2 then
            if brandCounts[b] = invalid then brandCounts[b] = {}
            brandCounts[b][ch.group] = ToInt(brandCounts[b][ch.group]) + 1
        end if
    end for
    brandGroups = {}
    for each b in brandCounts
        best = ""
        bestCount = 0
        for each g in brandCounts[b]
            if brandCounts[b][g] > bestCount then
                best = g
                bestCount = brandCounts[b][g]
            end if
        end for
        if bestCount >= 2 then brandGroups[b] = best
    end for

    for i = 1 to lines.Count() - 1
        for each ch in lines[i]
            key = ChannelKey(ch.name)
            if key <> "" and not seen.DoesExist(key) then
                seen[key] = true
                g = brandGroups[BrandOf(ch.name)]
                if g = invalid then g = baseGroups[LCase(ch.group)]
                if g <> invalid then ch.group = g
                result.Push(ch)
            end if
        end for
    end for

    ' Grupos con menos de 3 canales van a "Otros" para no llenar de carpetas.
    counts = {}
    for each ch in result
        counts[ch.group] = ToInt(counts[ch.group]) + 1
    end for
    for each ch in result
        if counts[ch.group] < 3 then ch.group = "Otros"
    end for
    return result
end function

function LiveCacheFile() as string
    return "cachefs:/live_cache.json"
end function

' Canales con caché en el equipo: se usa la copia si tiene menos de 30 min; si la descarga
' falla se conserva la copia anterior (no se borra la lista).
function LoadChannels(force as boolean) as object
    sources = LiveSources()
    key = sources.Join("|")
    if key = "" then return { channels: [], alternates: {}, epg: [] }
    cache = JsonOf(ReadAsciiFile(LiveCacheFile()))
    sameKey = cache <> invalid and cache.key = key and GetInterface(cache.channels, "ifArray") <> invalid
    if sameKey and not force then
        cache.stale = NowSec() - ToInt(cache.time) >= 1800
        return cache
    end if

    epg = []
    lines = []
    all = []
    for each r in HttpParallel(sources, { "User-Agent": "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36" }, 45000)
        list = []
        if r.code = 200 then
            if Instr(1, r.body, "#EXTINF") > 0 then list = CleanChannels(ParseM3u(r.body, epg)) else list = CleanChannels(ParsePlain(r.body))
        end if
        lines.Push(list)
        all.Append(list)
    end for
    merged = MergeLines(lines)
    if merged.Count() = 0 and sameKey then return cache

    ' Mismo canal en otras líneas (URLs distintas): respaldo si una no funciona.
    byKey = {}
    for each ch in all
        k = ChannelKey(ch.name)
        if k <> "" then
            if byKey[k] = invalid then byKey[k] = []
            byKey[k].Push({ url: ch.url, ua: ch.ua, referer: ch.referer })
        end if
    end for
    alternates = {}
    for each k in byKey
        if byKey[k].Count() > 1 then alternates[k] = byKey[k]
    end for

    result = { key: key, time: NowSec(), channels: merged, alternates: alternates, epg: epg }
    if merged.Count() > 0 then WriteAsciiFile(LiveCacheFile(), FormatJson(result))
    return result
end function

function ChannelHeaders(ua as dynamic, referer as dynamic) as object
    headers = []
    if AsStr(ua) <> "" then headers.Push("User-Agent: " + AsStr(ua))
    if AsStr(referer) <> "" then headers.Push("Referer: " + AsStr(referer))
    return headers
end function

function ChannelNode(ch as object, alternates as object) as object
    node = CreateObject("roSGNode", "ContentNode")
    node.AddFields({ itemId: ch.url, mediaType: "channel", group: ch.group, country: ch.country, epgId: ch.epgId, sources: [] })
    node.title = ch.name
    node.HDPosterUrl = ch.logo
    node.url = ch.url
    ' Fuentes en orden: la de la línea elegida y luego el mismo canal en otras líneas.
    sources = [{ url: ch.url, headers: ChannelHeaders(ch.ua, ch.referer) }]
    alts = alternates[ChannelKey(ch.name)]
    if alts <> invalid then
        for each a in alts
            if a.url <> ch.url then sources.Push({ url: a.url, headers: ChannelHeaders(a.ua, a.referer) })
        end for
    end if
    node.sources = sources
    return node
end function

function FolderNode(title as string, folderType as string, channels as object, alternates as object) as object
    folder = CreateObject("roSGNode", "ContentNode")
    folder.AddFields({ folderType: folderType, channelCount: channels.Count() })
    folder.title = title
    for each ch in channels
        folder.appendChild(ChannelNode(ch, alternates))
    end for
    return folder
end function

' Carpetas de "En Vivo": por categoría (Otros al final) y por país (de más a menos canales).
function ApiLive(p as object) as object
    data = LoadChannels(p.force = true)
    channels = data.channels
    if channels.Count() = 0 then return { error: "No hay canales disponibles en tu línea." }
    byGroup = {}
    byCountry = {}
    for each ch in channels
        if byGroup[ch.group] = invalid then byGroup[ch.group] = []
        byGroup[ch.group].Push(ch)
        if ch.country <> "" then
            if byCountry[ch.country] = invalid then byCountry[ch.country] = []
            byCountry[ch.country].Push(ch)
        end if
    end for
    groups = []
    for each g in byGroup
        sortKey = 100000 - byGroup[g].Count()
        if g = "Otros" then sortKey = sortKey + 1000000
        groups.Push({ name: g, sortKey: sortKey })
    end for
    groups.SortBy("sortKey")
    countries = []
    for each c in byCountry
        countries.Push({ name: c, sortKey: 100000 - byCountry[c].Count() })
    end for
    countries.SortBy("sortKey")

    root = CreateObject("roSGNode", "ContentNode")
    root.appendChild(FolderNode("Todos", "all", channels, data.alternates))
    for each g in groups
        root.appendChild(FolderNode(g.name, "category", byGroup[g.name], data.alternates))
    end for
    for each c in countries
        root.appendChild(FolderNode(c.name, "country", byCountry[c.name], data.alternates))
    end for

    ids = []
    for each ch in channels
        if ch.epgId <> "" then ids.Push(ch.epgId)
    end for
    return { content: root, epgSources: data.epg, epgIds: ids, total: channels.Count(), stale: data.stale = true }
end function

' =======================================================================================
' Guía de programación XMLTV (LiveEpg): solo lo que está en el aire y lo próximo.
' =======================================================================================

function EpgTime(value as string) as integer
    v = value.Trim()
    if Len(v) < 14 then return 0
    dt = CreateObject("roDateTime")
    dt.FromISO8601String(Mid(v, 1, 4) + "-" + Mid(v, 5, 2) + "-" + Mid(v, 7, 2) + "T" + Mid(v, 9, 2) + ":" + Mid(v, 11, 2) + ":" + Mid(v, 13, 2) + "Z")
    secs = dt.AsSeconds()
    tz = Mid(v, 15).Trim()
    if Len(tz) = 5 then
        offset = Mid(tz, 2, 2).ToInt() * 3600 + Mid(tz, 4, 2).ToInt() * 60
        if Left(tz, 1) = "+" then secs = secs - offset else secs = secs + offset
    end if
    return secs
end function

function ApiEpg(p as object) as object
    wanted = {}
    if p.ids <> invalid then
        for each id in p.ids
            wanted[id] = true
        end for
    end if
    programmes = {}
    if wanted.Count() = 0 or p.sources = invalid then return { programmes: programmes }
    q = Chr(34)
    reChannel = CreateObject("roRegex", "channel=" + q + "([^" + q + "]*)" + q, "")
    reStart = CreateObject("roRegex", "start=" + q + "([^" + q + "]*)" + q, "")
    reStop = CreateObject("roRegex", "stop=" + q + "([^" + q + "]*)" + q, "")
    reTitle = CreateObject("roRegex", "<title[^>]*>(.*?)</title>", "s")
    reDesc = CreateObject("roRegex", "<desc[^>]*>(.*?)</desc>", "s")
    now = NowSec()
    fs = CreateObject("roFileSystem")
    path = "tmp:/epg.xml"
    used = 0
    for each src in p.sources
        ' shortcut: las guías .gz no se leen (Roku no descomprime archivos); usar una guía .xml en la lista.
        if used < 3 and LCase(Right(src, 3)) <> ".gz" then
            used = used + 1
            if fs.Exists(path) then fs.Delete(path)
            port = CreateObject("roMessagePort")
            xfer = NewTransfer(src, invalid)
            xfer.SetMessagePort(port)
            if xfer.AsyncGetToFile(path) then
                msg = wait(90000, port)
                if type(msg) <> "roUrlEvent" then xfer.AsyncCancel()
            end if
            info = invalid
            if fs.Exists(path) then info = fs.Stat(path)
            ' Guías enormes no caben en la memoria de Roku.
            if info <> invalid and info.size > 0 and info.size < 40000000 then
                text = ReadAsciiFile(path)
                scanAt = Instr(1, text, "<programme")
                while scanAt > 0
                    stopPos = Instr(scanAt, text, "</programme>")
                    if stopPos = 0 then exit while
                    chunk = Mid(text, scanAt, stopPos - scanAt)
                    channel = FirstGroup(reChannel, Left(chunk, 300))
                    if wanted.DoesExist(channel) then
                        startSec = EpgTime(FirstGroup(reStart, Left(chunk, 300)))
                        stopSec = EpgTime(FirstGroup(reStop, Left(chunk, 300)))
                        if stopSec > now and startSec < now + 8 * 3600 then
                            if programmes[channel] = invalid then programmes[channel] = []
                            if programmes[channel].Count() < 6 then programmes[channel].Push({ title: XmlText(FirstGroup(reTitle, chunk)), desc: XmlText(FirstGroup(reDesc, chunk)), start: startSec, stop: stopSec })
                        end if
                    end if
                    scanAt = Instr(stopPos, text, "<programme")
                end while
                text = ""
            end if
            if fs.Exists(path) then fs.Delete(path)
        end if
    end for
    for each id in programmes
        programmes[id].SortBy("start")
    end for
    return { programmes: programmes }
end function

function XmlText(s as string) as string
    return s.Replace("&amp;", "&").Replace("&lt;", "<").Replace("&gt;", ">").Replace("&quot;", Chr(34)).Replace("&apos;", "'").Trim()
end function
