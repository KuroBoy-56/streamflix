// Inicio de sesión (activity_sync_gateway): tarjeta con barra roja, bienvenida, usuario,
// contraseña, botón y la MAC del equipo. Con los campos vacíos entra con la MAC vinculada.
import SwiftUI

struct LoginView: View {
    @EnvironmentObject var session: Session
    @State private var user = ""
    @State private var pass = ""
    @State private var showPass = false
    @State private var busy = false
    @State private var status = ""
    @FocusState private var focus: Field?
    enum Field { case user, pass }

    var body: some View {
        ZStack {
            Theme.navy.ignoresSafeArea()
            if !session.backgroundURL.isEmpty {
                RemoteImage(url: session.backgroundURL).opacity(0.6).ignoresSafeArea()
            }
            ScrollView {
                card.frame(maxWidth: 400).padding(.horizontal, 20).padding(.vertical, 40)
                    .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .onAppear { status = session.logoutMessage }
    }

    private var card: some View {
        VStack(spacing: 0) {
            Rectangle().fill(Theme.red).frame(height: 4).padding(.bottom, 22)
            BrandLogo(size: 84).padding(.bottom, 14)
            Text("Bienvenido").font(.system(size: 26, weight: .bold)).foregroundColor(.white)
            Text("Inicia sesión para continuar").font(.system(size: 16)).foregroundColor(Color(hex: 0xB4B9C8))
                .padding(.top, 4).padding(.bottom, 22)

            field(placeholder: "Usuario", text: $user, secure: false).focused($focus, equals: .user)
                .textContentType(.username).submitLabel(.next).onSubmit { focus = .pass }
            field(placeholder: "Contraseña", text: $pass, secure: !showPass).focused($focus, equals: .pass)
                .textContentType(.password).submitLabel(.go).onSubmit { submit() }
                .overlay(alignment: .trailing) {
                    Button { showPass.toggle() } label: {
                        Image(systemName: showPass ? "eye.slash" : "eye").foregroundColor(.white.opacity(0.7)).padding(.trailing, 16)
                    }
                }

            Button(action: submit) {
                HStack {
                    Text("Iniciar sesión").font(.system(size: 17)).foregroundColor(.white)
                    Spacer()
                    if busy { ProgressView().tint(.white) }
                }
                .padding(.horizontal, 18).frame(height: 52)
                .background(RoundedRectangle(cornerRadius: 12).fill(busy ? Theme.darkRed : Theme.red))
            }
            .disabled(busy)

            if !status.isEmpty {
                Text(status).font(.system(size: 14, weight: .bold)).foregroundColor(Color(hex: 0xFF5252))
                    .multilineTextAlignment(.center).padding(.top, 12)
            }

            Text("MAC: " + Device.mac).font(.system(size: 14)).foregroundColor(Color(hex: 0xE5E5E5))
                .textSelection(.enabled)
                .frame(maxWidth: .infinity).padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: 12).fill(Theme.field))
                .padding(.top, 22)
            Text("Campos vacíos: entra con la MAC vinculada").font(.system(size: 13)).foregroundColor(Color(hex: 0x8A8FA0))
                .padding(.top, 8)
        }
        .padding(.horizontal, 28).padding(.bottom, 24)
        .background(RoundedRectangle(cornerRadius: 20).fill(Color(hex: 0x121520, alpha: 0.95)))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(Color(hex: 0x262A38), lineWidth: 1))
    }

    private func field(placeholder: String, text: Binding<String>, secure: Bool) -> some View {
        let focused = (placeholder == "Usuario" && focus == .user) || (placeholder == "Contraseña" && focus == .pass)
        return Group {
            if secure { SecureField("", text: text, prompt: Text(placeholder).foregroundColor(Color(hex: 0x8A8FA0))) }
            else { TextField("", text: text, prompt: Text(placeholder).foregroundColor(Color(hex: 0x8A8FA0))) }
        }
        .textInputAutocapitalization(.never).autocorrectionDisabled()
        .foregroundColor(.white)
        .padding(.horizontal, 18).frame(height: 52)
        .background(RoundedRectangle(cornerRadius: 12).fill(Theme.field))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(focused ? Theme.red : Color(hex: 0x2E3242), lineWidth: focused ? 2 : 1))
        .padding(.bottom, 12)
    }

    private func submit() {
        guard !busy else { return }
        focus = nil
        busy = true
        status = ""
        Task {
            if let error = await session.login(user: user, pass: pass) { status = error }
            busy = false
        }
    }
}
