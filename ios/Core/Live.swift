// Canales en vivo de las listas M3U del panel (PanelM3uProvider / PanelLiveProvider) y guía
// de programación (LiveEpg). Port de Live.brs del canal de Roku.
//
// - La línea por defecto del usuario es la base; las demás líneas solo suman los canales que
//   la base no tiene, en la categoría que les corresponde.
// - Un canal que está en varias líneas guarda las otras URLs como respaldo en el reproductor.
// - Se quitan entradas sin URL válida, duplicados y canales para adultos.
import Foundation

/// Canal tal como sale de una lista (antes de armar las carpetas). Se guarda en la caché.
struct LiveCh: Codable, Equatable {
    var name: String
    var url: String
    var logo: String = ""
    var group: String = ""
    var ua: String = ""
    var referer: String = ""
    var country: String = ""
    var epgId: String = ""
}

struct LiveAlt: Codable, Equatable {
    var url: String
    var ua: String
    var referer: String
}

struct LiveCache: Codable {
    var key: String
    var time: Int
    var channels: [LiveCh]
    var alternates: [String: [LiveAlt]]
    var epg: [String]
    var stale: Bool? = nil
}

public enum Live {
    /// "En Vivo" solo existe si hay alguna lista http(s) del panel o personal (Android:
    /// PanelM3uProvider.isConfigured). Se calcula al momento: si el panel quita las líneas, se oculta.
    public static var hasLive: Bool { !sources().isEmpty }

    static let cacheFile = "live_cache.json"
    static let lineUA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    // MARK: - Fuentes

    static func sources() -> [String] {
        var urls: [String] = []
        var seen = Set<String>()
        for line in splitLines(Reg.read("panel_channel_lines") + "\n" + Reg.read("personal_provider_url")) {
            if isHttpURL(line), !seen.contains(line) { seen.insert(line); urls.append(line) }
        }
        let preferred = Reg.read("panel_default_channel_line").trimmingCharacters(in: .whitespacesAndNewlines)
        if seen.contains(preferred) { return [preferred] + urls.filter { $0 != preferred } }
        return urls
    }

    // MARK: - Constantes

