// Favoritos, historial y progreso del perfil activo, en el MISMO documento de la nube que usa la
// app Android (sync_userdata.php): { "<Proveedor>": { movies, tv_shows, episodes }, ... }.
// Port de UserData.brs del canal de Roku. Android guarda cada título bajo su proveedor (p. ej.
// "Cuevana_3") con el id de ese sitio, así que se leen todos los proveedores y se escribe en el
// registro que Android ya tiene, o en el proveedor por defecto del panel con el id de ese sitio.
// Los campos llevan el nombre EXACTO que lee Android (Gson distingue mayúsculas).
//
// Hilos: todo se llama desde el hilo principal (no hay bloqueo interno).
import Foundation

/// proveedor -> tipo (movies, tv_shows, episodes) -> id -> registro.
typealias UDDoc = [String: [String: [String: JSON]]]

/// Dónde está (o irá) el registro de un título.
struct UDLoc: Equatable {
    var key: String
    var id: String
    var link: SiteLink? = nil
}

struct UDEpisodeLoc {
    var key: String
    var id: String
    var showLoc: UDLoc
}

let udKinds = ["movies", "tv_shows", "episodes"]

/// Valor JSON presente (en BrightScript null es invalid).
func udHas(_ rec: JSON, _ key: String) -> Bool { rec[key] != nil && !(rec[key] is NSNull) }

/// Booleano JSON verdadero/falso (un número no cuenta, como en BrightScript).
func udBool(_ v: Any?) -> Bool? {
    if let n = v as? NSNumber { return isBoolNumber(n) ? n.boolValue : nil }
    return v as? Bool
}

public final class UserData {
    public static let shared = UserData()
    public static let changed = Notification.Name("UserDataChanged")

    /// true cuando lo local cambió y hay que subirlo a la nube.
    public var uploadPending = false
    private(set) var profileId = "00"
    private(set) var data: UDDoc = [:]

    init() {}

    static func fileName(_ profileId: String) -> String { "ud_" + profileId + ".json" }
    static func tmdbKey() -> String { safeName(catalogProviderName()) }

    // MARK: - Documento

    static func normalizePart(_ part: Any?) -> [String: [String: JSON]] {
        var out: [String: [String: JSON]] = ["movies": [:], "tv_shows": [:], "episodes": [:]]
        guard let part = part as? [String: Any] else { return out }
        for kind in udKinds {
            // shortcut: se descartan registros que no son objetos (Roku los guardaba sin poder leerlos).
            if let records = part[kind] as? [String: Any] { out[kind] = records.compactMapValues { $0 as? JSON } }
        }
        return out
    }

    /// Documento completo: una parte por proveedor (sin claves "_...").
    static func normalize(_ data: Any?) -> UDDoc {
        var out: UDDoc = [:]
        guard let data = data as? [String: Any] else { return out }
        for (key, value) in data where !key.hasPrefix("_") && value is [String: Any] {
            out[key] = normalizePart(value)
        }
        return out
    }

    public func load(profileId: String) {
        self.profileId = profileId
        data = Self.normalize(jsonOf(Paths.readText(Self.fileName(profileId))))
        notify()
    }

    private func save() { Paths.writeText(Self.fileName(profileId), jsonText(data)) }

    private func notify() {
        if Thread.isMainThread { NotificationCenter.default.post(name: Self.changed, object: self) }
        else { DispatchQueue.main.async { NotificationCenter.default.post(name: Self.changed, object: self) } }
    }

    /// Guarda en el equipo y marca la subida pendiente (la app sube con espera, como Android).
    private func commit(_ newData: UDDoc) {
        data = newData
        save()
        uploadPending = true
        notify()
    }

    public func deleteAllFiles() {
        for name in Paths.names() where name.hasPrefix("ud_") || name.hasPrefix("cloud_") { Paths.delete(name) }
    }

    // MARK: - Reglas de fusión (CloudSyncManager: gana el más reciente; si empatan, el que tiene datos)

