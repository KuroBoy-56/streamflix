// Obtiene el video (m3u8/mp4) de la página de un servidor (Extractor.extract en Android): port de
// Extract.brs. Genérico para los que guardan el enlace en JavaScript empaquetado (StreamWish,
// VidHide, Lulu, Dropload, Uqload, MixDrop, Goodstream...) y especiales para Voe, Dood,
// Streamtape y Ok.ru.
import Foundation

public enum Extract {
    /// ApiExtract: "vixsrc" va a Catalog.resolveVixSrc; el resto se lee de la página del servidor.
    public static func extract(_ server: Server, request: PlaybackRequest) async -> Result<Stream, CoreError> {
        var url = server.url
        if url == "vixsrc" { return await Catalog.resolveVixSrc(request) }
        var referer = server.referer

        // Puente de Cuevana/Poseidon (player.php?h=...): la página dice a qué servidor ir.
        if url.contains("/player.php?h=") || url.contains("mysync.mov/stream/") {
            let page = await http(url, headers: ["Referer": referer])
            let q = "['\"]"
            var target = firstGroup("(?:var\\s+url\\s*=\\s*" + q + "|location\\.href\\s*=\\s*" + q + "|location\\.replace\\(" + q + ")(https?://[^'\"]+)", page.body)
            if target.isEmpty { target = firstGroup("<iframe[^>]+src=" + q + "(https?://[^'\"]+)", page.body, options: .caseInsensitive) }
            if target.isEmpty { return .failure(CoreError("Servidor no disponible.")) }
            referer = Sources.originOf(url) + "/"
            url = target
        }

        let host = Sources.hostOf(url)
        if host.contains("voe") { return await extractVoe(url) }
        if host.contains("dood") || ["playmogo.com", "dsvplay.com", "myvidplay.com", "d000d.com", "do7go.com", "vide0.net", "mikaylaarealike.com"].contains(host) {
            return await extractDood(url)
        }
        if host.contains("streamtape") || host.contains("streamta.") { return await extractStreamtape(url) }
        if host == "ok.ru" || host == "m.ok.ru" { return await extractOkru(url) }
        if host.contains("mixdrop") || host.contains("mxdrop") { url = url.replacingOccurrences(of: "/f/", with: "/e/") }
        return await extractGeneric(url, referer)
    }

    static func videoResult(_ found: String, _ pageURL: String) -> Stream {
        var url = found
        let origin = Sources.originOf(pageURL)
        if url.hasPrefix("//") { url = "https:" + url }
        if url.hasPrefix("/") { url = origin + url }
        url = url.replacingOccurrences(of: "\\/", with: "/")
        return Stream(url: url, format: Tracks.guessFormat(url), headers: ["Referer": origin + "/", "Origin": origin, "User-Agent": browserUA])
    }

    static let packedMarker = "eval(function(p,a,c,k,e,d)"

    static func extractGeneric(_ url: String, _ referer: String) async -> Result<Stream, CoreError> {
        let page = await http(url, headers: referer.isEmpty ? [:] : ["Referer": referer])
        if page.code != 200 { return .failure(CoreError("El servidor no respondió (HTTP \(page.code)).")) }
        if let found = findVideo(page.body) { return .success(videoResult(found, url)) }
        return .failure(CoreError("No se encontró el video en el servidor."))
    }

    /// Desempaqueta cada bloque P.A.C.K.E.R. (también los anidados) y busca el enlace del video.
    static func findVideo(_ html: String) -> String? {
        var text = html
        var scan = text.range(of: packedMarker)?.lowerBound
        while let start = scan {
            let finish = text.range(of: "</script>", range: start..<text.endIndex)?.lowerBound ?? text.endIndex
            let finishOffset = text.utf16.distance(from: text.startIndex, to: finish)
            text += "\n" + unpackJs(String(text[start..<finish]))
            let from = String.Index(utf16Offset: finishOffset, in: text)
            scan = text.range(of: packedMarker, range: from..<text.endIndex)?.lowerBound
        }

        let q = "[\"']"
        let patterns = [
            q + "hls2" + q + "\\s*:\\s*" + q + "([^\"']+)",
            q + "hls4" + q + "\\s*:\\s*" + q + "([^\"']+)",
            "(?:file|source|src)" + q + "?\\s*[:=]\\s*" + q + "((?:https?:)?//[^\"']+\\.(?:m3u8|mp4)[^\"']*)",
            "wurl\\s*=\\s*" + q + "([^\"']+)",
            "(https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*)",
            "(?:file|src)" + q + "?\\s*[:=]\\s*" + q + "(/[^\"']+\\.m3u8[^\"']*)",
        ]
        for pattern in patterns {
            let found = firstGroup(pattern, text, options: .caseInsensitive)
            if !found.isEmpty { return found }
        }
        return nil
    }