    static let countries: [String: String] = ["mx": "México", "ar": "Argentina", "co": "Colombia", "cl": "Chile", "pe": "Perú", "ve": "Venezuela", "ec": "Ecuador", "uy": "Uruguay", "py": "Paraguay", "bo": "Bolivia", "cr": "Costa Rica", "pa": "Panamá", "gt": "Guatemala", "hn": "Honduras", "sv": "El Salvador", "ni": "Nicaragua", "do": "Rep. Dominicana", "pr": "Puerto Rico", "cu": "Cuba", "us": "Estados Unidos", "ca": "Canadá", "es": "España", "pt": "Portugal", "br": "Brasil", "it": "Italia", "fr": "Francia", "de": "Alemania", "gb": "Reino Unido"]
    static let aliases: [String: String] = ["usa": "us", "uk": "gb", "mex": "mx", "arg": "ar", "col": "co", "chi": "cl", "esp": "es"]
    static let countryWords: [String: String] = ["mexico": "mx", "argentina": "ar", "colombia": "co", "chile": "cl", "peru": "pe", "venezuela": "ve", "ecuador": "ec", "uruguay": "uy", "paraguay": "py", "bolivia": "bo", "costa rica": "cr", "panama": "pa", "guatemala": "gt", "honduras": "hn", "el salvador": "sv", "nicaragua": "ni", "dominicana": "do", "puerto rico": "pr", "cuba": "cu", "estados unidos": "us", "usa": "us", "canada": "ca", "espana": "es", "spain": "es", "portugal": "pt", "brasil": "br", "brazil": "br", "italia": "it", "italy": "it", "francia": "fr", "france": "fr", "alemania": "de", "germany": "de", "reino unido": "gb"]
    static let groupNames: [String: String] = ["undefined": "Otros", "general": "General", "news": "Noticias", "sports": "Deportes", "movies": "Películas", "series": "Series", "music": "Música", "entertainment": "Entretenimiento", "kids": "Infantil", "animation": "Animación", "documentary": "Documentales", "religious": "Religión", "education": "Educación", "culture": "Cultura", "legislative": "Gobierno", "lifestyle": "Estilo de vida", "comedy": "Comedia", "cooking": "Cocina", "family": "Familia", "travel": "Viajes", "outdoor": "Aire libre", "business": "Negocios", "classic": "Clásicos", "science": "Ciencia", "weather": "Clima", "shop": "Compras", "auto": "Motor", "relax": "Relax", "public": "Públicos", "sport": "Deportes", "deportes": "Deportes", "deporte": "Deportes", "futbol": "Deportes", "noticias": "Noticias", "infantil": "Infantil", "infantiles": "Infantil", "ninos": "Infantil", "cartoons": "Infantil", "peliculas": "Películas", "cine": "Películas", "musica": "Música", "documentales": "Documentales", "documentaries": "Documentales", "entretenimiento": "Entretenimiento"]
    static let keywords: [(String, [String])] = [
        ("Deportes", [" espn", " fox sports", " tudn", " dazn", " bein", " sky sports", " tyc", " win sports", " gol ", " futbol", " nba", " nfl", " f1 ", " ufc", " wwe", " deport", " sport", " claro sports", " vix "]),
        ("Noticias", [" cnn", " noticias", " news", " milenio", " n+", " euronews", " dw ", " bbc", " rt ", " 24h", " 24 horas"]),
        ("Infantil", [" disney", " nick", " cartoon", " discovery kids", " baby", " kids", " infantil", " boomerang", " junior", " jr "]),
        ("Películas", [" hbo", " cinemax", " tnt", " star ", " cine", " movie", " film", " golden", " space", " paramount", " universal", " warner", " amc", " sony"]),
        ("Música", [" mtv", " music", " musica", " vh1", " telehit", " htv", " radio"]),
        ("Documentales", [" discovery", " history", " nat geo", " national geographic", " animal planet", " a&e", " investigation"]),
    ]

    static func attr(_ name: String) -> String { name + "=\"([^\"]*)\"" }
    static let rePrefix = "^\\s*[\\[(|]?\\s*([A-Za-z]{2,3})\\s*[\\])|:\\-]+\\s*"
    static let reAdult = try! NSRegularExpression(pattern: "\\b(xxx|adult[oa]?s?|porno?|erotic[oa]?s?)\\b|18\\+|\\+18", options: .caseInsensitive)
    static let reCountryWords = "\\b(" + countryWords.keys.sorted().joined(separator: "|") + ")\\b"

    static func countryAlias(_ code: String) -> String {
        let c = code.lowercased()
        return aliases[c] ?? c
    }

    static func isAdult(_ text: String) -> Bool {
        reAdult.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
    }

    // MARK: - Listas