    static func calendarMs(_ cal: Any?) -> Int64 {
        guard let cal = cal as? [String: Any] else { return 0 }
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        let comps = DateComponents(year: toInt(cal["year"]), month: toInt(cal["month"]) + 1, day: toInt(cal["dayOfMonth"]),
                                   hour: toInt(cal["hourOfDay"]), minute: toInt(cal["minute"]), second: toInt(cal["second"]))
        guard let date = c.date(from: comps) else { return 0 }
        return Int64(date.timeIntervalSince1970) * 1000
    }

    /// Fecha como la serializa Gson para java.util.Calendar (en UTC, como Roku).
    static func nowCalendar(_ date: Date = Date()) -> JSON {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        let d = c.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        return ["year": d.year!, "month": d.month! - 1, "dayOfMonth": d.day!, "hourOfDay": d.hour!, "minute": d.minute!, "second": d.second!]
    }

    static func updatedAt(_ kind: String, _ rec: JSON) -> Int64 {
        let engagement = (rec["watchHistory"] as? [String: Any])?["lastEngagementTimeUtcMillis"]
        let values: [Any?]
        switch kind {
        case "movies": values = [engagement, rec["favoritedAtMillis"], rec["lastPlayedAtMillis"], calendarMs(rec["watchedDate"])]
        case "tv_shows": values = [rec["favoritedAtMillis"], rec["lastPlayedAtMillis"]]
        default: values = [engagement, calendarMs(rec["watchedDate"])]
        }
        return values.map { toLong($0) }.reduce(0, max)
    }

    static func hasData(_ kind: String, _ rec: JSON) -> Bool {
        switch kind {
        case "movies": return udBool(rec["isFavorite"]) == true || udBool(rec["isWatched"]) == true || udHas(rec, "watchHistory") || udHas(rec, "lastPlayedAtMillis")
        case "tv_shows": return udBool(rec["isFavorite"]) == true || udBool(rec["isWatching"]) == false || udHas(rec, "lastPlayedAtMillis")
        default: return udBool(rec["isWatched"]) == true || udHas(rec, "watchHistory")
        }
    }

    /// Última copia de la nube que este equipo leyó (y fusionó) o subió: la "base" de la fusión.
    static func baseFile(_ profileId: String) -> String { "cloud_" + profileId + ".json" }

    /// Fusión de tres vías (spec compartida con Android y Roku): base = última nube conocida,
    /// remote = la nube recién leída, local = lo de este equipo. Así no se borra lo que otro
    /// equipo escribió mientras esta app estaba abierta. needsUpload: lo fusionado difiere de la nube.
    static func merge3(local: UDDoc, remote: UDDoc, base: UDDoc) -> (merged: UDDoc, needsUpload: Bool) {
        var merged = local
        var needsUpload = false
        for key in Set(local.keys).union(remote.keys) {
            var part = local[key] ?? normalizePart(nil)
            for kind in udKinds {
                let r = remote[key]?[kind] ?? [:], b = base[key]?[kind] ?? [:], l = local[key]?[kind] ?? [:]
                var m = l
                for (id, rr) in r {
                    // Sin cambios en la nube desde la base: se respeta lo local (también si se borró aquí).
                    if let bb = b[id], updatedAt(kind, bb) == updatedAt(kind, rr), hasData(kind, bb) == hasData(kind, rr) { continue }
                    guard let ll = l[id] else { m[id] = rr; continue }
                    let ru = updatedAt(kind, rr), lu = updatedAt(kind, ll)
                    if ru > lu || (ru == lu && hasData(kind, rr) && !hasData(kind, ll)) { m[id] = rr }
                }
                // Estaba en la base y ya no en la nube: otro equipo lo quitó (salvo que aquí sea más nuevo).
                for (id, ll) in l where r[id] == nil {
                    if let bb = b[id], updatedAt(kind, ll) <= updatedAt(kind, bb) { m[id] = nil }
                }
                part[kind] = m
                // Se sube si los registros con datos no coinciden con los de la nube.
                let mine = m.filter { hasData(kind, $0.value) }, theirs = r.filter { hasData(kind, $0.value) }
                if Set(mine.keys) != Set(theirs.keys) || mine.contains(where: { updatedAt(kind, $0.value) != updatedAt(kind, theirs[$0.key]!) }) {
                    needsUpload = true
                }
            }
            merged[key] = part
        }
        return (merged, needsUpload)
    }