    // MARK: - Desempaquetador P.A.C.K.E.R. (JsUnpacker de Android)

    static func unpackJs(_ js: String) -> String {
        let parts = allMatches("\\}\\s*\\('(.*)',\\s*(.*?),\\s*(\\d+),\\s*'(.*?)'\\.split\\('\\|'\\)", js, options: .dotMatchesLineSeparators).first ?? []
        if parts.count < 5 { return "" }
        let payload = parts[1].replacingOccurrences(of: "\\'", with: "'")
        var radix = toInt(parts[2].trimmingCharacters(in: .whitespacesAndNewlines))
        if radix <= 0 { radix = 36 }
        let symbols = parts[4].components(separatedBy: "|")

        let src = Array(payload.utf8)
        var out: [UInt8] = []
        var word: [UInt8] = []
        for i in 0...src.count {
            let b = i < src.count ? Int(src[i]) : -1
            let isWord = (48...57).contains(b) || (65...90).contains(b) || (97...122).contains(b) || b == 95
            if isWord {
                word.append(UInt8(b))
                continue
            }
            if !word.isEmpty {
                let index = unbaseWord(word, radix)
                let value = index >= 0 && index < symbols.count ? symbols[index] : ""
                out += value.isEmpty ? word : Array(value.utf8)
                word = []
            }
            if b >= 0 { out.append(UInt8(b)) }
        }
        return String(decoding: out, as: UTF8.self)
    }

    /// Número de la palabra en la base del empaquetado (-1 si no es válida).
    static func unbaseWord(_ word: [UInt8], _ radix: Int) -> Int {
        var value = 0
        for byte in word {
            let b = Int(byte)
            let d: Int
            switch b {
            case 48...57: d = b - 48
            case 97...122: d = b - 87
            case 65...90: d = radix > 36 ? b - 29 : b - 55
            default: return -1
            }
            if d >= radix { return -1 }
            value = value * radix + d
            if value > 1_000_000 { return -1 }
        }
        return value
    }

    // MARK: - Servidores especiales

    /// Voe: la página redirige a un espejo; el video va cifrado (rot13, base64, desplazamiento).
    static func extractVoe(_ url: String) async -> Result<Stream, CoreError> {
        var page = await http(url, headers: ["Referer": url])
        let reJson = "<script\\s+type=\"application/json\">(.*?)</script>"
        var encoded = firstGroup(reJson, page.body, options: .dotMatchesLineSeparators)
        var pageURL = url
        if encoded.isEmpty {
            let mirror = firstGroup("https://([a-zA-Z0-9.-]+)/", page.body)
            if mirror.isEmpty { return .failure(CoreError("Voe no respondió.")) }
            // Ruta de la URL original (desde la primera "/" después del host).
            let afterScheme = url.range(of: "://").map { url[$0.upperBound...] } ?? Substring(url)
            let path = afterScheme.firstIndex(of: "/").map { String(afterScheme[$0...]) } ?? ""
            pageURL = "https://" + mirror + path
            page = await http(pageURL, headers: ["Referer": url])
            encoded = firstGroup(reJson, page.body, options: .dotMatchesLineSeparators)
        }
        guard let data = voeDecrypt(encoded) else { return .failure(CoreError("No se pudo leer el video de Voe.")) }
        var source = asStr(data["source"])
        if source.isEmpty { source = asStr(data["direct_access_url"]) }
        if source.isEmpty { return .failure(CoreError("No se pudo leer el video de Voe.")) }
        return .success(videoResult(source, pageURL))
    }