    static func parseM3u(_ raw: String, epg: inout [String]) -> [LiveCh] {
        var out: [LiveCh] = []
        var name = "", logo = "", group = "", ua = "", referer = "", tvgCountry = "", tvgId = ""
        for line in raw.components(separatedBy: "\n") {
            let t = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if startsWithI(t, "#EXTM3U") {
                for src in firstGroup("(?:url-tvg|x-tvg-url)=\"([^\"]*)\"", t, options: .caseInsensitive).components(separatedBy: ",") {
                    let s = src.trimmingCharacters(in: .whitespaces)
                    if isHttpURL(s) { epg.append(s) }
                }
            } else if startsWithI(t, "#EXTINF") {
                name = t.contains(",") ? firstGroup(",([^,]*)$", t).trimmingCharacters(in: .whitespaces) : t
                logo = firstGroup(attr("tvg-logo"), t, options: .caseInsensitive)
                group = firstGroup(attr("group-title"), t, options: .caseInsensitive)
                ua = firstGroup(attr("http-user-agent"), t, options: .caseInsensitive)
                referer = ""
                tvgCountry = firstGroup(attr("tvg-country"), t, options: .caseInsensitive)
                tvgId = firstGroup(attr("tvg-id"), t, options: .caseInsensitive)
            } else if startsWithI(t, "#EXTGRP:") {
                if group.trimmingCharacters(in: .whitespaces).isEmpty { group = String(t.dropFirst(8)).trimmingCharacters(in: .whitespaces) }
            } else if startsWithI(t, "#EXTVLCOPT:http-user-agent=") {
                ua = subAfter(t, "=").trimmingCharacters(in: .whitespaces)
            } else if startsWithI(t, "#EXTVLCOPT:http-referrer=") {
                referer = subAfter(t, "=").trimmingCharacters(in: .whitespaces)
            } else if startsWithI(t, "http") {
                if !name.trimmingCharacters(in: .whitespaces).isEmpty {
                    let ch = LiveCh(name: name, url: t, logo: logo, group: group, ua: ua, referer: referer,
                                    epgId: tvgId.trimmingCharacters(in: .whitespaces))
                    out.append(withCountry(ch, tvgCountry: tvgCountry, tvgId: tvgId))
                }
                name = ""; logo = ""; group = ""; ua = ""; referer = ""; tvgCountry = ""; tvgId = ""
            }
        }
        return out
    }

    /// Formato simple "Nombre,URL" o solo URL por línea.
    static func parsePlain(_ raw: String) -> [LiveCh] {
        var out: [LiveCh] = []
        for line in raw.components(separatedBy: "\n") {
            let t = line.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let r = t.range(of: "https?://\\S+", options: .regularExpression) else { continue }
            let url = String(t[r])
            var title = String(t[..<r.lowerBound]).trimmingCharacters(in: .whitespaces)
            while let last = title.last, ",|;".contains(last) {
                title = String(title.dropLast()).trimmingCharacters(in: .whitespaces)
            }
            if title.isEmpty {
                title = url.components(separatedBy: "?")[0]
                title = title.components(separatedBy: "/").last ?? title
                title = title.components(separatedBy: ".")[0]
            }
            out.append(withCountry(LiveCh(name: title, url: url), tvgCountry: "", tvgId: ""))
        }
        return out
    }

    /// País del canal y nombre sin el prefijo de país ("MX: Canal 5" -> "Canal 5", México).
    static func withCountry(_ input: LiveCh, tvgCountry: String, tvgId: String) -> LiveCh {
        var ch = input
        var code = ""
        for part in tvgCountry.components(separatedBy: CharacterSet(charactersIn: ",; ")) {
            let p = part.trimmingCharacters(in: .whitespaces).lowercased()
            if code.isEmpty, countries[p] != nil { code = p }
        }
        if code.isEmpty {
            let c = firstGroup("\\.([a-z]{2})(@.*)?$", tvgId.lowercased())
            if countries[c] != nil { code = c }
        }
        if let found = allMatches(rePrefix, ch.name).first {
            let c = countryAlias(found[1])
            if countries[c] != nil {
                if code.isEmpty { code = c }
                let rest = String(ch.name.dropFirst(found[0].count)).trimmingCharacters(in: .whitespaces)
                if !rest.isEmpty { ch.name = rest }
            }
        }
        if code.isEmpty {
            let c = countryAlias(firstGroup("\\(([A-Za-z]{2,3})\\)\\s*$", ch.name))
            if countries[c] != nil { code = c }
        }
        if code.isEmpty {
            let word = firstGroup(reCountryWords, normalize(ch.group))
            if !word.isEmpty { code = countryWords[word] ?? "" }
            if code.isEmpty {
                let c = countryAlias(firstGroup(rePrefix, ch.group))
                if countries[c] != nil { code = c }
            }
        }
        if let country = countries[code] { ch.country = country }
        return ch
    }

