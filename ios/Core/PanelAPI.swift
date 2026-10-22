// Llamadas al panel. Cada función equivale a una parte de la app Android (indicada en su
// comentario) y a Api.brs del canal de Roku: mismas URLs, parámetros, token y cabeceras.
import Foundation

public struct LoginResult {
    public var ok: Bool
    public var message: String = ""
    public var cached: Bool = false
    public var profileLimit: Int = 1
    /// Tipo para el reporte de errores al panel (vacío si no hay que reportar).
    public var report: String = ""
    public var detail: String = ""
}

public enum SecurityAction: Equatable {
    case none, ok, offline
    case logout(String)
}

public enum PanelAPI {
    /// Usuario y contraseña en cabeceras: el panel verifica que la nube/perfiles son de esa cuenta.
    static func syncHeaders() -> [String: String] {
        ["X-Sync-User": urlEnc(Reg.read("alpha_token")), "X-Sync-Pass": urlEnc(Reg.read("beta_token"))]
    }

    static func activeUserId() -> String { safeName(Reg.read("alpha_token")) }

    // MARK: Inicio de sesión (SyncGatewayActivity.executeGatewayHandshake)

    public static func login(user rawUser: String, pass rawPass: String, auto: Bool = false) async -> LoginResult {
        let user = rawUser.trimmingCharacters(in: .whitespaces)
        let pass = rawPass.trimmingCharacters(in: .whitespaces)
        let mac = Device.mac
        let url = Panel.apiURL("flixlat_api.php") + "?username=" + urlEnc(user) + "&password=" + urlEnc(pass)
            + "&mac=" + urlEnc(mac) + "&token=" + urlEnc(securityToken(user, mac))
        let r = await http(url, timeout: 10)
        if r.code != 200 {
            // Sin servidor: con sesión guardada se entra con lo que ya había (Android: enterWithCachedSession).
            let hasCache = !Reg.read("assigned_servers").isEmpty || !Reg.read("panel_channel_lines").isEmpty || !Reg.read("personal_provider_url").isEmpty
            if auto && hasCache { return LoginResult(ok: true, cached: true) }
            if r.code > 0 {
                return LoginResult(ok: false, message: "No se pudo conectar con el servidor (HTTP \(r.code)).", report: "Login.Servidor", detail: "HTTP \(r.code)")
            }
            return LoginResult(ok: false, message: "Error de conexión. Revisa tu internet e inténtalo de nuevo.", report: "Login.Conexion", detail: "Sin respuesta del servidor")
        }

        guard let json = jsonOf(r.body) as? JSON, let info = json["user_info"] as? JSON, toInt(info["auth"]) == 1 else {
            var message = "Acceso denegado."
            if let info = (jsonOf(r.body) as? JSON)?["user_info"] as? JSON, !panelText(info["message"]).isEmpty { message = panelText(info["message"]) }
            if auto { Reg.clearSession() }
            return LoginResult(ok: false, message: message)
        }

        let names = (json["assigned_servers"] as? [Any] ?? []).compactMap { ($0 as? JSON).map { panelText($0["name"]) } }.filter { !$0.isEmpty }
        let channelLines = panelLines(json["channel_lines"])
        let personal = panelLines(json["personal_provider_url"])
        if names.isEmpty && channelLines.isEmpty && personal.isEmpty {
            if auto { Reg.clearSession() }
            return LoginResult(ok: false, message: "Tu cuenta no tiene servidores asignados. Contacta a tu distribuidor.")
        }

        // Entrada por MAC (campos vacíos): el panel devuelve la cuenta vinculada.
        var accountName = panelText(info["username"])
        if accountName.isEmpty { accountName = user }
        var accountPass = pass
        if accountPass.isEmpty { accountPass = panelText(info["password"]) }
        let savedUser = user.isEmpty ? accountName : user
        let limit = min(5, max(1, toInt(json["profile_limit"], 1)))

        Reg.writeMany([
            "alpha_token": savedUser,
            "beta_token": accountPass,
            "custom_logo": panelText(json["custom_logo"]),
            "custom_background_url": panelText(json["custom_background"]),
            "custom_background_tv_url": panelText(json["custom_background_tv"]),
            "custom_splash_url": panelText(json["custom_splash"]),
            "tmdb_api_key": panelText(json["tmdb_api_key"]),
            "app_id": String(toInt(json["app_id"])),
            "assigned_servers": names.joined(separator: ","),
            "brand_name": panelText(json["app_name"]),
            "default_provider": panelText(json["default_provider"]),
            "panel_channel_lines": channelLines.joined(separator: "\n"),
            "personal_provider_url": personal.joined(separator: "\n"),
            "panel_default_channel_line": panelText(json["default_channel_line"]),
            "channel_lines_from": String(toInt(json["channel_lines_from"])),
            "settings_note": panelText(json["settings_note"]),
            "profile_limit": String(limit),
            "last_online_check": String(nowSec()),
        ])

        // El equipo guarda datos de una sola cuenta: si entra otra, se borra lo local (ya está en su nube).
        let owner = Reg.read("device_owner")
        if (!owner.isEmpty && owner.lowercased() != accountName.lowercased()) || (owner.isEmpty && !auto) {
            await MainActor.run { UserData.shared.deleteAllFiles() }
            Reg.write("active_profile", "00")
        }
        Reg.write("device_owner", accountName)
        return LoginResult(ok: true, profileLimit: limit)
    }

