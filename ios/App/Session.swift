// Sesión de la app (MainScene de Roku / StreamFlixApp + MainMobileActivity de Android):
// inicio de sesión, perfil activo, verificación cada 30 min, marca cada 5 min, aviso de
// vencimiento y nube del perfil.
import SwiftUI
import UIKit
import Security

@MainActor
final class Session: ObservableObject {
    enum Screen { case login, profiles, main }

    @Published var screen: Screen = .login
    @Published var profileId = "00"
    @Published var brandTick = 0
    @Published var dataTick = 0
    @Published var logoutMessage = ""
    @Published var showNotice = false
    @Published var autoLoggingIn = false

    private var started = false
    private var timers: [Timer] = []
    private var uploadTask: Task<Void, Never>?
    private var syncChain: Task<Void, Never>?
    private var lastPull = Date.distantPast
    private var observer: NSObjectProtocol?

    init() {
        let deviceId = Keychain.deviceIdentifier()
        Device.identifier = { deviceId }
        Device.modelName = { "\(UIDevice.current.model) (iOS \(UIDevice.current.systemVersion))" }
        observer = NotificationCenter.default.addObserver(forName: UserData.changed, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.onUserDataChanged() }
        }
        if Reg.isLoggedIn {
            autoLoggingIn = true
            Task { await autoLogin() }
        }
    }

    // MARK: Marca del distribuidor

    var brandName: String { Reg.read("brand_name").isEmpty ? "VOD Pro" : Reg.read("brand_name") }
    var logoURL: String { Reg.read("custom_logo") }
    var backgroundURL: String { Reg.read("custom_background_url") }
    var settingsNote: String { Reg.read("settings_note") }

    /// Icono del administrador según el APP ID del login (como los activity-alias ".Admin<APP ID>"
    /// de Android): "AppIcon<APP ID>" si la app lo trae; si no, el icono principal.
    private func applyAppIcon() {
        let app = UIApplication.shared
        guard app.supportsAlternateIcons else { return }
        let info = Bundle.main.infoDictionary ?? [:]
        let sets = [info["CFBundleIcons"], info["CFBundleIcons~ipad"]].compactMap { $0 as? [String: Any] }
        let name = "AppIcon" + Reg.read("app_id")
        let bundled = sets.contains(where: { ($0["CFBundleAlternateIcons"] as? [String: Any])?[name] != nil })
        let wanted: String? = bundled ? name : nil
        if app.alternateIconName != wanted { app.setAlternateIconName(wanted) { _ in } }
    }

    // MARK: Inicio de sesión

    private func autoLogin() async {
        let r = await PanelAPI.login(user: Reg.read("alpha_token"), pass: Reg.read("beta_token"), auto: true)
        autoLoggingIn = false
        if r.ok { afterLogin() } else { logoutMessage = r.message; screen = .login }
    }

    func login(user: String, pass: String) async -> String? {
        let r = await PanelAPI.login(user: user, pass: pass)
        if !r.report.isEmpty { Task { await PanelAPI.report(r.report, r.detail) } }
        guard r.ok else { return r.message }
        afterLogin()
        return nil
    }

    /// Cuenta con 2 o más perfiles: "¿Quién está viendo?". Con 1 se entra con el perfil principal.
    func afterLogin() {
        brandTick += 1
        logoutMessage = ""
        applyAppIcon()
        // Marca del administrador (config.json + config_<APP ID>.json) antes de elegir perfil.
        Task { await runBranding() }
        if toInt(Reg.read("profile_limit", "1")) >= 2 { screen = .profiles } else { enterProfile("00") }
    }

    func switchProfile() { screen = .profiles }

    func enterProfile(_ id: String) {
        // Lo pendiente se sube a la nube del perfil que se deja.
        if started && profileId != id { uploadNow() }
        profileId = id
        Reg.write("active_profile", id)
        UserData.shared.uploadPending = false
        UserData.shared.load(profileId: id)
        screen = .main
        syncDown()
        if !started {
            started = true
            startTimers()
            Task { await runSecurity() }
            // El aviso de vencimiento se pide tras el splash, como en Android.
            Task {
                try? await Task.sleep(nanoseconds: 2_300_000_000)
                if started, PanelAPI.noticeURL() != nil { showNotice = true }
            }
        }
    }

    func logout(_ message: String = "") {
        timers.forEach { $0.invalidate() }
        timers = []
        uploadTask?.cancel()
        started = false
        Reg.clearSession()
        profileId = "00"
        UserData.shared.uploadPending = false
        UserData.shared.load(profileId: "00")
        brandTick += 1
        logoutMessage = message
        screen = .login
    }

    // MARK: Verificaciones periódicas

    private func startTimers() {
        timers = [
            Timer.scheduledTimer(withTimeInterval: 1800, repeats: true) { [weak self] _ in
                Task { @MainActor in
                    await self?.runSecurity()
                    if UserData.shared.uploadPending { self?.uploadNow() }
                }
            },
            Timer.scheduledTimer(withTimeInterval: 300, repeats: true) { [weak self] _ in
                Task { @MainActor in await self?.runBranding() }
            },
        ]
    }

    private func runSecurity() async {
        let action = await PanelAPI.security()
        guard started else { return }
        if case .logout(let message) = action { logout(message) }
    }

    private func runBranding() async {
        if await PanelAPI.branding() { brandTick += 1 }
    }

    // MARK: Nube del perfil (fusión de tres vías: siempre se lee la nube justo antes de subir)

    /// Lee la nube del perfil activo, la fusiona y sube solo si lo local tiene algo nuevo.
    func syncDown() { sync(profileId) }

    /// Inicio visible otra vez (o la app vuelve al frente): ver lo que otro equipo escribió,
    /// como mucho una vez cada 45 s.
    func refreshCloud() {
        guard started, Date().timeIntervalSince(lastPull) > 45 else { return }
        sync(profileId)
    }

    private func onUserDataChanged() {
        dataTick += 1
        guard UserData.shared.uploadPending else { return }
        uploadTask?.cancel()
        uploadTask = Task {
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            if !Task.isCancelled { uploadNow() }
        }
    }

    func uploadNow() {
        guard started else { return }
        sync(profileId)
    }

    /// Una sincronización a la vez (en orden), para que dos subidas no se pisen.
    private func sync(_ id: String) {
        lastPull = Date()
        let previous = syncChain
        syncChain = Task {
            await previous?.value
            await runSync(id)
        }
    }

    private func runSync(_ id: String) async {
        let ud = UserData.shared
        let active = { ud.profileId == id }
        if active() { ud.uploadPending = false }
        guard let remote = await PanelAPI.syncDown(id) else {
            if active() && started { ud.uploadPending = true }
            return
        }
        // Se cerró la sesión mientras se esperaba: nada que fusionar.
        if Reg.read("alpha_token").isEmpty { return }
        guard ud.mergeRemote(remote, profileId: id) else { return }
        let body = ud.uploadBody(remote, profileId: id)
        if await PanelAPI.syncUp(id, doc: body) {
            ud.uploaded(body, profileId: id)
        } else if active() && started {
            ud.uploadPending = true
        }
    }
}

/// Identificador del equipo guardado en el llavero: no cambia al reinstalar la app, así la
/// "MAC" que vincula el panel se mantiene (como ANDROID_ID).
enum Keychain {
    static func deviceIdentifier() -> String {
        let account = "device_uuid"
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrAccount as String: account,
                                    kSecReturnData as String: true]
        var out: AnyObject?
        if SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess, let data = out as? Data,
           let id = String(data: data, encoding: .utf8), !id.isEmpty {
            return id
        }
        let id = (UIDevice.current.identifierForVendor ?? UUID()).uuidString
        let add: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                  kSecAttrAccount as String: account,
                                  kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
                                  kSecValueData as String: Data(id.utf8)]
        SecItemAdd(add as CFDictionary, nil)
        return id
    }
}