    /// "Animation;Kids" -> "Animación". Vacío, solo país o "Undefined": se deduce del nombre (ESPN -> Deportes).
    static func cleanGroup(_ raw: String, channelName: String) -> String {
        for part in raw.components(separatedBy: CharacterSet(charactersIn: ";|/:")) {
            let p = part.trimmingCharacters(in: .whitespaces)
            let n = normalize(p)
            if !p.isEmpty, countryWords[n] == nil, countries[countryAlias(n)] == nil {
                let named = groupNames[n] ?? (p.prefix(1).uppercased() + p.dropFirst())
                if named != "Otros" { return named }
                break
            }
        }
        let n = " " + normalize(channelName) + " "
        for (group, words) in keywords where words.contains(where: { n.contains($0) }) { return group }
        return "Otros"
    }

    static func cleanChannels(_ list: [LiveCh]) -> [LiveCh] {
        var out: [LiveCh] = []
        var seen = Set<String>()
        for var ch in list where startsWithI(ch.url, "http") && !ch.name.trimmingCharacters(in: .whitespaces).isEmpty && !seen.contains(ch.url) {
            if isAdult(normalize(ch.name)) || isAdult(normalize(ch.group)) { continue }
            seen.insert(ch.url)
            ch.name = replaceAll("\\s+", ch.name, with: " ").trimmingCharacters(in: .whitespaces)
            ch.group = cleanGroup(ch.group, channelName: ch.name)
            out.append(ch)
        }
        return out
    }

    /// "ESPN HD", "ESPN (720p)", "espn" -> "espn".
    static func channelKey(_ name: String) -> String {
        var s = replaceAll("\\[.*?\\]|\\(.*?\\)", normalize(name), with: " ")
        s = replaceAll("\\b(hd|fhd|uhd|sd|4k|1080p?|720p?|480p?|backup|alt)\\b", s, with: " ")
        return replaceAll("[^a-z0-9]+", s, with: "")
    }

    /// Primera palabra: "ESPN 2 HD" -> "espn".
    static func brandOf(_ name: String) -> String {
        normalize(name).split(whereSeparator: { !(("a"..."z").contains($0) || ("0"..."9").contains($0)) }).first.map(String.init) ?? ""
    }

    static func mergeLines(_ lines: [[LiveCh]]) -> [LiveCh] {
        var result: [LiveCh] = []
        var seen = Set<String>()
        for ch in lines.first ?? [] {
            let key = channelKey(ch.name)
            if !key.isEmpty, !seen.contains(key) { seen.insert(key); result.append(ch) }
        }

        // Marca -> categoría más usada en la base, si tiene 2+ canales ("espn" -> "Deportes").
        var brandCounts: [String: [(group: String, count: Int)]] = [:]
        var baseGroups: [String: String] = [:]
        for ch in result {
            let b = brandOf(ch.name)
            baseGroups[ch.group.lowercased()] = ch.group
            if b.count >= 2 {
                var counts = brandCounts[b] ?? []
                if let i = counts.firstIndex(where: { $0.group == ch.group }) { counts[i].count += 1 } else { counts.append((ch.group, 1)) }
                brandCounts[b] = counts
            }
        }
        var brandGroups: [String: String] = [:]
        for (b, counts) in brandCounts {
            var best = "", bestCount = 0
            for c in counts where c.count > bestCount { best = c.group; bestCount = c.count }
            if bestCount >= 2 { brandGroups[b] = best }
        }

        for line in lines.dropFirst() {
            for var ch in line {
                let key = channelKey(ch.name)
                if !key.isEmpty, !seen.contains(key) {
                    seen.insert(key)
                    if let g = brandGroups[brandOf(ch.name)] ?? baseGroups[ch.group.lowercased()] { ch.group = g }
                    result.append(ch)
                }
            }
        }

        // Grupos con menos de 3 canales van a "Otros" para no llenar de carpetas.
        var counts: [String: Int] = [:]
        for ch in result { counts[ch.group, default: 0] += 1 }
        for i in result.indices where counts[result[i].group, default: 0] < 3 { result[i].group = "Otros" }
        return result
    }

    // MARK: - Caché y descarga

    static func readCache() -> LiveCache? {
        guard let data = Paths.readText(cacheFile).data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(LiveCache.self, from: data)
    }

