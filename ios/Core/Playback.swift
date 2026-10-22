// Partes puras de Playback.brs: formato del video y elección/nombre de pistas de audio y subtítulos.
import Foundation

public enum Tracks {
    /// GuessFormat: "hls", "dash", "mp4", "mkv", "mp3" o "" (.ts: lo decide el reproductor).
    public static func guessFormat(_ url: String) -> String {
        var path = url.lowercased()
        if let q = path.firstIndex(of: "?") { path = String(path[..<q]) }
        if path.hasSuffix(".m3u8") || path.hasSuffix(".m3u") { return "hls" }
        if path.hasSuffix(".mpd") { return "dash" }
        if path.hasSuffix(".mp4") { return "mp4" }
        if path.hasSuffix(".mkv") { return "mkv" }
        if path.hasSuffix(".mp3") { return "mp3" }
        if path.hasSuffix(".ts") { return "" }
        return "hls"
    }

    /// Pista de audio según el idioma de Ajustes (nil si ninguna sirve).
    public static func pickAudio(_ languages: [String], lang: String) -> Int? {
        pickAudio(languages.map { (language: $0, name: "") }, lang: lang)
    }

    public static func pickAudio(_ tracks: [(language: String, name: String)], lang: String) -> Int? {
        var best: Int?
        var bestScore = 0
        for (i, t) in tracks.enumerated() {
            let score = audioScore(t, lang: lang)
            if score > bestScore { best = i; bestScore = score }
        }
        return best
    }

    /// Prioridad del audio (igual en Android y Roku): en español, latino 3 > castellano 2 > inglés 1;
    /// en inglés, inglés 3 > latino 2 > castellano 1. 0 = otro idioma (no se elige).
    public static func audioScore(_ t: (language: String, name: String), lang: String) -> Int {
        let language = t.language.lowercased(), name = t.name.lowercased()
        let english = language.hasPrefix("en") || name.contains("english") || name.contains("inglés") || name.contains("ingles")
        let latino = name.contains("latin") || name.contains("lat)") || name.contains("[lat") || language.contains("419") || language.contains("mx")
        let spanish = language.hasPrefix("es") || language.hasPrefix("spa") || name.contains("spanish") || name.contains("español") || name.contains("espanol")
        if lang == "en" { return english ? 3 : latino ? 2 : spanish ? 1 : 0 }
        return latino ? 3 : spanish ? 2 : english ? 1 : 0
    }

    /// Subtítulos completos en español (no "forced", que solo traducen partes sueltas).
    public static func pickSubtitle(_ languages: [String]) -> Int? {
        pickSubtitle(languages.map { (language: $0, name: "") })
    }

    /// `name`: nombre y descripción de la pista (Roku usa TrackName + " " + Description).
    public static func pickSubtitle(_ tracks: [(language: String, name: String)]) -> Int? {
        var best: Int?
        var bestScore = 0
        for (i, t) in tracks.enumerated() {
            let language = t.language.lowercased(), text = t.name.lowercased()
            var score = 0
            if language.hasPrefix("es") || language.hasPrefix("spa") || text.contains("spanish") || text.contains("español") { score = 1 }
            if score > 0 && (text.contains("latin") || language.contains("419")) { score = 2 }
            if text.contains("forced") || text.contains("forzad") { score = 0 }
            if score > bestScore { best = i; bestScore = score }
        }
        return best
    }

    static let trackNames = ["es": "Español", "spa": "Español", "esp": "Español", "en": "Inglés", "eng": "Inglés", "it": "Italiano", "ita": "Italiano",
                             "pt": "Portugués", "por": "Portugués", "fr": "Francés", "fra": "Francés", "fre": "Francés", "de": "Alemán", "deu": "Alemán",
                             "ger": "Alemán", "ja": "Japonés", "jpn": "Japonés", "ko": "Coreano", "kor": "Coreano", "zh": "Chino", "chi": "Chino",
                             "zho": "Chino", "ru": "Ruso", "rus": "Ruso"]

    /// TrackLabel: nombre en español de una pista ("spa" + "Latino" -> "Español latino").
    public static func label(language: String, name: String) -> String {
        let code = language.lowercased()
        let text = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = text.lowercased()
        guard let base = trackNames[String(code.prefix(3))] ?? trackNames[String(code.prefix(2))] else {
            if !text.isEmpty { return text }
            if !code.isEmpty { return code.uppercased() }
            return "Original"
        }
        if base == "Español" && (code.contains("419") || code.contains("mx") || lower.contains("lat")) { return "Español latino" }
        if base == "Español" && (lower.contains("castellano") || lower.contains("spain") || lower.contains("españa")) { return "Español (España)" }
        if lower.contains("forced") || lower.contains("forzad") { return base + " (forzados)" }
        return base
    }
}
