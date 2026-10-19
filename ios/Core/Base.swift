// Utilidades compartidas (equivalen a Utils.brs del canal de Roku y a UserPreferences,
// DeviceMac, CloudSyncManager.decryptData y generateSecurityToken de la app Android).
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum AppInfo {
    public static let versionName = "1.8.9"
    // Mismo versionCode que Android: el panel lo recibe al iniciar sesión.
    public static let versionCode = 21
}

// MARK: - JSON (los JSON del panel mezclan números y textos)

public typealias JSON = [String: Any]

public func asStr(_ v: Any?) -> String {
    switch v {
    case nil, is NSNull: return ""
    case let s as String: return s
    case let n as NSNumber:
        // Los booleanos de JSON llegan como NSNumber de tipo "c" (en iOS y en Linux).
        if isBoolNumber(n) { return n.boolValue ? "true" : "false" }
        let d = n.doubleValue
        if d == d.rounded(), abs(d) < 1e18 { return String(Int64(d)) }
        return String(d)
    case let b as Bool: return b ? "true" : "false"
    case let i as Int: return String(i)
    case let d as Double: return d == d.rounded() && abs(d) < 1e18 ? String(Int64(d)) : String(d)
    default: return ""
    }
}

func isBoolNumber(_ n: NSNumber) -> Bool { String(cString: n.objCType) == "c" }

public func toInt(_ v: Any?, _ def: Int = 0) -> Int {
    switch v {
    case let s as String:
        let t = s.trimmingCharacters(in: .whitespaces)
        if t.isEmpty { return def }
        if let i = Int(t) { return i }
        // Como ToInt de BrightScript: toma los dígitos del principio ("12abc" -> 12).
        let digits = t.prefix { $0.isNumber || $0 == "-" }
        return Int(digits) ?? Int(Double(t) ?? Double(def))
    case let n as NSNumber: return n.intValue
    case let b as Bool: return b ? 1 : 0
    case let i as Int: return i
    case let d as Double: return Int(d)
    default: return def
    }
}

public func toLong(_ v: Any?) -> Int64 {
    switch v {
    case let s as String: return Int64(s.trimmingCharacters(in: .whitespaces)) ?? Int64(Double(s.trimmingCharacters(in: .whitespaces)) ?? 0)
    case let n as NSNumber: return n.int64Value
    case let i as Int: return Int64(i)
    case let d as Double: return Int64(d)
    default: return 0
    }
}

public func toDouble(_ v: Any?) -> Double {
    switch v {
    case let s as String: return Double(s.trimmingCharacters(in: .whitespaces)) ?? 0
    case let n as NSNumber: return n.doubleValue
    case let i as Int: return Double(i)
    case let d as Double: return d
    default: return 0
    }
}

public func jsonOf(_ body: String) -> Any? {
    let t = body.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !t.isEmpty, let data = t.data(using: .utf8) else { return nil }
    return try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
}

public func jsonText(_ value: Any) -> String {
    guard JSONSerialization.isValidJSONObject(value),
          let data = try? JSONSerialization.data(withJSONObject: value, options: []) else { return "{}" }
    return String(decoding: data, as: UTF8.self)
}