    static func voeDecrypt(_ encoded: String) -> JSON? {
        var s = encoded.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("[\"") && s.count >= 4 { s = String(s.dropFirst(2).dropLast(2)) }
        let rot = s.utf8.map { c -> UInt8 in
            if (65...90).contains(c) { return (c - 65 + 13) % 26 + 65 }
            if (97...122).contains(c) { return (c - 97 + 13) % 26 + 97 }
            return c
        }
        s = String(decoding: rot, as: UTF8.self)
        for pattern in ["@$", "^^", "~@", "%?", "*~", "!!", "#&"] { s = s.replacingOccurrences(of: pattern, with: "_") }
        s = s.replacingOccurrences(of: "_", with: "")
        let step1 = base64Bytes(s)
        let shifted = step1.reversed().map { $0 &- 3 }
        let step2 = base64Bytes(String(decoding: shifted, as: UTF8.self))
        return jsonOf(String(decoding: step2, as: UTF8.self)) as? JSON
    }

    /// Base64 tolerante (sin relleno), como FromBase64String de Roku.
    static func base64Bytes(_ text: String) -> [UInt8] {
        var t = text.trimmingCharacters(in: CharacterSet(charactersIn: "="))
        if t.count % 4 != 0 { t += String(repeating: "=", count: 4 - t.count % 4) }
        return Data(base64Encoded: t, options: .ignoreUnknownCharacters).map { [UInt8]($0) } ?? []
    }

    /// DoodStream: /pass_md5/ da el inicio del enlace; se completa con 10 letras y el token.
    static func extractDood(_ url: String) async -> Result<Stream, CoreError> {
        let embed = url.replacingOccurrences(of: "/d/", with: "/e/")
        let page = await http(embed, headers: ["Referer": url])
        let md5Path = firstGroup("(/pass_md5/[^'\"]*)", page.body)
        if md5Path.isEmpty { return .failure(CoreError("DoodStream no respondió.")) }
        let base = Sources.originOf(embed)
        let prefix = await http(base + md5Path, headers: ["Referer": embed]).body.trimmingCharacters(in: .whitespacesAndNewlines)
        if !prefix.hasPrefix("http") { return .failure(CoreError("DoodStream no respondió.")) }
        let letters = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")
        let random = String((0..<10).map { _ in letters.randomElement()! })
        let final = prefix + random + "?token=" + (md5Path.components(separatedBy: "/").last ?? "")
        return .success(Stream(url: final, format: "mp4", headers: ["Referer": base + "/", "User-Agent": browserUA]))
    }

    static func extractStreamtape(_ url: String) async -> Result<Stream, CoreError> {
        let page = await http(url)
        guard let botlink = allMatches("getElementById\\('botlink'\\)\\.innerHTML\\s*=\\s*'([^']+)'\\s*\\+\\s*\\('([^']+)'\\)\\.substring\\((\\d+)\\)", page.body).first,
              botlink.count >= 4 else { return .failure(CoreError("Streamtape no respondió.")) }
        let params = String(botlink[2].dropFirst(toInt(botlink[3])))
        let final = "https://streamtape.com/get_video?id=" + firstGroup("id=([^&]+)", params) + "&expires=" + firstGroup("expires=([^&]+)", params)
            + "&ip=" + firstGroup("ip=([^&]+)", params) + "&token=" + firstGroup("token=([^&]+)", params) + "&stream=1"
        return .success(Stream(url: final, format: "mp4", headers: ["User-Agent": browserUA]))
    }

    /// Ok.ru: data-options trae los videos por calidad (y a veces un m3u8).
    static func extractOkru(_ url: String) async -> Result<Stream, CoreError> {
        let page = await http(url)
        let raw = firstGroup("data-options=\"([^\"]+)\"", page.body)
            .replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&amp;", with: "&")
        let failed = Result<Stream, CoreError>.failure(CoreError("Ok.ru no respondió."))
        guard let flashvars = (jsonOf(raw) as? JSON)?["flashvars"] as? JSON,
              let meta = jsonOf(asStr(flashvars["metadata"])) as? JSON else { return failed }
        let headers = ["Referer": "https://ok.ru/", "User-Agent": browserUA]
        let hls = asStr(meta["hlsManifestUrl"])
        if !hls.isEmpty { return .success(Stream(url: hls, format: "hls", headers: headers)) }
        let videos = ((meta["videos"] as? [Any]) ?? []).compactMap { $0 as? JSON }
        for quality in ["ultra", "quad", "full", "hd", "sd", "low", "lowest", "mobile"] {
            if let v = videos.first(where: { asStr($0["name"]) == quality && asStr($0["url"]).hasPrefix("http") }) {
                return .success(Stream(url: asStr(v["url"]), format: "mp4", headers: headers))
            }
        }
        return failed
    }
}