    /// Canales con caché en el equipo: se usa la copia (stale si tiene 30 min o más); si la
    /// descarga falla se conserva la copia anterior (no se borra la lista).
    static func loadChannels(force: Bool) async -> LiveCache {
        let srcs = sources()
        let key = srcs.joined(separator: "|")
        let empty = LiveCache(key: "", time: 0, channels: [], alternates: [:], epg: [])
        if key.isEmpty { return empty }
        var cache = readCache()
        let sameKey = cache?.key == key
        if sameKey, !force, var c = cache {
            c.stale = nowSec() - c.time >= 1800
            return c
        }
        if !sameKey { cache = nil }

        var epg: [String] = []
        var lines: [[LiveCh]] = []
        for r in await httpParallel(srcs, headers: ["User-Agent": lineUA], timeout: 45) {
            var list: [LiveCh] = []
            if r.code == 200 {
                list = r.body.contains("#EXTINF") ? cleanChannels(parseM3u(r.body, epg: &epg)) : cleanChannels(parsePlain(r.body))
            }
            lines.append(list)
        }
        let merged = mergeLines(lines)
        if merged.isEmpty, let cache { return cache }

        // Mismo canal en otras líneas (URLs distintas): respaldo si una no funciona.
        var byKey: [String: [LiveAlt]] = [:]
        for ch in lines.joined() {
            let k = channelKey(ch.name)
            if !k.isEmpty { byKey[k, default: []].append(LiveAlt(url: ch.url, ua: ch.ua, referer: ch.referer)) }
        }
        let result = LiveCache(key: key, time: nowSec(), channels: merged, alternates: byKey.filter { $0.value.count > 1 }, epg: epg)
        if !merged.isEmpty, let data = try? JSONEncoder().encode(result) {
            Paths.writeText(cacheFile, String(decoding: data, as: UTF8.self))
        }
        return result
    }

    static func headers(ua: String, referer: String) -> [String: String] {
        var h: [String: String] = [:]
        if !ua.isEmpty { h["User-Agent"] = ua }
        if !referer.isEmpty { h["Referer"] = referer }
        return h
    }

    static func channel(_ ch: LiveCh, alternates: [String: [LiveAlt]]) -> Channel {
        // Fuentes en orden: la de la línea elegida y luego el mismo canal en otras líneas.
        var srcs = [StreamSource(url: ch.url, headers: headers(ua: ch.ua, referer: ch.referer))]
        for a in alternates[channelKey(ch.name)] ?? [] where a.url != ch.url {
            srcs.append(StreamSource(url: a.url, headers: headers(ua: a.ua, referer: a.referer)))
        }
        return Channel(id: ch.url, name: ch.name, logo: ch.logo, group: ch.group, country: ch.country, epgId: ch.epgId, sources: srcs)
    }

    /// Carpetas: "Todos", por categoría (Otros al final) y por país (de más a menos canales).
    static func folders(_ data: LiveCache) -> [LiveFolder] {
        let channels = data.channels.map { channel($0, alternates: data.alternates) }
        var groupOrder: [String] = [], countryOrder: [String] = []
        var byGroup: [String: [Channel]] = [:], byCountry: [String: [Channel]] = [:]
        for ch in channels {
            if byGroup[ch.group] == nil { groupOrder.append(ch.group) }
            byGroup[ch.group, default: []].append(ch)
            if !ch.country.isEmpty {
                if byCountry[ch.country] == nil { countryOrder.append(ch.country) }
                byCountry[ch.country, default: []].append(ch)
            }
        }
        func sorted(_ names: [String], _ sortKey: (String) -> Int) -> [String] {
            names.enumerated().sorted { (sortKey($0.element), $0.offset) < (sortKey($1.element), $1.offset) }.map(\.element)
        }
        let groups = sorted(groupOrder) { 100_000 - byGroup[$0]!.count + ($0 == "Otros" ? 1_000_000 : 0) }
        let countries = sorted(countryOrder) { 100_000 - byCountry[$0]!.count }
        return [LiveFolder(title: "Todos", folderType: "all", channels: channels)]
            + groups.map { LiveFolder(title: $0, folderType: "category", channels: byGroup[$0]!) }
            + countries.map { LiveFolder(title: $0, folderType: "country", channels: byCountry[$0]!) }
    }