    /// Fusiona la nube recién leída con lo local del perfil (el activo o uno que se acaba de dejar)
    /// y la guarda como nueva base. Devuelve true si lo local tiene algo que la nube no (hay que subir).
    @discardableResult
    public func mergeRemote(_ doc: JSON, profileId other: String? = nil) -> Bool {
        let pid = other ?? profileId
        let active = pid == profileId
        let local = active ? data : Self.normalize(jsonOf(Paths.readText(Self.fileName(pid))))
        let base = Self.normalize(jsonOf(Paths.readText(Self.baseFile(pid))))
        let result = Self.merge3(local: local, remote: Self.normalize(doc), base: base)
        Paths.writeText(Self.fileName(pid), jsonText(result.merged))
        Paths.writeText(Self.baseFile(pid), jsonText(doc))
        if active {
            data = result.merged
            notify()
        }
        return result.needsUpload
    }

    /// Lo subido con éxito pasa a ser la base de la próxima fusión.
    public func uploaded(_ doc: JSON, profileId pid: String) {
        Paths.writeText(Self.baseFile(pid), jsonText(doc))
    }

    /// Cuerpo para subir: la nube recién leída con cada proveedor de este equipo reemplazado por
    /// lo fusionado (solo registros con datos). Los proveedores que el equipo no tiene quedan igual.
    public func uploadBody(_ remote: JSON, profileId other: String? = nil) -> JSON {
        let pid = other ?? profileId
        let local = pid == profileId ? data : Self.normalize(jsonOf(Paths.readText(Self.fileName(pid))))
        var out: JSON = [:]
        for (key, value) in remote where !key.hasPrefix("_") { out[key] = value }
        for (key, part) in Self.forUpload(local) { out[key] = part }
        return out
    }

    /// Lo que se sube: todos los proveedores, solo registros con datos del usuario.
    public func forUpload() -> JSON { Self.forUpload(data) }

    static func forUpload(_ data: UDDoc) -> JSON {
        var out: JSON = [:]
        for (key, part) in data {
            var outPart: JSON = [:]
            for kind in udKinds {
                let records = (part[kind] ?? [:]).filter { Self.hasData(kind, $0.value) }
                if !records.isEmpty { outPart[kind] = records }
            }
            out[key] = outPart
        }
        return out
    }

    // MARK: - Dónde está (o irá) el registro de un título

    static func kindOf(_ mediaType: String) -> String { mediaType == "tv" ? "tv_shows" : "movies" }

    /// item: el de la ficha (id de TMDb; extra pkey/pid si vino de un registro de la nube).
    /// links: ids del título en los sitios, por clave de proveedor.
    static func locate(_ data: UDDoc, _ kind: String, _ item: Item, _ links: [String: SiteLink]) -> UDLoc {
        let pkey = item.extra["pkey"] ?? "", pid = item.extra["pid"] ?? ""
        if !pkey.isEmpty, !pid.isEmpty { return UDLoc(key: pkey, id: pid, link: links[pkey]) }
        let mainKey = mainCloudKey()
        for key in [mainKey] + links.keys.sorted().filter({ $0 != mainKey }) {
            if let link = links[key], data[key]?[kind]?[link.id] != nil { return UDLoc(key: key, id: link.id, link: link) }
        }
        let tmdbStore = tmdbKey()
        if data[tmdbStore]?[kind]?[item.itemId] != nil { return UDLoc(key: tmdbStore, id: item.itemId) }
        // Registro de otro proveedor con el mismo título (Android lo guardó con su id).
        let wanted = titleKey(item.title)
        if !wanted.isEmpty {
            for key in data.keys.sorted() {
                let records = data[key]?[kind] ?? [:]
                for id in records.keys.sorted() where titleKey(asStr(records[id]?["title"])) == wanted {
                    return UDLoc(key: key, id: id, link: links[key])
                }
            }
        }
        if let link = links[mainKey] { return UDLoc(key: mainKey, id: link.id, link: link) }
        return UDLoc(key: tmdbStore, id: item.itemId)
    }

