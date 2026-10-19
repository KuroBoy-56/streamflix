// Port de las pruebas de canales de tests/selftest.brs del canal de Roku (sin red).
import XCTest
@testable import VODCore

final class LiveTests: XCTestCase {
    var dir: URL!

    override func setUp() {
        dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("livetests-" + UUID().uuidString)
        Paths.root = dir
        Reg.store = UserDefaults(suiteName: "livetests")!
        Reg.clearSession()
    }

    override func tearDown() {
        Reg.clearSession()
        try? FileManager.default.removeItem(at: dir)
    }

    let m3u = """
    #EXTM3U url-tvg="http://g.tv/epg.xml"
    #EXTINF:-1 tvg-id="espn.mx" tvg-logo="http://l/espn.png" group-title="Sports",MX: ESPN HD
    http://s/espn.m3u8
    #EXTINF:-1 group-title="Sports",ESPN 2
    http://s/espn2.m3u8
    #EXTINF:-1 group-title="Sports",Fox Sports
    http://s/fox.m3u8
    #EXTINF:-1 group-title="XXX Adultos",Canal X
    http://s/x.m3u8
    #EXTINF:-1 group-title="Sports",ESPN 2 copia
    http://s/espn2.m3u8
    """

    func testM3u() {
        var epg: [String] = []
        let list = Live.cleanChannels(Live.parseM3u(m3u, epg: &epg))
        XCTAssertEqual(list.count, 3, "sin adultos ni URLs repetidas")
        XCTAssertEqual(list[0].name, "ESPN HD")
        XCTAssertEqual(list[0].country, "México")
        XCTAssertEqual(list[0].group, "Deportes")
        XCTAssertEqual(list[0].logo, "http://l/espn.png")
        XCTAssertEqual(list[0].epgId, "espn.mx")
        XCTAssertEqual(epg.joined(separator: ","), "http://g.tv/epg.xml")
        XCTAssertEqual(Live.channelKey("ESPN (720p) HD"), "espn")

        var none: [String] = []
        let other = Live.cleanChannels(Live.parseM3u("#EXTM3U\n#EXTINF:-1,ESPN\nhttp://o/espn.m3u8\n#EXTINF:-1,ESPN 3\nhttp://o/espn3.m3u8\n", epg: &none))
        let merged = Live.mergeLines([list, other])
        // ESPN ya está en la base; ESPN 3 se suma a la categoría de la marca.
        XCTAssertEqual(merged.count, 4)
        XCTAssertEqual(merged[3].group, "Deportes")
    }

    func testPlainAndCountry() {
        let list = Live.cleanChannels(Live.parsePlain("Canal 5 (MX), http://a/c5.m3u8\nhttp://a/path/telefe.m3u8?t=1\nsin url\n"))
        XCTAssertEqual(list.map(\.name), ["Canal 5 (MX)", "telefe"])
        XCTAssertEqual(list[0].country, "México")
        XCTAssertEqual(list[1].url, "http://a/path/telefe.m3u8?t=1")
        XCTAssertEqual(Live.countryAlias("USA"), "us")
        XCTAssertEqual(Live.withCountry(LiveCh(name: "Canal 9", url: "http://x", group: "Argentina"), tvgCountry: "", tvgId: "").country, "Argentina")
        XCTAssertEqual(Live.withCountry(LiveCh(name: "Uno", url: "http://x"), tvgCountry: "xx;CO", tvgId: "").country, "Colombia")
    }

    func testGroups() {
        XCTAssertEqual(Live.cleanGroup("Animation;Kids", channelName: "X"), "Animación")
        XCTAssertEqual(Live.cleanGroup("Undefined", channelName: "CNN en Español"), "Noticias")
        XCTAssertEqual(Live.cleanGroup("Mexico", channelName: "Disney Channel"), "Infantil")
        XCTAssertEqual(Live.cleanGroup("variedades", channelName: "Canal 1"), "Variedades")
        XCTAssertEqual(Live.cleanGroup("", channelName: "Canal 1"), "Otros")
        XCTAssertTrue(Live.isAdult(normalize("Canal +18")))
        XCTAssertFalse(Live.isAdult(normalize("Canal 18 Noticias")))
    }