/// Texto del panel: Android hace .replace("null", "").trim().
public func panelText(_ v: Any?) -> String {
    asStr(v).replacingOccurrences(of: "null", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
}

// MARK: - Preferencias (equivalente a SharedPreferences "SecureGatewayPrefs" / registro de Roku)

public enum Reg {
    public static var store: UserDefaults = UserDefaults(suiteName: "gateway") ?? .standard
    /// Claves que sobreviven al cierre de sesión.
    static let kept: Set<String> = ["device_owner", "audio_lang", "device_uuid"]
    static let prefix = "gw."

    public static func read(_ key: String, _ def: String = "") -> String {
        store.string(forKey: prefix + key) ?? def
    }

    public static func write(_ key: String, _ value: String) {
        store.set(value, forKey: prefix + key)
    }

    public static func writeMany(_ values: [String: Any]) {
        for (k, v) in values { write(k, asStr(v)) }
    }

    public static func delete(_ key: String) { store.removeObject(forKey: prefix + key) }

    /// Cerrar sesión: se borra todo menos el dueño del equipo y sus ajustes.
    public static func clearSession() {
        for key in store.dictionaryRepresentation().keys where key.hasPrefix(prefix) {
            if !kept.contains(String(key.dropFirst(prefix.count))) { store.removeObject(forKey: key) }
        }
    }

    public static var isLoggedIn: Bool { !read("alpha_token").isEmpty && !read("beta_token").isEmpty }
}

// MARK: - Archivos (caché de canales y nube del perfil)

public enum Paths {
    public static var root: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return base.appendingPathComponent("VODPro", isDirectory: true)
    }()

    public static func file(_ name: String) -> URL {
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root.appendingPathComponent(name)
    }

    public static func readText(_ name: String) -> String {
        (try? String(contentsOf: file(name), encoding: .utf8)) ?? ""
    }

    public static func writeText(_ name: String, _ text: String) {
        try? text.write(to: file(name), atomically: true, encoding: .utf8)
    }

    public static func delete(_ name: String) { try? FileManager.default.removeItem(at: file(name)) }

    public static func names() -> [String] {
        (try? FileManager.default.contentsOfDirectory(atPath: root.path)) ?? []
    }
}

// MARK: - Red

public struct HTTPResult {
    public var code: Int
    public var body: String
    public var data: Data
    public var finalURL: String
    public static let failed = HTTPResult(code: -1, body: "", data: Data(), finalURL: "")
}

public let browserUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

public func urlEnc(_ text: String) -> String {
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "-._~")
    return text.addingPercentEncoding(withAllowedCharacters: allowed) ?? text
}

public enum Net {
    public static let session: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        cfg.httpCookieStorage = nil
        cfg.httpShouldSetCookies = false
        return URLSession(configuration: cfg)
    }()
}

/// GET/POST con cabeceras. Nunca lanza: si falla devuelve code -1 (como Http() de Roku).
public func http(_ url: String, method: String = "GET", body: String? = nil, headers: [String: String] = [:], timeout: TimeInterval = 15) async -> HTTPResult {
    guard let u = URL(string: url) else { return .failed }
    var req = URLRequest(url: u, timeoutInterval: timeout)
    req.httpMethod = method
    if headers["User-Agent"] == nil { req.setValue(browserUA, forHTTPHeaderField: "User-Agent") }
    for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
    if method != "GET", let body { req.httpBody = body.data(using: .utf8) }
    do {
        let (data, resp) = try await Net.session.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? -1
        let text = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
        return HTTPResult(code: code, body: text, data: data, finalURL: resp.url?.absoluteString ?? url)
    } catch {
        return .failed
    }
}

/// Varias peticiones GET a la vez; devuelve los resultados en el mismo orden.
public func httpParallel(_ urls: [String], headers: [String: String] = [:], timeout: TimeInterval = 20) async -> [HTTPResult] {
    await withTaskGroup(of: (Int, HTTPResult).self) { group in
        for (i, u) in urls.enumerated() {
            group.addTask { (i, await http(u, headers: headers, timeout: timeout)) }
        }
        var out = Array(repeating: HTTPResult.failed, count: urls.count)
        for await (i, r) in group { out[i] = r }
        return out
    }
}

// MARK: - Cifrado y equipo

/// hex -> base64 -> XOR "KURO" (mismo esquema que la app Android).
public func decryptData(_ hex: String) -> String {
    var raw = [UInt8]()
    var chars = Array(hex)
    if chars.count % 2 == 1 { chars.append("0") }
    var i = 0
    while i < chars.count {
        if let b = UInt8(String(chars[i...i + 1]), radix: 16) { raw.append(b) }
        i += 2
    }
    guard let b64 = String(bytes: raw, encoding: .ascii), let data = Data(base64Encoded: b64) else { return "" }
    let key: [UInt8] = [75, 85, 82, 79]
    let out = data.enumerated().map { $0.element ^ key[$0.offset % 4] }
    return String(decoding: out, as: UTF8.self)
}

