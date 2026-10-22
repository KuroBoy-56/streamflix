// "¿Quién está viendo?" (ProfilesActivity): elegir, crear, renombrar, avatar, PIN y eliminar.
import SwiftUI

struct ProfilesView: View {
    @EnvironmentObject var session: Session
    @State private var account = PanelAPI.cachedAccount() ?? Account(limit: 1, profiles: [], avatars: [])
    @State private var status = ""
    @State private var editing = false
    @State private var prompt: PromptKind?
    @State private var input = ""
    @State private var options: Profile?
    @State private var avatarFor: Pending?
    @State private var confirmDelete: Profile?

    enum PromptKind: Identifiable {
        case pin(Profile, then: () -> Void), add, rename(Profile), setPin(Profile)
        var id: String {
            switch self {
            case .pin(let p, _): return "pin" + p.id
            case .add: return "add"
            case .rename(let p): return "rename" + p.id
            case .setPin(let p): return "setpin" + p.id
            }
        }
    }

    struct Pending: Identifiable { var action: String; var id: String; var name: String }

    private let columns = [GridItem(.adaptive(minimum: 104), spacing: 16)]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    BrandLogo(size: 70).padding(.top, 30)
                    Text(avatarFor == nil ? "¿Quién está viendo?" : "Elige un avatar")
                        .font(.system(size: 28, weight: .bold)).foregroundColor(.white)
                        .multilineTextAlignment(.center).minimumScaleFactor(0.8)
                    if avatarFor != nil { avatarGrid } else { profileGrid }
                    if !status.isEmpty { Text(status).foregroundColor(Theme.textSecondary).font(.footnote).multilineTextAlignment(.center) }
                }
                .padding(.horizontal, 16)
            }
            .background(Theme.navy.ignoresSafeArea())
            .toolbar {
                if avatarFor == nil {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button(editing ? "Listo" : "Editar") { editing.toggle() }.foregroundColor(.white)
                    }
                } else {
                    ToolbarItem(placement: .navigationBarLeading) {
                        Button("Cancelar") { avatarFor = nil }.foregroundColor(.white)
                    }
                }
            }
        }
        .task { await reload(nil) }
        .alert(promptTitle, isPresented: Binding(get: { prompt != nil }, set: { if !$0 { prompt = nil } })) {
            TextField(promptPlaceholder, text: $input).keyboardType(isPinPrompt ? .numberPad : .default)
            Button("Aceptar") { submitPrompt() }
            Button("Cancelar", role: .cancel) { prompt = nil }
        }
        .confirmationDialog(options?.name ?? "", isPresented: Binding(get: { options != nil }, set: { if !$0 { options = nil } }), titleVisibility: .visible) {
            if let p = options { optionButtons(p) }
        } message: { Text("¿Qué deseas cambiar de este perfil?") }
        .alert("Eliminar perfil", isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } })) {
            Button("Eliminar", role: .destructive) { if let p = confirmDelete { call(["action": "delete", "id": p.id]) } }
            Button("Cancelar", role: .cancel) {}
        } message: { Text("Se borrarán el historial y los favoritos de \(confirmDelete?.name ?? "").") }
    }

    private var profileGrid: some View {
        LazyVGrid(columns: columns, spacing: 26) {
            ForEach(account.profiles) { p in
                Button { tap(p) } label: { ProfileTile(profile: p, editing: editing) }.buttonStyle(.plain)
            }
            if account.profiles.count < account.limit {
                Button { input = ""; prompt = .add } label: {
                    VStack(spacing: 8) {
                        RoundedRectangle(cornerRadius: 8).stroke(Color(hex: 0x808080), lineWidth: 2)
                            .overlay(Image(systemName: "plus").font(.system(size: 34)).foregroundColor(Color(hex: 0x808080)))
                            .frame(width: 100, height: 100)
                        Text("Agregar perfil").font(.system(size: 14)).foregroundColor(Color(hex: 0xB3B3B3)).lineLimit(1)
                    }
                }.buttonStyle(.plain)
            }
        }
    }

    private var avatarGrid: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 80), spacing: 14)], spacing: 14) {
            ForEach(account.avatars, id: \.self) { url in
                Button {
                    if let pending = avatarFor { apply(pending, avatar: url) }
                } label: {
                    RemoteImage(url: url).frame(width: 80, height: 80).clipShape(RoundedRectangle(cornerRadius: 8))
                }
            }
        }
    }

    @ViewBuilder private func optionButtons(_ p: Profile) -> some View {
        Button("Cambiar nombre") { input = p.name; prompt = .rename(p) }
        if !account.avatars.isEmpty { Button("Cambiar avatar") { avatarFor = Pending(action: "edit", id: p.id, name: "") } }
        Button("Poner PIN") { input = ""; prompt = .setPin(p) }
        if p.pin.count == 4 { Button("Quitar PIN") { call(["action": "edit", "id": p.id, "pin": ""]) } }
        if p.id != "00" { Button("Eliminar", role: .destructive) { confirmDelete = p } }
        Button("Cancelar", role: .cancel) {}
    }

    private func tap(_ p: Profile) {
        let open: () -> Void = { if editing { options = p } else { session.enterProfile(p.id) } }
        if p.pin.count == 4 { input = ""; prompt = .pin(p, then: open) } else { open() }
    }

    private var isPinPrompt: Bool {
        switch prompt { case .pin, .setPin: return true; default: return false }
    }
    private var promptTitle: String {
        switch prompt {
        case .pin(let p, _): return "PIN de " + p.name
        case .add: return "Nombre del perfil"
        case .rename: return "Nuevo nombre"
        case .setPin: return "PIN de 4 dígitos"
        case nil: return ""
        }
    }
    private var promptPlaceholder: String { isPinPrompt ? "PIN" : "Nombre" }

    private func submitPrompt() {
        let text = input.trimmingCharacters(in: .whitespaces)
        switch prompt {
        case .pin(let p, let then):
            if text == p.pin { then() } else { status = "PIN incorrecto" }
        case .add:
            guard !text.isEmpty else { break }
            let pending = Pending(action: "add", id: "", name: text)
            if account.avatars.isEmpty { apply(pending, avatar: "") } else { avatarFor = pending }
        case .rename(let p):
            if !text.isEmpty { call(["action": "edit", "id": p.id, "name": text]) }
        case .setPin(let p):
            if text.count == 4 { call(["action": "edit", "id": p.id, "pin": text]) } else { status = "El PIN debe tener 4 dígitos" }
        case nil: break
        }
        prompt = nil
    }

    private func apply(_ pending: Pending, avatar: String) {
        var body: JSON = ["action": pending.action]
        if pending.action == "add" { body["name"] = pending.name } else { body["id"] = pending.id }
        if !avatar.isEmpty { body["avatar"] = avatar }
        avatarFor = nil
        call(body)
    }

    private func call(_ body: JSON) {
        status = "Guardando..."
        Task { await reload(body) }
    }

    private func reload(_ body: JSON?) async {
        switch await PanelAPI.profiles(body: body) {
        case .success(let acc):
            account = acc
            status = ""
            // El perfil activo ya no existe: se vuelve al principal.
            if !acc.profiles.contains(where: { $0.id == session.profileId }) { session.profileId = "00" }
        case .failure(let e):
            status = "No se pudieron cargar los perfiles (\(e.message))."
            if account.profiles.isEmpty { account.profiles = [Profile(id: "00", name: "Principal", avatar: "", pin: "")] }
        }
    }
}

struct ProfileTile: View {
    let profile: Profile
    var editing = false

    var body: some View {
        VStack(spacing: 8) {
            ZStack {
                RoundedRectangle(cornerRadius: 8).fill(Color(hex: 0x333333))
                if profile.avatar.isEmpty {
                    Text(String(profile.name.prefix(1)).uppercased()).font(.system(size: 40, weight: .bold)).foregroundColor(.white)
                } else {
                    RemoteImage(url: profile.avatar)
                }
                if editing {
                    Color.black.opacity(0.6)
                    Image(systemName: "pencil").font(.system(size: 28)).foregroundColor(.white)
                }
            }
            .frame(width: 100, height: 100).clipShape(RoundedRectangle(cornerRadius: 8))
            Text(profile.name).font(.system(size: 14)).foregroundColor(Color(hex: 0xB3B3B3)).lineLimit(1)
            if profile.pin.count == 4 { Image(systemName: "lock.fill").font(.system(size: 11)).foregroundColor(Theme.textSecondary) }
        }
    }
}