    func testFoldersAndSources() {
        Reg.write("panel_channel_lines", "http://b/2.m3u\nhttp://a/1.m3u")
        Reg.write("personal_provider_url", "http://a/1.m3u\nftp://x")
        Reg.write("panel_default_channel_line", "http://a/1.m3u")
        XCTAssertEqual(Live.sources(), ["http://a/1.m3u", "http://b/2.m3u"])

        var epg: [String] = []
        let base = Live.cleanChannels(Live.parseM3u(m3u, epg: &epg))
        var none: [String] = []
        let other = Live.cleanChannels(Live.parseM3u("#EXTM3U\n#EXTINF:-1,ESPN\nhttp://o/espn.m3u8\n", epg: &none))
        let cache = LiveCache(key: "k", time: 0, channels: Live.mergeLines([base, other]),
                              alternates: ["espn": [LiveAlt(url: "http://s/espn.m3u8", ua: "", referer: ""), LiveAlt(url: "http://o/espn.m3u8", ua: "UA", referer: "")]],
                              epg: epg)
        let folders = Live.folders(cache)
        XCTAssertEqual(folders.map(\.title), ["Todos", "Deportes", "México"])
        XCTAssertEqual(folders.map(\.folderType), ["all", "category", "country"])
        let espn = folders[0].channels[0]
        XCTAssertEqual(espn.sources.map(\.url), ["http://s/espn.m3u8", "http://o/espn.m3u8"])
        XCTAssertEqual(espn.sources[1].headers, ["User-Agent": "UA"])
    }

    func testCacheIsUsed() async {
        Reg.write("panel_channel_lines", "http://a/1.m3u")
        var epg: [String] = []
        let cache = LiveCache(key: "http://a/1.m3u", time: nowSec() - 3600, channels: Live.cleanChannels(Live.parseM3u(m3u, epg: &epg)), alternates: [:], epg: epg)
        Paths.writeText(Live.cacheFile, String(decoding: try! JSONEncoder().encode(cache), as: UTF8.self))
        guard case .success(let res) = await Live.load(force: false) else { return XCTFail("sin caché") }
        XCTAssertTrue(res.stale)
        XCTAssertEqual(res.total, 3)
        XCTAssertEqual(res.epgSources, ["http://g.tv/epg.xml"])

        Reg.write("panel_channel_lines", "")
        guard case .failure(let err) = await Live.load(force: false) else { return XCTFail("sin líneas") }
        XCTAssertEqual(err.message, "No hay canales disponibles en tu línea.")
    }

    func testEpg() {
        XCTAssertEqual(Live.epgTime("20240101120000 +0100"), 1_704_106_800)
        XCTAssertEqual(Live.epgTime("20240101120000 -0500"), 1_704_128_400)
        XCTAssertEqual(Live.epgTime("20240101120000"), 1_704_110_400)
        XCTAssertEqual(Live.epgTime("2024"), 0)

        let now = 1_704_110_400
        let xml = """
        <tv><programme start="20240101130000 +0000" stop="20240101140000 +0000" channel="a.mx"><title lang="es">Después &amp; más</title></programme>
        <programme start="20240101110000 +0000" stop="20240101130000 +0000" channel="a.mx">
        <title>Ahora</title></programme>
        <programme start="20240101090000 +0000" stop="20240101100000 +0000" channel="a.mx"><title>Pasado</title></programme>
        <programme start="20240101110000 +0000" stop="20240101130000 +0000" channel="otro"><title>No</title></programme></tv>
        """
        var out: [String: [Programme]] = [:]
        Live.parseEpg(xml, wanted: ["a.mx"], now: now, into: &out)
        XCTAssertEqual(out["a.mx"]?.map(\.title), ["Después & más", "Ahora"])
        XCTAssertNil(out["otro"])
    }
}