    static func baseRecord(_ kind: String, _ item: Item, _ id: String) -> JSON {
        var rec: JSON = ["id": id, "title": item.title, "overview": item.overview, "poster": item.poster,
                         "banner": item.backdrop, "isFavorite": false]
        if !item.rating.isEmpty { rec["rating"] = Double(item.rating) ?? 0 }
        if kind == "movies" { rec["isWatched"] = false }
        if kind == "tv_shows" { rec["isWatching"] = true }
        return rec
    }

    static func get(_ data: UDDoc, _ kind: String, _ loc: UDLoc) -> JSON? { data[loc.key]?[kind]?[loc.id] }

    /// Episodio de la serie en el mismo proveedor: el que Android ya guardó (serie + temporada +
    /// número) o un id nuevo con el formato del sitio. nil si no se sabe armar.
    static func locateEpisode(_ data: UDDoc, _ showLoc: UDLoc, _ season: Int, _ number: Int, _ tmdbEpisodeId: String) -> UDEpisodeLoc? {
        let episodes = data[showLoc.key]?["episodes"] ?? [:]
        for id in episodes.keys.sorted() {
            let ep = episodes[id]!
            if let show = ep["tvShow"] as? [String: Any], asStr(show["id"]) == showLoc.id,
               let s = ep["season"] as? [String: Any], toInt(s["number"]) == season, toInt(ep["number"]) == number {
                return UDEpisodeLoc(key: showLoc.key, id: id, showLoc: showLoc)
            }
        }
        if showLoc.key == tmdbKey() { return UDEpisodeLoc(key: showLoc.key, id: tmdbEpisodeId, showLoc: showLoc) }
        if let link = showLoc.link {
            var id = link.episodeId
            if id.isEmpty || link.style != "cine" { id = linkEpisodeId(link, String(season), String(number)) }
            return UDEpisodeLoc(key: showLoc.key, id: id, showLoc: showLoc)
        }
        return nil
    }

    // MARK: - Favoritos ("Mi lista")

    public func isFavorite(_ item: Item, links: [String: SiteLink]) -> Bool {
        let kind = Self.kindOf(item.mediaType)
        return udBool(Self.get(data, kind, Self.locate(data, kind, item, links))?["isFavorite"]) == true
    }

    public func setFavorite(_ item: Item, links: [String: SiteLink], favorite: Bool) {
        var d = data
        let kind = Self.kindOf(item.mediaType)
        let loc = Self.locate(d, kind, item, links)
        var part = d[loc.key] ?? Self.normalizePart(nil)
        var rec = part[kind]?[loc.id] ?? Self.baseRecord(kind, item, loc.id)
        rec["isFavorite"] = favorite
        rec["favoritedAtMillis"] = nowMs()
        part[kind]![loc.id] = Self.hasData(kind, rec) ? rec : nil
        d[loc.key] = part
        commit(d)
    }

    // MARK: - Progreso de reproducción

    public func resume(of rec: JSON?) -> Int {
        guard let history = rec?["watchHistory"] as? [String: Any] else { return 0 }
        return Int(toLong(history["lastPlaybackPositionMillis"]) / 1000)
    }

    /// Porcentaje visto (para la barra de "Continuar viendo").
    public func percent(of rec: JSON?) -> Int {
        guard let history = rec?["watchHistory"] as? [String: Any] else { return 0 }
        let dur = toLong(history["durationMillis"])
        if dur <= 0 { return 0 }
        return Int(toLong(history["lastPlaybackPositionMillis"]) * 100 / dur)
    }

    /// Película: segundos donde se quedó.
    public func movieResume(_ item: Item, links: [String: SiteLink]) -> Int {
        resume(of: Self.get(data, "movies", Self.locate(data, "movies", item, links)))
    }

