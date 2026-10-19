// Port de tests/cloud_test.brs del canal de Roku: nube compartida con Android.
import XCTest
@testable import VODCore

final class CloudTests: XCTestCase {
    var dir: URL!

    override func setUp() {
        dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("cloudtests-" + UUID().uuidString)
        Paths.root = dir
        Reg.store = UserDefaults(suiteName: "cloudtests")!
        Reg.clearSession()
        Reg.write("default_provider", "Cuevana 3")
    }

    override func tearDown() {
        Reg.clearSession()
        try? FileManager.default.removeItem(at: dir)
    }

    /// Documento como lo sube Android (proveedor Cuevana 3, ids del sitio).
    func androidDoc() -> JSON {
        jsonOf("""
        {"Cuevana_3": {
          "movies": {"ver-pelicula/matrix": {"id": "ver-pelicula/matrix", "title": "Matrix", "isFavorite": true, "favoritedAtMillis": 1700000000000}},
          "tv_shows": {"ver-serie/the-boys": {"id": "ver-serie/the-boys", "title": "The Boys", "lastPlayedAtMillis": 1700000000001, "isWatching": true}},
          "episodes": {"episodio/the-boys-temporada-1-episodio-2": {"id": "episodio/the-boys-temporada-1-episodio-2", "number": 2,
            "tvShow": {"id": "ver-serie/the-boys"}, "season": {"number": 1},
            "watchHistory": {"lastPlaybackPositionMillis": 600000, "durationMillis": 3600000, "lastEngagementTimeUtcMillis": 1700000000001}}}
        },
        "_profile": {"name": "x"}}
        """) as! JSON
    }

    let boysLinks = ["Cuevana_3": SiteLink(style: "cuevana", id: "ver-serie/the-boys", slug: "the-boys")]

    func testKeys() {
        XCTAssertEqual(mainCloudKey(), "Cuevana_3")
        XCTAssertEqual(cloudKeyFor("PoseidonHD2"), "Poseidonhd2")
    }

    func testLocate() {
        let doc = UserData.normalize(androidDoc())
        XCTAssertNil(doc["_profile"])

        // Película de TMDb sin enlaces: se encuentra el registro de Android por el título.
        let loc = UserData.locate(doc, "movies", Item(itemId: "603", mediaType: "movie", title: "Matrix"), [:])
        XCTAssertEqual(loc.key + " " + loc.id, "Cuevana_3 ver-pelicula/matrix")

        // Serie con enlaces: registro de Android y episodio existente (serie + temporada + número).
        let show = UserData.locate(doc, "tv_shows", Item(itemId: "76479", mediaType: "tv", title: "The Boys"), boysLinks)
        XCTAssertEqual(show.id, "ver-serie/the-boys")
        let ep = UserData.locateEpisode(doc, show, 1, 2, "1234")!
        XCTAssertEqual(ep.id, "episodio/the-boys-temporada-1-episodio-2")
        let ep3 = UserData.locateEpisode(doc, show, 1, 3, "1235")!
        XCTAssertEqual(ep3.id, "episodio/the-boys-temporada-1-episodio-3")
        XCTAssertEqual(UserData.shared.resume(of: doc[ep.key]?["episodes"]?[ep.id]), 600)

        // Título nuevo con enlace al proveedor principal: va con el id del sitio.
        let nuevo = UserData.locate(doc, "movies", Item(itemId: "999", mediaType: "movie", title: "Otra"),
                                    ["Cuevana_3": SiteLink(style: "cuevana", id: "ver-pelicula/otra", slug: "otra")])
        XCTAssertEqual(nuevo.key + " " + nuevo.id, "Cuevana_3 ver-pelicula/otra")
        // Sin enlace: va a la clave TMDb.
        let sinEnlace = UserData.locate(doc, "movies", Item(itemId: "998", mediaType: "movie", title: "Nada"), [:])
        XCTAssertEqual(sinEnlace.key + " " + sinEnlace.id, "TMDb__es_ 998")
    }

    func testSiteIds() {
        XCTAssertEqual(linkSeasonId(SiteLink(style: "poseidon", id: "152483/the-boys"), "2"), "152483/the-boys/temporada/2")
        XCTAssertEqual(linkEpisodeId(SiteLink(style: "cine", id: "x", slug: "the-boys", base: "https://www.cinecalidad.am"), "1", "2"),
                       "https://www.cinecalidad.am/ver-el-episodio/the-boys-1x2/")
    }