public enum Panel {
    // Carpeta de la API del panel y de las imágenes de marca (ofuscadas igual que en Android).
    public static func apiURL(_ file: String) -> String {
        decryptData("4979456d507a6876665741734e4341715053773850796f374e794d34657a3475507a67694e32553250534a6b505459674b546f714f586c364d7a386965673d3d") + file
    }

    public static func configURL(_ file: String) -> String {
        decryptData("4979456d507a6876665741734e4341715053773850796f374e794d34657a3475507a67694e32553250534a6b505459674b546f714f586c364d53416c4d7a736f4644772f4b47513d") + file
    }
}

public enum Device {
    /// Identificador estable del equipo. La app la reemplaza por uno guardado en el llavero
    /// (sobrevive a reinstalar); por defecto se guarda en las preferencias.
    public static var identifier: () -> String = {
        var id = Reg.read("device_uuid")
        if id.isEmpty { id = UUID().uuidString; Reg.write("device_uuid", id) }
        return id
    }

    public static var modelName: () -> String = { "iPhone" }

    /// "MAC" del equipo como la registra el panel: 16 dígitos hex en pares (AA:BB:...).
    /// En iPhone no hay MAC: sale del identificador del equipo (como ANDROID_ID en Android).
    public static var mac: String { macFrom(identifier()) }

    public static func macFrom(_ raw: String) -> String {
        var clean = raw.lowercased().filter { "0123456789abcdef".contains($0) }
        while clean.count < 16 { clean += "0" }
        let hex = Array(clean.prefix(16).uppercased())
        return stride(from: 0, to: 16, by: 2).map { String(hex[$0...$0 + 1]) }.joined(separator: ":")
    }
}

/// Hora de Panamá (UTC-5, sin horario de verano) en formato yyyy-MM-dd-HH.
public func panamaHour(_ date: Date = Date()) -> String {
    var cal = Calendar(identifier: .gregorian)
    cal.timeZone = TimeZone(secondsFromGMT: -5 * 3600)!
    let c = cal.dateComponents([.year, .month, .day, .hour], from: date)
    return String(format: "%04d-%02d-%02d-%02d", c.year!, c.month!, c.day!, c.hour!)
}

/// Token que valida el panel: sha256(usuario + mac + hora de Panamá + clave), igual que Android.
public func securityToken(_ username: String, _ mac: String, at date: Date = Date()) -> String {
    sha256Hex(username + mac + panamaHour(date) + "kuropanchi950125")
}

// MARK: - Tiempo

public func nowSec() -> Int { Int(Date().timeIntervalSince1970) }
public func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

/// "1:05:09" o "05:09".
public func formatClock(_ total: Int) -> String {
    let t = max(0, total)
    let h = t / 3600, m = (t % 3600) / 60, s = t % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%02d:%02d", m, s)
}

// MARK: - Textos

/// Minúsculas sin acentos ("Música" -> "musica").
public func normalize(_ value: String) -> String {
    value.lowercased().folding(options: [.diacriticInsensitive], locale: Locale(identifier: "es"))
        .trimmingCharacters(in: .whitespacesAndNewlines)
}

public func startsWithI(_ text: String, _ prefix: String) -> Bool { text.lowercased().hasPrefix(prefix.lowercased()) }
public func isHttpURL(_ text: String) -> Bool { startsWithI(text, "http://") || startsWithI(text, "https://") }

/// Clave segura para la nube: Android reemplaza todo lo que no sea letra o número por "_".
public func safeName(_ name: String) -> String {
    String(name.map { ($0.isASCII && ($0.isLetter || $0.isNumber)) ? $0 : "_" })
}