    // MARK: Verificación de la línea cada 30 min (StreamFlixApp.runSecurityChecks)

    public static func security() async -> SecurityAction {
        let user = Reg.read("alpha_token"), pass = Reg.read("beta_token")
        if user.isEmpty || pass.isEmpty { return .none }
        let mac = Device.mac
        let url = Panel.apiURL("check_security.php") + "?u=" + urlEnc(user) + "&p=" + urlEnc(pass)
            + "&v=\(AppInfo.versionCode)&mac=" + urlEnc(mac) + "&token=" + urlEnc(securityToken(user, mac))
        let r = await http(url, timeout: 6)
        guard r.code == 200, let json = jsonOf(r.body) as? JSON else {
            // Sin conexión con el panel: se permite seguir hasta 7 días desde la última verificación.
            let last = toInt(Reg.read("last_online_check", String(nowSec())))
            if nowSec() - last > 7 * 24 * 3600 {
                return .logout("No se pudo verificar tu cuenta en los últimos 7 días. Inicia sesión de nuevo.")
            }
            return .offline
        }
        let info = json["user_info"] as? JSON
        let authFailed = info != nil && toInt(info?["auth"], 1) == 0
        if asStr(json["status"]) == "banned" || authFailed {
            var message = "Tu cuenta fue desactivada o venció."
            if let info, !panelText(info["message"]).isEmpty { message = panelText(info["message"]) }
            return .logout(message)
        }
        // update_required no se usa: la actualización del panel es un APK solo para Android.
        Reg.write("last_online_check", String(nowSec()))
        return .ok
    }

    // MARK: Marca del panel cada 5 min (CloudSyncManager.applyPanelBranding)

    /// Devuelve true si cambió algo de la marca (logo, fondo, nombre, nota, canales).
    public static func branding() async -> Bool {
        let appId = toInt(Reg.read("app_id", "0"))
        var urls = [Panel.configURL("config.json") + "?cb=\(nowSec())"]
        if appId != 0 { urls.append(Panel.configURL("config_\(appId).json") + "?cb=\(nowSec())") }
        let results = await httpParallel(urls, timeout: 8)
        let master = results[0].code == 200 ? jsonOf(results[0].body) as? JSON : nil
        let own = appId != 0 && results[1].code == 200 ? jsonOf(results[1].body) as? JSON : nil
        // Si una de las dos no se pudo leer ahora, no se cambia nada (evita alternar marcas).
        guard let master, appId == 0 || own != nil else { return false }

        func field(_ key: String) -> String {
            let v = panelText(own?[key])
            return v.isEmpty ? panelText(master[key]) : v
        }
        var values: [String: String] = [
            "custom_background_url": field("custom_background"),
            "custom_background_tv_url": field("custom_background_tv"),
            "custom_splash_url": field("custom_splash"),
            "brand_name": field("app_name"),
            "settings_note": field("settings_note"),
            "custom_logo": field("custom_logo"),
        ]
        // Canales: cada administrador usa solo los suyos (0 = dueño, APP ID = administrador, -1 = ninguna).
        let from = toInt(Reg.read("channel_lines_from", "0"))
        if from == 0, master.keys.contains("channel_lines") { values["panel_channel_lines"] = panelLines(master["channel_lines"]).joined(separator: "\n") }
        if from != 0, from == appId, let own, own.keys.contains("channel_lines") { values["panel_channel_lines"] = panelLines(own["channel_lines"]).joined(separator: "\n") }
        if from < 0 { values["panel_channel_lines"] = "" }

        let changed = values.contains { Reg.read($0.key) != $0.value }
        if changed { Reg.writeMany(values) }
        return changed
    }

    // MARK: Aviso de vencimiento (ntf_api.php): Android lo abre en un WebView, igual que iOS.

    public static func noticeURL() -> URL? {
        let base = decryptData("4979456d507a6876665741734e4341715053773850796f374e794d34657a3475507a67694e32553250534a6b505459674b546f714f586c364d7a3869656a77374c516f7a50794a3749696337")
        if base.isEmpty { return nil }
        let sep = base.contains("?") ? "&" : "?"
        // Mismos parámetros que DeviceMac.noticeParams en Android: MAC y cuenta.
        return URL(string: base + sep + "mac=" + Device.mac + "&u=" + urlEnc(Reg.read("alpha_token")) + "&cb=\(nowMs())")
    }