    func testCamelCaseFields() {
        // El JSON que se sube lleva los nombres exactos que lee Android (Gson distingue mayúsculas).
        var rec = UserData.baseRecord("movies", Item(itemId: "", mediaType: "movie", title: "Matrix", rating: "7.4"), "ver-pelicula/matrix")
        rec["lastPlayedAtMillis"] = Int64(5)
        UserData.applyWatch(&rec, watched: false, history: ["lastPlaybackPositionMillis": Int64(1000)])
        let json = jsonText(rec)
        for field in ["isFavorite", "isWatched", "lastPlayedAtMillis", "watchHistory", "lastPlaybackPositionMillis"] {
            XCTAssertTrue(json.contains("\"" + field + "\""), field + " en " + json)
        }
        XCTAssertTrue(json.contains("\"isFavorite\":false"), json)
        UserData.applyWatch(&rec, watched: true, history: nil)
        XCTAssertTrue(jsonText(rec).contains("\"dayOfMonth\""))
    }

    func testMergeContinueFavorites() {
        let ud = UserData()
        ud.load(profileId: "t1")
        XCTAssertFalse(ud.mergeRemote(androidDoc()), "nada local que subir")
        XCTAssertFalse(ud.uploadPending)
        XCTAssertEqual(ud.continueWatching().count, 1)
        XCTAssertEqual(ud.favorites().map(\.title), ["Matrix"])
        XCTAssertEqual(ud.favorites().first?.extra, ["pkey": "Cuevana_3", "pid": "ver-pelicula/matrix", "match": "1"])

        let boys = Item(itemId: "76479", mediaType: "tv", title: "The Boys")
        XCTAssertEqual(ud.episodeRecord(boys, links: boysLinks, season: 1, number: 2, tmdbEpisodeId: "1234").map { ud.percent(of: $0) }, 16)

        // Progreso de un episodio nuevo: va al registro de Android con el id del sitio.
        ud.saveProgress(PlaybackRequest(mediaType: "tv", id: "76479", title: "The Boys", year: "2019", season: 1, episode: 3,
                                        episodeId: "1235", episodeTitle: "Ep 3", item: boys, links: boysLinks),
                        positionSec: 900, durationSec: 3600)
        XCTAssertTrue(ud.uploadPending)
        XCTAssertEqual(ud.lastEpisode(boys, links: boysLinks)?.season, 1)
        XCTAssertEqual(ud.lastEpisode(boys, links: boysLinks)?.episode, 3)
        let cw = ud.continueWatching()
        XCTAssertEqual(cw.count, 1)
        XCTAssertEqual(cw.first?.progress, 25)

        // Película nueva en favoritos y vista.
        let otra = Item(itemId: "999", mediaType: "movie", title: "Otra")
        ud.setFavorite(otra, links: [:], favorite: true)
        XCTAssertTrue(ud.isFavorite(otra, links: [:]))
        XCTAssertEqual(ud.favorites().count, 2)
        ud.setWatched(PlaybackRequest(mediaType: "movie", id: "999", title: "Otra", year: "", item: otra), watched: true)
        XCTAssertTrue(ud.movieWatched(otra, links: [:]))

        let up = ud.forUpload()
        XCTAssertNotNil((up["TMDb__es_"] as? JSON)?["movies"])
        let text = jsonText(up)
        for field in ["lastPlayedEpisodeId", "lastEngagementTimeUtcMillis", "durationMillis", "tvShow", "favoritedAtMillis", "watchedDate", "hourOfDay"] {
            XCTAssertTrue(text.contains("\"" + field + "\""), field)
        }
        // La nube vieja no tiene lo nuevo: hay que subir.
        XCTAssertTrue(ud.mergeRemote(androidDoc()))

        // Se guarda en el archivo del perfil.
        let again = UserData()
        again.load(profileId: "t1")
        XCTAssertEqual(again.favorites().count, 2)
        again.setFavorite(otra, links: [:], favorite: false)
        XCTAssertFalse(again.isFavorite(otra, links: [:]))
        XCTAssertEqual(again.movieResume(otra, links: [:]), 0)

        again.deleteAllFiles()
        XCTAssertFalse(Paths.names().contains { $0.hasPrefix("ud_") })
    }

    func testMergeRules() {
        let ud = UserData()
        ud.load(profileId: "t2")
        let item = Item(itemId: "5", mediaType: "movie", title: "Cinco")
        ud.setFavorite(item, links: [:], favorite: true)
        // Nube con registro más viejo: gana lo local y hay que subir.
        let old: JSON = ["TMDb__es_": ["movies": ["5": ["id": "5", "title": "Cinco", "isFavorite": false, "favoritedAtMillis": 1]]]]
        XCTAssertTrue(ud.mergeRemote(old))
        XCTAssertTrue(ud.isFavorite(item, links: [:]))
        // Nube más nueva: gana la nube.
        let newer: JSON = ["TMDb__es_": ["movies": ["5": ["id": "5", "title": "Cinco", "isFavorite": false, "favoritedAtMillis": nowMs() + 60_000]]]]
        XCTAssertFalse(ud.mergeRemote(newer))
        XCTAssertFalse(ud.isFavorite(item, links: [:]))
    }
}