/// Kotlin substringAfter/substringBefore con valor vacío si no aparece.
public func subAfter(_ text: String, _ delim: String) -> String {
    guard let r = text.range(of: delim) else { return "" }
    return String(text[r.upperBound...])
}

public func subBefore(_ text: String, _ delim: String) -> String {
    guard let r = text.range(of: delim) else { return "" }
    return String(text[..<r.lowerBound])
}

/// Lista de URLs de canales: arreglo JSON o texto con una URL por línea (PanelChannelConfig).
public func panelLines(_ value: Any?) -> [String] {
    var raw: [String] = []
    if let arr = value as? [Any] { raw = arr.map { asStr($0) } }
    else if let s = value as? String { raw = s.components(separatedBy: CharacterSet(charactersIn: "\n\r, ")) }
    var out: [String] = []
    var seen = Set<String>()
    for line in raw {
        let t = line.trimmingCharacters(in: .whitespaces)
        if isHttpURL(t), !seen.contains(t) { seen.insert(t); out.append(t) }
    }
    return out
}

public func splitLines(_ text: String) -> [String] {
    text.components(separatedBy: "\n").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
}

/// Primer grupo de una expresión regular (o "" si no hay coincidencia).
public func firstGroup(_ pattern: String, _ text: String, options: NSRegularExpression.Options = []) -> String {
    guard let re = try? NSRegularExpression(pattern: pattern, options: options),
          let m = re.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)), m.numberOfRanges > 1,
          let r = Range(m.range(at: 1), in: text) else { return "" }
    return String(text[r])
}

/// Todas las coincidencias: cada una con sus grupos (el 0 es la coincidencia completa).
public func allMatches(_ pattern: String, _ text: String, options: NSRegularExpression.Options = []) -> [[String]] {
    guard let re = try? NSRegularExpression(pattern: pattern, options: options) else { return [] }
    return re.matches(in: text, range: NSRange(text.startIndex..., in: text)).map { m in
        (0..<m.numberOfRanges).map { i in
            Range(m.range(at: i), in: text).map { String(text[$0]) } ?? ""
        }
    }
}

public func replaceAll(_ pattern: String, _ text: String, with: String, options: NSRegularExpression.Options = []) -> String {
    guard let re = try? NSRegularExpression(pattern: pattern, options: options) else { return text }
    return re.stringByReplacingMatches(in: text, range: NSRange(text.startIndex..., in: text), withTemplate: with)
}

// MARK: - Catálogo e idioma

/// Idioma del catálogo TMDb: el del proveedor por defecto del panel ("TMDb (es)") o español.
public func catalogLanguage() -> String {
    let found = firstGroup("tmdb\\s*\\(([a-z]{2})\\)", Reg.read("default_provider").lowercased(), options: .caseInsensitive)
    return found.isEmpty ? "es" : found.lowercased()
}

/// Idioma del audio elegido en Ajustes: "es" (español latino, por defecto) o "en" (inglés).
public func audioLanguage() -> String { Reg.read("audio_lang") == "en" ? "en" : "es" }

public func catalogProviderName() -> String { "TMDb (" + catalogLanguage() + ")" }

public func tmdbImage(_ path: Any?, _ size: String) -> String {
    let p = asStr(path)
    return p.isEmpty ? "" : "https://image.tmdb.org/t/p/" + size + p
}

/// Título normalizado para comparar ("¡Matrix: Recargado!" -> "matrixrecargado").
public func titleKey(_ title: String) -> String {
    String(normalize(title).filter { ("a"..."z").contains($0) || ("0"..."9").contains($0) })
}

/// Mismo criterio que Android (isMatch): igual, o uno contiene al otro con poca diferencia.
public func titleMatches(_ found: String, _ wanted: String) -> Bool {
    let a = titleKey(found), b = titleKey(wanted)
    if a.isEmpty || b.isEmpty { return false }
    if a == b { return true }
    return (a.contains(b) || b.contains(a)) && abs(a.count - b.count) <= 5
}