    // MARK: Perfiles (ProfileManager -> profiles.php)

    /// GET sin cuerpo; con cuerpo hace POST (crear, renombrar, eliminar, cambiar avatar o PIN).
    public static func profiles(body: JSON? = nil) async -> Result<Account, CoreError> {
        if Reg.read("alpha_token").isEmpty || Reg.read("beta_token").isEmpty { return .failure(CoreError("auth")) }
        var headers = syncHeaders()
        var method = "GET"
        var text: String? = nil
        if let body {
            method = "POST"
            text = jsonText(body)
            headers["Content-Type"] = "application/json"
        }
        let r = await http(Panel.apiURL("profiles.php"), method: method, body: text, headers: headers, timeout: 10)
        guard let json = jsonOf(r.body) as? JSON else { return .failure(CoreError("http_\(r.code)")) }
        if asStr(json["status"]) != "success" {
            let message = asStr(json["message"])
            return .failure(CoreError(message.isEmpty ? "error" : message))
        }
        let account = parseAccount(json)
        if let data = try? JSONEncoder().encode(account) { Reg.write("profiles_cache", String(decoding: data, as: UTF8.self)) }
        Reg.write("profile_limit", String(account.limit))
        return .success(account)
    }

    /// Última lista de perfiles guardada (para mostrar algo sin conexión).
    public static func cachedAccount() -> Account? {
        Reg.read("profiles_cache").data(using: .utf8).flatMap { try? JSONDecoder().decode(Account.self, from: $0) }
    }

    static func parseAccount(_ json: JSON) -> Account {
        let limit = min(5, max(1, toInt(json["limit"], 1)))
        let profiles = (json["profiles"] as? [Any] ?? []).compactMap { $0 as? JSON }.map { p -> Profile in
            let id = asStr(p["id"])
            return Profile(id: id.isEmpty ? "00" : id, name: asStr(p["name"]), avatar: asStr(p["avatar"]), pin: asStr(p["pin"]))
        }
        let avatars = (json["avatars"] as? [Any] ?? []).map { asStr($0) }
        return Account(limit: limit, profiles: profiles, avatars: avatars)
    }

    // MARK: Nube del perfil (CloudSyncManager -> sync_userdata.php?u=<usuario>&p=<perfil>)

    static func syncURL(_ profileId: String) -> String {
        Panel.apiURL("sync_userdata.php") + "?u=" + activeUserId() + "&p=" + profileId
    }

    /// Documento completo de la nube (todas las claves de proveedor de Android, sin las "_...").
    /// La base de la fusión la guarda UserData.mergeRemote.
    public static func syncDown(_ profileId: String) async -> JSON? {
        if Reg.read("alpha_token").isEmpty { return nil }
        let r = await http(syncURL(profileId), headers: syncHeaders(), timeout: 10)
        if r.code != 200 { return nil }
        var doc: JSON = [:]
        if !r.body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            guard let json = jsonOf(r.body) as? JSON else { return nil }
            if asStr(json["status"]) != "empty" {
                for (key, value) in json where !key.hasPrefix("_") && value is JSON { doc[key] = value }
            }
        }
        return doc
    }

    /// Sube el documento completo (el servidor reemplaza todo): se arma con UserData.uploadBody
    /// sobre la nube leída justo antes.
    public static func syncUp(_ profileId: String, doc: JSON) async -> Bool {
        if Reg.read("alpha_token").isEmpty { return false }
        var headers = syncHeaders()
        headers["Content-Type"] = "application/json"
        let r = await http(syncURL(profileId), method: "POST", body: jsonText(doc), headers: headers, timeout: 10)
        // El servidor responde 200 también en errores: se revisa "status".
        let json = jsonOf(r.body) as? JSON
        return r.code == 200 && (json == nil || json?["status"] == nil || asStr(json?["status"]) == "success")
    }

    // MARK: Reporte de errores al panel (ErrorReporter -> log_error.php)

    public static func report(_ tipo: String, _ mensaje: String) async {
        let body: JSON = [
            "tipo": String(tipo.prefix(120)),
            "mensaje": String(mensaje.prefix(2000)),
            "stacktrace": "",
            "dispositivo": Device.modelName(),
            "version_app": AppInfo.versionName + " (iOS)",
            "app_name": "VOD Pro (iOS)",
            "usuario": Reg.read("alpha_token"),
            "proveedor": catalogProviderName(),
            "app_id": toInt(Reg.read("app_id", "0")),
        ]
        _ = await http(Panel.apiURL("log_error.php"), method: "POST", body: jsonText(body),
                       headers: ["Content-Type": "application/json; charset=UTF-8"], timeout: 8)
    }
}