    public func movieWatched(_ item: Item, links: [String: SiteLink]) -> Bool {
        udBool(Self.get(data, "movies", Self.locate(data, "movies", item, links))?["isWatched"]) == true
    }

    /// Registro de un episodio (serie de la ficha, temporada y número) o nil.
    public func episodeRecord(_ item: Item, links: [String: SiteLink], season: Int, number: Int, tmdbEpisodeId: String) -> JSON? {
        guard let loc = Self.locateEpisode(data, Self.locate(data, "tv_shows", item, links), season, number, tmdbEpisodeId) else { return nil }
        return data[loc.key]?["episodes"]?[loc.id]
    }

    public func saveProgress(_ playback: PlaybackRequest, positionSec: Int, durationSec: Int) {
        if durationSec <= 0 || positionSec < 5 { return }
        let finished = Double(positionSec) >= Double(durationSec) * 0.9
        let now = nowMs()
        let history: JSON = ["lastEngagementTimeUtcMillis": now, "lastPlaybackPositionMillis": Int64(positionSec) * 1000,
                             "durationMillis": Int64(durationSec) * 1000]
        updatePlayback(playback, playedAt: now, watched: finished, history: history)
    }

    /// "Marcar como visto / no visto" (ficha y episodios).
    public func setWatched(_ playback: PlaybackRequest, watched: Bool) {
        updatePlayback(playback, playedAt: nil, watched: watched, history: nil)
    }

    /// Cambia el registro de una película o episodio (y su serie). history nil = sin progreso.
    func updatePlayback(_ playback: PlaybackRequest, playedAt: Int64?, watched: Bool, history: JSON?) {
        var d = data
        let item = playback.item, links = playback.links
        if playback.mediaType == "movie" {
            let loc = Self.locate(d, "movies", item, links)
            var part = d[loc.key] ?? Self.normalizePart(nil)
            var rec = part["movies"]?[loc.id] ?? Self.baseRecord("movies", item, loc.id)
            if let playedAt { rec["lastPlayedAtMillis"] = playedAt }
            Self.applyWatch(&rec, watched: watched, history: history)
            part["movies"]![loc.id] = Self.hasData("movies", rec) ? rec : nil
            d[loc.key] = part
        } else {
            var showLoc = Self.locate(d, "tv_shows", item, links)
            // Proveedor de Android cuyos ids no se saben armar: la serie y el episodio van a TMDb.
            let loc = Self.locateEpisode(d, showLoc, playback.season, playback.episode, playback.episodeId)
                ?? UDEpisodeLoc(key: Self.tmdbKey(), id: playback.episodeId, showLoc: UDLoc(key: Self.tmdbKey(), id: item.itemId))
            showLoc = loc.showLoc
            var part = d[loc.key] ?? Self.normalizePart(nil)
            if let playedAt {
                var show = part["tv_shows"]?[showLoc.id] ?? Self.baseRecord("tv_shows", item, showLoc.id)
                show["lastPlayedAtMillis"] = playedAt
                show["lastPlayedEpisodeId"] = loc.id
                show["isWatching"] = true
                part["tv_shows"]![showLoc.id] = show
            }
            var seasonId = item.itemId + "-" + String(playback.season)
            if let link = showLoc.link { seasonId = linkSeasonId(link, String(playback.season)) }
            // shortcut: PlaybackRequest no trae póster/resumen del episodio ni el nombre de la temporada; van vacíos.
            var rec = part["episodes"]?[loc.id] ?? [
                "id": loc.id, "number": playback.episode, "title": playback.episodeTitle, "poster": playback.episodePoster, "overview": playback.episodeOverview, "isWatched": false,
                "tvShow": ["id": showLoc.id, "title": item.title, "poster": item.poster, "banner": item.backdrop] as JSON,
                "season": ["id": seasonId, "number": playback.season, "title": playback.seasonTitle] as JSON,
            ]
            Self.applyWatch(&rec, watched: watched, history: history)
            part["episodes"]![loc.id] = Self.hasData("episodes", rec) ? rec : nil
            d[loc.key] = part
        }
        commit(d)
    }

