// Tipos que comparten la lógica y la interfaz (equivalen a los diccionarios que devuelve
// cada función Api* del canal de Roku).
import Foundation

public struct CoreError: Error, LocalizedError {
    public var message: String
    public init(_ message: String) { self.message = message }
    public var errorDescription: String? { message }
}

// MARK: - Catálogo

public struct SeasonInfo: Identifiable, Hashable {
    public var number: Int
    public var title: String
    public var poster: String
    public var episodeCount: Int
    public var id: Int { number }
    public init(number: Int, title: String, poster: String, episodeCount: Int) {
        self.number = number; self.title = title; self.poster = poster; self.episodeCount = episodeCount
    }
}

public struct Episode: Identifiable, Hashable {
    public var id: String
    public var number: Int
    public var title: String
    public var overview: String
    public var poster: String
    public var airDate: String
    public var runtime: Int
    public init(id: String, number: Int, title: String, overview: String, poster: String, airDate: String, runtime: Int) {
        self.id = id; self.number = number; self.title = title; self.overview = overview
        self.poster = poster; self.airDate = airDate; self.runtime = runtime
    }
}

public struct Details {
    public var item: Item
    public var genres: String
    public var runtime: Int
    public var tagline: String
    public var imdbId: String
    public var cast: [Item]
    public var recommendations: [Item]
    public var seasons: [SeasonInfo]
    public init(item: Item, genres: String, runtime: Int, tagline: String, imdbId: String, cast: [Item], recommendations: [Item], seasons: [SeasonInfo]) {
        self.item = item; self.genres = genres; self.runtime = runtime; self.tagline = tagline
        self.imdbId = imdbId; self.cast = cast; self.recommendations = recommendations; self.seasons = seasons
    }
}

public struct PersonPage {
    public var title: String
    public var overview: String
    public var poster: String
    public var items: [Item]
    public init(title: String, overview: String, poster: String, items: [Item]) {
        self.title = title; self.overview = overview; self.poster = poster; self.items = items
    }
}

// MARK: - Reproducción

/// Qué se va a reproducir (como el diccionario "playback" de Roku).
public struct PlaybackRequest: Hashable {
    public var mediaType: String      // movie o tv
    public var id: String             // id TMDb (de la película o de la serie)
    public var title: String
    public var year: String
    public var season: Int = 0
    public var episode: Int = 0
    public var episodeId: String = "" // id TMDb del episodio
    public var episodeTitle: String = ""
    public var episodePoster: String = ""
    public var episodeOverview: String = ""
    public var seasonTitle: String = ""
    public var item: Item              // la película o la serie (para la nube)
    public var links: [String: SiteLink] = [:]
    public init(mediaType: String, id: String, title: String, year: String, season: Int = 0, episode: Int = 0,
                episodeId: String = "", episodeTitle: String = "", item: Item, links: [String: SiteLink] = [:]) {
        self.mediaType = mediaType; self.id = id; self.title = title; self.year = year; self.season = season
        self.episode = episode; self.episodeId = episodeId; self.episodeTitle = episodeTitle; self.item = item; self.links = links
    }
}

/// Servidor encontrado: name visible, url de la página del servidor ("vixsrc" para VixSrc),
/// referer y lang ("lat", "cast", "sub" o "multi").
public struct Server: Identifiable, Hashable {
    public var name: String
    public var url: String
    public var referer: String
    public var lang: String
    public var id: String { name + "|" + url }
    public init(name: String, url: String, referer: String = "", lang: String = "") {
        self.name = name; self.url = url; self.referer = referer; self.lang = lang
    }
}

/// Video listo para reproducir: url, formato ("hls", "mp4", "dash") y cabeceras HTTP.
public struct Stream: Hashable {
    public var url: String
    public var format: String
    public var headers: [String: String]
    public var server: String
    public init(url: String, format: String, headers: [String: String] = [:], server: String = "") {
        self.url = url; self.format = format; self.headers = headers; self.server = server
    }
}

// MARK: - En Vivo

public struct StreamSource: Hashable {
    public var url: String
    public var headers: [String: String]
    public init(url: String, headers: [String: String] = [:]) { self.url = url; self.headers = headers }
}

public struct Channel: Identifiable, Hashable {
    public var id: String
    public var name: String
    public var logo: String
    public var group: String
    public var country: String
    public var epgId: String
    /// Señal principal y alternativas (otras líneas del mismo canal).
    public var sources: [StreamSource]
    public init(id: String, name: String, logo: String, group: String, country: String, epgId: String, sources: [StreamSource]) {
        self.id = id; self.name = name; self.logo = logo; self.group = group
        self.country = country; self.epgId = epgId; self.sources = sources
    }
}

/// Carpeta de En Vivo: "all" (Todos), "category" o "country".
public struct LiveFolder: Identifiable, Hashable {
    public var title: String
    public var folderType: String
    public var channels: [Channel]
    public var id: String { folderType + ":" + title }
    public init(title: String, folderType: String, channels: [Channel]) {
        self.title = title; self.folderType = folderType; self.channels = channels
    }
}

public struct LiveResult {
    public var folders: [LiveFolder]
    public var epgSources: [String]
    public var total: Int
    /// true si se mostró la copia guardada y conviene actualizarla aparte.
    public var stale: Bool
    public init(folders: [LiveFolder], epgSources: [String], total: Int, stale: Bool) {
        self.folders = folders; self.epgSources = epgSources; self.total = total; self.stale = stale
    }
}

public struct Programme: Hashable {
    public var start: Int   // segundos Unix
    public var stop: Int
    public var title: String
    public init(start: Int, stop: Int, title: String) { self.start = start; self.stop = stop; self.title = title }
}

// MARK: - Panel

public struct Profile: Identifiable, Hashable, Codable {
    public var id: String
    public var name: String
    public var avatar: String
    public var pin: String
    public init(id: String, name: String, avatar: String, pin: String) {
        self.id = id; self.name = name; self.avatar = avatar; self.pin = pin
    }
}

public struct Account: Codable {
    public var limit: Int
    public var profiles: [Profile]
    public var avatars: [String]
    public init(limit: Int, profiles: [Profile], avatars: [String]) {
        self.limit = limit; self.profiles = profiles; self.avatars = avatars
    }
}
