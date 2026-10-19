import XCTest
@testable import VODCore

final class CatalogTests: XCTestCase {
    func testRating() {
        XCTAssertEqual(Catalog.rating1(7.36), "7.4")
        XCTAssertEqual(Catalog.rating1("8"), "8.0")
        XCTAssertEqual(Catalog.rating1(0), "")
    }

    func testTmdbResults() {
        let json = jsonOf("""
        {"page":1,"results":[
          {"id":603,"media_type":"movie","title":"Matrix","overview":"Neo","poster_path":"/p.jpg","backdrop_path":"/b.jpg","vote_average":8.2,"release_date":"1999-03-31","popularity":50.5},
          {"id":1399,"name":"Juego de tronos","first_air_date":"2011-04-17","vote_average":0,"popularity":90},
          {"id":6384,"media_type":"person","name":"Keanu Reeves","profile_path":"/k.jpg","popularity":10},
          {"id":1,"media_type":"collection","name":"X"}
        ]}
        """)
        let items = Catalog.tmdbResults(json, "tv").map(\.item)
        XCTAssertEqual(items.map(\.itemId), ["603", "1399"])
        XCTAssertEqual(items[0], Item(itemId: "603", mediaType: "movie", title: "Matrix", overview: "Neo",
                                      poster: "https://image.tmdb.org/t/p/w342/p.jpg", backdrop: "https://image.tmdb.org/t/p/w1280/b.jpg",
                                      rating: "8.2", year: "1999"))
        XCTAssertEqual(items[1].mediaType, "tv")
        XCTAssertEqual(items[1].title, "Juego de tronos")
        XCTAssertEqual(items[1].year, "2011")
        XCTAssertEqual(items[1].rating, "")

        let withPeople = Catalog.tmdbResults(json, "tv", withPeople: true)
        XCTAssertEqual(withPeople.map(\.item.mediaType), ["movie", "tv", "person"])
        XCTAssertEqual(withPeople[2].item.poster, "https://image.tmdb.org/t/p/w342/k.jpg")
        XCTAssertEqual(Catalog.byPopularity(withPeople).map(\.itemId), ["1399", "603", "6384"])
        XCTAssertTrue(Catalog.tmdbResults(nil, "movie").isEmpty)
    }

    func testTmdbURL() {
        let key = Reg.read("tmdb_api_key")
        defer { Reg.write("tmdb_api_key", key) }
        Reg.write("tmdb_api_key", "abc")
        let url = Catalog.tmdbURL("discover/movie", ["with_genres": "28", "page": "2"])
        XCTAssertTrue(url.hasPrefix("https://api.themoviedb.org/3/discover/movie?language="))
        XCTAssertTrue(url.contains("&api_key=abc&page=2&with_genres=28"))
        XCTAssertNil(Catalog.tmdbHeaders()["Authorization"])
        Reg.write("tmdb_api_key", "eyJtoken")
        XCTAssertFalse(Catalog.tmdbURL("movie/1").contains("api_key"))
        XCTAssertEqual(Catalog.tmdbHeaders()["Authorization"], "Bearer eyJtoken")
    }
}
