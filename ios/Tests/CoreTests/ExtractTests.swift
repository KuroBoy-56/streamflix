import XCTest
@testable import VODCore

// Port de roku/tests/extract_test.brs y de las pruebas de pistas de selftest.brs (sin red).
final class ExtractTests: XCTestCase {
    func testUnpackAndGenericFile() {
        let packed = #"eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('0 1=\'2\';3.4({5:"6\/7.8"})',36,9,'var|x|hello|jwplayer|setup|file|https:\/\/cdn.example\/hls2|master|m3u8'.split('|'),0,{}))"#
        let out = Extract.unpackJs(packed)
        XCTAssertEqual(out, #"var x='hello';jwplayer.setup({file:"https:\/\/cdn.example\/hls2\/master.m3u8"})"#)
        let file = firstGroup("file\"?\\s*:\\s*\"([^\"]+)", out)
        XCTAssertEqual(Extract.videoResult(file, "https://host.tv/e/1").url, "https://cdn.example/hls2/master.m3u8")
        // Lista de patrones: hls2 primero, luego file/src con .m3u8 o .mp4.
        XCTAssertEqual(Extract.findVideo(#"var links={"hls4":"/b.m3u8","hls2":"https://a/x.m3u8"}"#), "https://a/x.m3u8")
        XCTAssertEqual(Extract.findVideo(#"sources:[{File: "//cdn.tv/v.mp4?x=1"}]"#), "//cdn.tv/v.mp4?x=1")
        XCTAssertNil(Extract.findVideo("<html></html>"))
        let video = Extract.videoResult("/v/master.m3u8", "https://host.tv/e/1")
        XCTAssertEqual(video.url, "https://host.tv/v/master.m3u8")
        XCTAssertEqual(video.format, "hls")
        XCTAssertEqual(video.headers, ["Referer": "https://host.tv/", "Origin": "https://host.tv", "User-Agent": browserUA])
    }

    func testVoeDecrypt() {
        let voe = Extract.voeDecrypt("CR1TH@$wEEI2_I4GSgqZ1SoKUp8Iy12EIgyn2goKKt3Fy1gCUkCBIOYMwAIF2EfETkFoSt1KUkMAzI9GKkb")
        XCTAssertEqual(asStr(voe?["source"]), "https://cdn.example/master.m3u8")
    }

    func testHostAndUnsupported() {
        XCTAssertEqual(Sources.hostOf("https://Voe.SX/e/abc"), "voe.sx")
        XCTAssertEqual(Sources.originOf("https://Voe.SX/e/abc"), "https://voe.sx")
        XCTAssertTrue(Sources.unsupported("Cuevana · filemoon"))
        XCTAssertFalse(Sources.unsupported("Cuevana · streamwish https://sw.tv/e/1"))
    }

    func testServerOrdering() {
        let found = [
            Server(name: "Cuevana · voe", url: "https://voe.sx/e/1", lang: "cast"),
            Server(name: "Cuevana · streamwish", url: "https://sw.tv/e/1", lang: "sub"),
            Server(name: "Cuevana · filemoon", url: "https://fm.tv/e/1", lang: "lat"),
            Server(name: "CineCalidad · dood", url: "https://dood.to/e/1", lang: "lat"),
        ]
        let vix = Server(name: "VixSrc", url: "vixsrc", lang: "multi")
        // VixSrc asignado: siempre al final.
        XCTAssertEqual(Sources.orderServers(found, lang: "es", vix: true).map(\.name),
                       ["CineCalidad · dood", "Cuevana · voe", "Cuevana · streamwish", "VixSrc"])
        XCTAssertEqual(Sources.orderServers(found, lang: "en", vix: true).map(\.name),
                       ["Cuevana · streamwish", "CineCalidad · dood", "Cuevana · voe", "VixSrc"])
        // VixSrc no asignado por el panel: no aparece.
        XCTAssertEqual(Sources.orderServers(found, lang: "es", vix: false).map(\.name),
                       ["CineCalidad · dood", "Cuevana · voe", "Cuevana · streamwish"])

        // Etiqueta visible: número e idioma, sin el sitio.
        XCTAssertEqual(Sources.label(0, found[3]), "Servidor 1 · Latino")
        XCTAssertEqual(Sources.label(1, found[0]), "Servidor 2 · Castellano")
        XCTAssertEqual(Sources.label(2, vix), "Servidor 3 · Multiaudio")
        XCTAssertEqual(Sources.label(3, found[1]), "Servidor 4 · Subtitulado")
    }

    func testAssignedSites() {
        // Solo los proveedores del panel (nombre normalizado).
        XCTAssertEqual(Sources.assignedSites("Cuevana 3,VixSrc"), ["cuevana", "vix"])
        XCTAssertEqual(Sources.assignedSites("PoseidonHD2, CineCalidad,TMDb (es)"), ["poseidon", "cine"])
        // Ninguno conocido o ninguno asignado: no se busca en ningún sitio.
        XCTAssertEqual(Sources.assignedSites("Pluto TV"), [])
        XCTAssertEqual(Sources.assignedSites(""), [])
    }

    func testSiteParsers() {
        let next = #"<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"movies":[{"TMDbId":"99","url":{"slug":"movies/otra"}},{"TMDbId":603,"url":{"slug":"movies/matrix"},"slug":{"name":"matrix"}}],"thisMovie":{"videos":{"latino":[{"cyberlocker":"voe","result":"https://a/player.php?h=1"}],"english":[{"cyberlocker":"dood","result":"https://b"}],"spanish":[]}}}}}</script>"#
        let found = Sources.nextSiteFind(next, "603", "movie")
        XCTAssertEqual(found, Sources.NextFound(path: "matrix", name: "matrix"))
        XCTAssertNil(Sources.nextSiteFind(next, "603", "tv"))
        var servers: [Server] = []
        Sources.nextSiteServers(next, "Cuevana", "https://c.eu", &servers)
        XCTAssertEqual(Set(servers), [Server(name: "Cuevana · voe", url: "https://a/player.php?h=1", referer: "https://c.eu/", lang: "lat"),
                                      Server(name: "Cuevana · dood", url: "https://b", referer: "https://c.eu/", lang: "sub")])

        let search = """
        <article id="post-1" class="item movies"><a href="https://www.cinecalidad.am/ver-pelicula/matrix/"><img alt="Matrix &amp; Co"></a><p>1999</p></article>
        """
        XCTAssertEqual(Sources.cineCalidadFind(search, "Matrix & Co", "2000", "movie"), "https://www.cinecalidad.am/ver-pelicula/matrix/")
        XCTAssertEqual(Sources.cineCalidadFind(search, "Matrix & Co", "2005", "movie"), "")
        let episodes = "<div class=\"numerando\">S1-E2</div>\n <div class=\"episodiotitle\"> <a href=\"https://x/ver-el-episodio/s-1x2/\">"
        XCTAssertEqual(Sources.cineCalidadEpisode(episodes, "1", "2"), "https://x/ver-el-episodio/s-1x2/")
        var cine: [Server] = []
        Sources.cineCalidadServers(#"<li id="a" class="dooplay_player_option on" data-option="https://Filemoon.sx/e/1"><li class="dooplay_player_option" data-option="https://youtube.com/x">"#, &cine)
        XCTAssertEqual(cine, [Server(name: "CineCalidad · filemoon.sx", url: "https://Filemoon.sx/e/1", referer: "https://www.cinecalidad.am/", lang: "lat")])

        let links = Sources.siteLinks(Sources.Found(cuevana: found, poseidon: found, cine: "https://www.cinecalidad.am/ver-serie/matrix/"), "tv")
        XCTAssertEqual(links["Cuevana_3"], SiteLink(style: "cuevana", id: "ver-serie/matrix", slug: "matrix"))
        XCTAssertEqual(links["Poseidonhd2"], SiteLink(style: "poseidon", id: "matrix"))
        XCTAssertEqual(links["CineCalidad"], SiteLink(style: "cine", id: "https://www.cinecalidad.am/ver-serie/matrix/", slug: "matrix", base: "https://www.cinecalidad.am"))
    }

    func testTracks() {
        XCTAssertEqual(Tracks.guessFormat("http://x/live/a/b/1.m3u8?t=1"), "hls")
        XCTAssertEqual(Tracks.guessFormat("http://x/v.MP4"), "mp4")
        XCTAssertEqual(Tracks.guessFormat("http://x/v.mpd"), "dash")

        // Audio: latino antes que castellano.
        let tracks = [(language: "eng", name: "English"), (language: "spa", name: "Español (Castellano)"), (language: "spa", name: "Español (Latino)")]
        XCTAssertEqual(Tracks.pickAudio(tracks, lang: "es"), 2)
        XCTAssertEqual(Tracks.pickAudio(tracks, lang: "en"), 0)
        XCTAssertNil(Tracks.pickAudio([(language: "ita", name: "Italiano")], lang: "es"))
        // Sin español: inglés antes que italiano.
        XCTAssertEqual(Tracks.pickAudio(["it", "en"], lang: "es"), 1)
        XCTAssertEqual(Tracks.pickAudio([(language: "ita", name: "Italiano"), (language: "spa", name: "Español"), (language: "eng", name: "English")], lang: "es"), 1)
        XCTAssertEqual(Tracks.pickAudio(["en", "es-ES", "es-419"], lang: "es"), 2)
        XCTAssertEqual(Tracks.pickSubtitle([(language: "spa", name: "a Spanish Forced"), (language: "spa", name: "b Spanish")]), 1)
        XCTAssertEqual(Tracks.pickSubtitle(["en", "es"]), 1)

        XCTAssertEqual(Tracks.label(language: "spa", name: "Spanish (Latino)"), "Español latino")
        XCTAssertEqual(Tracks.label(language: "es-419", name: ""), "Español latino")
        XCTAssertEqual(Tracks.label(language: "spa", name: "Castellano"), "Español (España)")
        XCTAssertEqual(Tracks.label(language: "eng", name: "English"), "Inglés")
        XCTAssertEqual(Tracks.label(language: "", name: "Audio 2"), "Audio 2")
    }
}