    static func applyWatch(_ rec: inout JSON, watched: Bool, history: JSON?) {
        if watched {
            rec["isWatched"] = true
            rec["watchedDate"] = nowCalendar()
            rec["watchHistory"] = nil
        } else if let history {
            rec["watchHistory"] = history
        } else {
            rec["isWatched"] = false
            rec["watchedDate"] = nil
            rec["watchHistory"] = nil
        }
    }

    /// Último episodio visto de una serie.
    public func lastEpisode(_ item: Item, links: [String: SiteLink]) -> (season: Int, episode: Int)? {
        let loc = Self.locate(data, "tv_shows", item, links)
        guard let show = Self.get(data, "tv_shows", loc), udHas(show, "lastPlayedEpisodeId"),
              let ep = data[loc.key]?["episodes"]?[asStr(show["lastPlayedEpisodeId"])],
              let season = ep["season"] as? [String: Any] else { return nil }
        return (toInt(season["number"]), toInt(ep["number"]))
    }

    // MARK: - Listas para las filas (de todos los proveedores, sin repetir títulos)

    static func recordToItem(_ key: String, _ kind: String, _ rec: JSON) -> Item {
        // Registro de otro proveedor: la ficha busca el título en TMDb y escribe en este registro.
        Item(itemId: asStr(rec["id"]), mediaType: kind == "tv_shows" ? "tv" : "movie", title: asStr(rec["title"]),
             overview: asStr(rec["overview"]), poster: asStr(rec["poster"]), backdrop: asStr(rec["banner"]), rating: asStr(rec["rating"]),
             extra: ["pkey": key, "pid": asStr(rec["id"]), "match": key != tmdbKey() ? "1" : "0"])
    }

    static func collect(_ entries: [(sortKey: Int64, item: Item)]) -> [Item] {
        var out: [Item] = []
        var seen = Set<String>()
        for e in entries.sorted(by: { $0.sortKey > $1.sortKey }) where seen.insert(e.item.mediaType + titleKey(e.item.title)).inserted {
            out.append(e.item)
        }
        return out
    }

    /// Canales en vivo que Android guarda en la nube ("Live", listas IPTV, Vavoo): van solo en En Vivo.
    static func isLiveKey(_ key: String) -> Bool {
        let k = key.lowercased()
        return k == "live" || k.contains("iptv") || k.contains("vavoo") || k.contains("live_tv")
    }

    public func continueWatching() -> [Item] {
        var entries: [(sortKey: Int64, item: Item)] = []
        for key in data.keys.sorted() where !Self.isLiveKey(key) {
            let part = data[key]!
            for (_, rec) in part["movies"] ?? [:] where udHas(rec, "watchHistory") {
                var item = Self.recordToItem(key, "movies", rec)
                item.progress = percent(of: rec)
                entries.append((Self.updatedAt("movies", rec), item))
            }
            for (_, rec) in part["tv_shows"] ?? [:] where udBool(rec["isWatching"]) != false && udHas(rec, "lastPlayedAtMillis") {
                var item = Self.recordToItem(key, "tv_shows", rec)
                if udHas(rec, "lastPlayedEpisodeId"), let ep = part["episodes"]?[asStr(rec["lastPlayedEpisodeId"])] {
                    item.progress = percent(of: ep)
                }
                entries.append((Self.updatedAt("tv_shows", rec), item))
            }
        }
        return Self.collect(entries)
    }

    public func favorites() -> [Item] {
        var entries: [(sortKey: Int64, item: Item)] = []
        for key in data.keys.sorted() where !Self.isLiveKey(key) {
            for kind in ["movies", "tv_shows"] {
                for (_, rec) in data[key]?[kind] ?? [:] where udBool(rec["isFavorite"]) == true {
                    entries.append((toLong(rec["favoritedAtMillis"]), Self.recordToItem(key, kind, rec)))
                }
            }
        }
        return Self.collect(entries)
    }
}
