// Ajustes (SettingsMobileFragment): perfil, idioma del audio, sincronizar, aviso del panel,
// datos del equipo y cerrar sesión.
import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var session: Session
    @State private var audioLang = audioLanguage()
    @State private var confirmLogout = false
    @State private var synced = false

    var body: some View {
        List {
            Section {
                HStack(spacing: 14) {
                    BrandLogo(size: 54)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(session.brandName).font(.headline).foregroundColor(.white)
                        Text("Cuenta: " + Reg.read("alpha_token")).font(.subheadline).foregroundColor(Theme.textSecondary)
                    }
                }
                .padding(.vertical, 4)
                if toInt(Reg.read("profile_limit", "1")) >= 2 {
                    Button { session.switchProfile() } label: { Label("Cambiar de perfil", systemImage: "person.2.fill") }
                }
            }

            Section("Reproducción") {
                Picker(selection: $audioLang) {
                    Text("Español latino").tag("es")
                    Text("Inglés").tag("en")
                } label: { Label("Idioma del audio", systemImage: "speaker.wave.2.fill") }
                .onChange(of: audioLang) { v in Reg.write("audio_lang", v) }
                Text(audioLang == "es"
                     ? "Se buscan servidores en español latino. Si un título no trae audio en español, se activan los subtítulos en español."
                     : "Se buscan servidores en inglés.")
                    .font(.footnote).foregroundColor(Theme.textSecondary)
            }

            Section("Mi lista") {
                Button {
                    session.uploadNow()
                    session.syncDown()
                    synced = true
                } label: { Label(synced ? "Sincronizado" : "Sincronizar ahora", systemImage: "arrow.triangle.2.circlepath") }
            }

            if !session.settingsNote.isEmpty {
                Section("Aviso") { Text(session.settingsNote).foregroundColor(.white) }
            }

            Section("Equipo") {
                LabeledContent("MAC", value: Device.mac)
                LabeledContent("Versión", value: AppInfo.versionName)
            }

            Section {
                Button(role: .destructive) { confirmLogout = true } label: { Label("Cerrar sesión", systemImage: "rectangle.portrait.and.arrow.right") }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.background.ignoresSafeArea())
        .navigationTitle("Ajustes")
        .navigationBarTitleDisplayMode(.inline)
        .id(session.brandTick)
        .alert("Cerrar sesión", isPresented: $confirmLogout) {
            Button("Cerrar sesión", role: .destructive) { session.logout() }
            Button("Cancelar", role: .cancel) {}
        } message: { Text("¿Deseas cerrar la sesión en este equipo?") }
    }
}
