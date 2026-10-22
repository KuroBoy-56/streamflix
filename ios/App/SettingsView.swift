// Ajustes (SettingsMobileFragment): perfil, idioma del audio, sincronizar, aviso del panel,
// datos del equipo y cerrar sesión. Estilo de Ajustes de iPhone (lista agrupada) en grises.
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
                        Text(session.brandName).font(.headline).foregroundColor(.white).lineLimit(2)
                        Text("Cuenta: " + Reg.read("alpha_token")).font(.subheadline).foregroundColor(Theme.textSecondary).lineLimit(1)
                    }
                }
                .padding(.vertical, 4)
                if toInt(Reg.read("profile_limit", "1")) >= 2 {
                    Button { session.switchProfile() } label: { row("Cambiar de perfil", "person.2.fill") }
                }
            }

            Section {
                Picker(selection: $audioLang) {
                    Text("Español latino").tag("es")
                    Text("Inglés").tag("en")
                } label: { row("Idioma del audio", "speaker.wave.2.fill") }
                .onChange(of: audioLang) { v in Reg.write("audio_lang", v) }
            } header: {
                Text("Reproducción")
            } footer: {
                Text(audioLang == "es"
                     ? "Se buscan servidores en español latino. Si un título no trae audio en español, se activan los subtítulos en español."
                     : "Se buscan servidores en inglés.")
            }

            Section("Mi lista") {
                Button {
                    session.syncDown()
                    synced = true
                } label: { row(synced ? "Sincronizado" : "Sincronizar ahora", "arrow.triangle.2.circlepath") }
            }

            if !session.settingsNote.isEmpty {
                Section("Aviso") { Text(session.settingsNote).foregroundColor(.white) }
            }

            Section("Equipo") {
                LabeledContent("MAC", value: Device.mac)
                LabeledContent("Versión", value: AppInfo.versionName)
            }

            Section {
                Button { confirmLogout = true } label: { row("Cerrar sesión", "rectangle.portrait.and.arrow.right") }
            }
        }
        .listStyle(.insetGrouped)
        .tint(Theme.accent)
        .navigationTitle("Ajustes")
        .navigationBarTitleDisplayMode(.inline)
        .id(session.brandTick)
        .alert("Cerrar sesión", isPresented: $confirmLogout) {
            Button("Cerrar sesión", role: .destructive) { session.logout() }
            Button("Cancelar", role: .cancel) {}
        } message: { Text("¿Deseas cerrar la sesión en este equipo?") }
    }

    /// Fila como en Ajustes de iPhone: icono blanco sobre un cuadrito gris y el texto en blanco.
    private func row(_ title: String, _ icon: String) -> some View {
        Label {
            Text(title).foregroundColor(.white).lineLimit(1)
        } icon: {
            Image(systemName: icon).font(.system(size: 14, weight: .semibold)).foregroundColor(.white)
                .frame(width: 28, height: 28)
                .background(RoundedRectangle(cornerRadius: 7).fill(Color(hex: 0x48484A)))
        }
    }
}