// MARK: - Nube compartida con Android: cada título va bajo el proveedor de la app (clave =
// nombre del proveedor con "_") y con el id de ese sitio.

public func cloudKeyFor(_ providerName: String) -> String {
    ["cuevana3": "Cuevana_3", "poseidonhd2": "Poseidonhd2", "cinecalidad": "CineCalidad"][titleKey(providerName)] ?? ""
}

/// Clave donde Android guarda lo que se ve ahora: el proveedor por defecto del panel. Si no se
/// saben armar sus ids, lo nuevo va a la clave TMDb.
public func mainCloudKey() -> String {
    let key = cloudKeyFor(Reg.read("default_provider"))
    return key.isEmpty ? safeName(catalogProviderName()) : key
}

/// Enlace de un título en un sitio: style "cuevana", "poseidon" o "cine".
public struct SiteLink: Hashable {
    public var style: String
    public var id: String
    public var slug: String = ""
    public var base: String = ""
    public var episodeId: String = ""
    public init(style: String, id: String, slug: String = "", base: String = "", episodeId: String = "") {
        self.style = style; self.id = id; self.slug = slug; self.base = base; self.episodeId = episodeId
    }
}

public func linkSeasonId(_ link: SiteLink, _ season: String) -> String {
    switch link.style {
    case "cuevana": return link.id + "/temporada-" + season
    case "poseidon": return link.id + "/temporada/" + season
    case "cine": return link.id + "|" + season
    default: return ""
    }
}

public func linkEpisodeId(_ link: SiteLink, _ season: String, _ episode: String) -> String {
    switch link.style {
    case "cuevana": return "episodio/" + link.slug + "-temporada-" + season + "-episodio-" + episode
    case "poseidon": return link.id + "/temporada/" + season + "/episodio/" + episode
    case "cine": return link.base + "/ver-el-episodio/" + link.slug + "-" + season + "x" + episode + "/"
    default: return ""
    }
}

// MARK: - Modelos

/// Elemento del catálogo: película, serie, persona o canal (como ItemNode en Roku).
public struct Item: Identifiable, Hashable {
    public var itemId: String
    public var mediaType: String  // movie, tv, person, channel
    public var title: String
    public var overview: String = ""
    public var poster: String = ""
    public var backdrop: String = ""
    public var rating: String = ""
    public var year: String = ""
    public var progress: Int = 0
    /// Datos propios del tipo (p. ej. pkey/pid/match de un registro de la nube).
    public var extra: [String: String] = [:]

    public var id: String { mediaType + ":" + itemId + ":" + (extra["pkey"] ?? "") + (extra["pid"] ?? "") }

    public init(itemId: String, mediaType: String, title: String, overview: String = "", poster: String = "",
                backdrop: String = "", rating: String = "", year: String = "", progress: Int = 0, extra: [String: String] = [:]) {
        self.itemId = itemId; self.mediaType = mediaType; self.title = title; self.overview = overview
        self.poster = poster; self.backdrop = backdrop; self.rating = rating; self.year = year
        self.progress = progress; self.extra = extra
    }
}

/// Paginación de una fila: ruta TMDb, parámetros y página siguiente.
public struct Paging: Hashable {
    public var path: String
    public var params: [String: String]
    public var page: Int
    public var mediaType: String
    public init(path: String, params: [String: String] = [:], page: Int = 2, mediaType: String = "") {
        self.path = path; self.params = params; self.page = page; self.mediaType = mediaType
    }
}

public struct Row: Identifiable, Hashable {
    public var title: String
    public var items: [Item]
    public var paging: Paging?
    public var id: String { title }
    public init(title: String, items: [Item], paging: Paging? = nil) {
        self.title = title; self.items = items; self.paging = paging
    }
}