    public static func load(force: Bool) async -> Result<LiveResult, CoreError> {
        let data = await loadChannels(force: force)
        if data.channels.isEmpty { return .failure(CoreError("No hay canales disponibles en tu línea.")) }
        return .success(LiveResult(folders: folders(data), epgSources: data.epg, total: data.channels.count, stale: data.stale == true))
    }

    // MARK: - Guía de programación XMLTV: solo lo que está en el aire y lo próximo.

    /// "20240101120000 +0100" -> segundos Unix (UTC).
    static func epgTime(_ value: String) -> Int {
        let v = Array(value.trimmingCharacters(in: .whitespaces))
        guard v.count >= 14 else { return 0 }
        func num(_ from: Int, _ len: Int) -> Int { Int(String(v[from..<from + len])) ?? 0 }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        let comps = DateComponents(year: num(0, 4), month: num(4, 2), day: num(6, 2), hour: num(8, 2), minute: num(10, 2), second: num(12, 2))
        guard let date = cal.date(from: comps) else { return 0 }
        var secs = Int(date.timeIntervalSince1970)
        let tz = Array(String(v[14...]).trimmingCharacters(in: .whitespaces))
        if tz.count == 5 {
            let offset = (Int(String(tz[1...2])) ?? 0) * 3600 + (Int(String(tz[3...4])) ?? 0) * 60
            secs += tz[0] == "+" ? -offset : offset
        }
        return secs
    }

    static func xmlText(_ s: String) -> String {
        s.replacingOccurrences(of: "&amp;", with: "&").replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">").replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&apos;", with: "'").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Programas de los canales pedidos en un XMLTV ya descargado (máx. 6 por canal, próximas 8 h).
    static func parseEpg(_ text: String, wanted: Set<String>, now: Int, into programmes: inout [String: [Programme]]) {
        var scan = text.range(of: "<programme")
        while let open = scan {
            guard let close = text.range(of: "</programme>", range: open.lowerBound..<text.endIndex) else { break }
            let chunk = String(text[open.lowerBound..<close.lowerBound])
            let head = String(chunk.prefix(300))
            let channel = firstGroup("channel=\"([^\"]*)\"", head)
            if wanted.contains(channel) {
                let start = epgTime(firstGroup("start=\"([^\"]*)\"", head))
                let stop = epgTime(firstGroup("stop=\"([^\"]*)\"", head))
                if stop > now, start < now + 8 * 3600, programmes[channel, default: []].count < 6 {
                    let title = xmlText(firstGroup("<title[^>]*>(.*?)</title>", chunk, options: .dotMatchesLineSeparators))
                    programmes[channel, default: []].append(Programme(start: start, stop: stop, title: title))
                }
            }
            scan = text.range(of: "<programme", range: close.lowerBound..<text.endIndex)
        }
    }

    public static func epg(sources: [String], ids: Set<String>) async -> [String: [Programme]] {
        var programmes: [String: [Programme]] = [:]
        if ids.isEmpty { return programmes }
        // shortcut: las guías .gz no se leen (igual que en Roku); usar una guía .xml en la lista.
        let used = sources.filter { !$0.lowercased().hasSuffix(".gz") }.prefix(3)
        let now = nowSec()
        for src in used {
            let r = await http(src, timeout: 90)
            // Guías enormes no caben en la memoria.
            if r.data.count > 0, r.data.count < 40_000_000 { parseEpg(r.body, wanted: ids, now: now, into: &programmes) }
        }
        for (id, list) in programmes {
            programmes[id] = list.enumerated().sorted { ($0.element.start, $0.offset) < ($1.element.start, $1.offset) }.map(\.element)
        }
        return programmes
    }
}
