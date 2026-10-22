import XCTest
@testable import VODCore

final class BaseTests: XCTestCase {
    func testSha256() {
        XCTAssertEqual(sha256Hex(""), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        XCTAssertEqual(sha256Hex("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        let long = String(repeating: "a", count: 1000)
        XCTAssertEqual(sha256Hex(long), "41edece42d63e8d9bf515a9ba6932e1c20cbc9f5a5d134645adb5db1b9737ea3")
    }

    func testTokenMatchesPanelFormula() {
        // sha256(usuario + MAC + yyyy-MM-dd-HH en Panamá + clave), igual que master_api del panel.
        let date = Date(timeIntervalSince1970: 1_760_000_000) // 2025-10-09 08:53 UTC -> 03 en Panamá
        XCTAssertEqual(panamaHour(date), "2025-10-09-03")
        XCTAssertEqual(securityToken("demo", "AA:BB:CC:DD:EE:FF:00:11", at: date),
                       sha256Hex("demoAA:BB:CC:DD:EE:FF:00:112025-10-09-03kuropanchi950125"))
    }

    func testPanelUrlsDecrypt() {
        XCTAssertTrue(Panel.apiURL("x.php").hasPrefix("http"), Panel.apiURL("x.php"))
        XCTAssertTrue(Panel.configURL("config.json").hasSuffix("/config.json"))
    }

    func testPanelDomains() {
        let saved = Reg.store
        Reg.store = UserDefaults(suiteName: "domaintests")!
        Reg.clearSession()
        defer { Reg.clearSession(); Reg.store = saved }
        // Panel antiguo (sin "provider_domains") o valor que no es objeto: no se cambia nada.
        XCTAssertNil(PanelAPI.panelDomains(nil))
        XCTAssertNil(PanelAPI.panelDomains([Any]()))
        XCTAssertEqual(Sources.siteCuevana, "https://wv3.cuevana3.eu")

        Reg.writeMany(PanelAPI.panelDomains(["cuevana3": " nuevo.cuevana.com ", "poseidonhd2": ""] as JSON)!)
        XCTAssertEqual(Sources.siteCuevana, "https://nuevo.cuevana.com")
        XCTAssertEqual(Sources.sitePoseidon, "https://www.poseidonhd2.co")
        XCTAssertEqual(Sources.siteCineCalidad, "https://www.cinecalidad.am")

        // Vacío o ausente vuelve al dominio de la app.
        Reg.writeMany(PanelAPI.panelDomains(["cinecalidad": "cine.nuevo"] as JSON)!)
        XCTAssertEqual(Sources.siteCuevana, "https://wv3.cuevana3.eu")
        XCTAssertEqual(Sources.siteCineCalidad, "https://cine.nuevo")
        XCTAssertEqual(Sources.siteHost("vixsrc", "vixsrc.to"), "https://vixsrc.to")
        Reg.writeMany(PanelAPI.panelDomains(["vixsrc": "vix.nuevo"] as JSON)!)
        XCTAssertEqual(Sources.siteHost("vixsrc", "vixsrc.to"), "https://vix.nuevo")
    }

    func testMac() {
        XCTAssertEqual(Device.macFrom("6c5bf3a5-b2a5-4918-9aaa-000000000000"), "6C:5B:F3:A5:B2:A5:49:18")
        XCTAssertEqual(Device.macFrom("ab"), "AB:00:00:00:00:00:00:00")
    }

    func testText() {
        XCTAssertEqual(titleKey("¡Matrix: Recargado!"), "matrixrecargado")
        XCTAssertTrue(titleMatches("Matrix Recargado", "matrix recargado (2003)"))
        XCTAssertEqual(safeName("Cuevana 3"), "Cuevana_3")
        XCTAssertEqual(normalize("Música  "), "musica")
        XCTAssertEqual(formatClock(3909), "1:05:09")
        XCTAssertEqual(cloudKeyFor("Cuevana 3"), "Cuevana_3")
        XCTAssertEqual(asStr(jsonOf("{\"a\":5}") .flatMap { ($0 as? JSON)?["a"] }), "5")
        XCTAssertEqual(asStr(jsonOf("{\"a\":true}") .flatMap { ($0 as? JSON)?["a"] }), "true")
        XCTAssertEqual(panelLines("http://a\nhttp://a, https://b x"), ["http://a", "https://b"])
    }
}
