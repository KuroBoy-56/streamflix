// En Vivo como la versión de celular de Android (LiveBrowser sin TV): vista previa arriba y
// debajo carpetas (categorías o países) | canales; en horizontal o iPad, tres columnas.
// Tocar un canal lo reproduce en la vista previa; tocarlo otra vez (o la vista previa) abre
// el reproductor a pantalla completa con el panel de canales de la carpeta (LiveChannelsOverlay).
import SwiftUI
import AVKit
import Combine

@MainActor
final class LiveModel: ObservableObject {
    @Published var result: LiveResult?
    @Published var error = ""
    @Published var mode = "category"
    @Published var folderIndex = 0
    @Published var epg: [String: [Programme]] = [:]

    var hasCountries: Bool { (result?.folders.filter { $0.folderType == "country" }.count ?? 0) >= 2 }

    /// Carpetas del modo elegido: "Todos" y categorías, o países.
    var folders: [LiveFolder] {
        guard let r = result else { return [] }
        let wantCountry = hasCountries && mode == "country"
        return r.folders.filter { ($0.folderType == "country") == wantCountry }
    }

    var folder: LiveFolder? { folders.indices.contains(folderIndex) ? folders[folderIndex] : folders.first }

    func load(force: Bool = false) async {
        switch await Live.load(force: force) {
        case .success(let r):
            result = r
            error = ""
            if folderIndex >= folders.count { folderIndex = 0 }
            // Copia guardada vieja: se muestra ya y se actualiza aparte (como la caché de Android).
            if r.stale && !force { Task { await load(force: true) } }
            if !r.epgSources.isEmpty {
                let ids = Set(r.folders.flatMap { $0.channels.map(\.epgId) }.filter { !$0.isEmpty })
                epg = await Live.epg(sources: r.epgSources, ids: ids)
            }
        case .failure(let e):
            if result == nil { error = e.message }
        }
    }

    func now(_ ch: Channel) -> Programme? {
        let t = nowSec()
        return epg[ch.epgId]?.first { $0.start <= t && t < $0.stop }
    }

    func next(_ ch: Channel) -> Programme? {
        let t = nowSec()
        return epg[ch.epgId]?.first { $0.start > t }
    }
}

struct LiveView: View {
    @StateObject private var model = LiveModel()
    @StateObject private var live = LivePlayerModel()
    @State private var full = false

    private static let selectedFill = Color(hex: 0x2A2D3A)
    private static let chipText = Color(hex: 0xD8DCE8)

    var body: some View {
        GeometryReader { geo in
            if model.result != nil {
                browser(width: geo.size.width, wide: geo.size.width > geo.size.height)
            } else if !model.error.isEmpty {
                MessageView(text: model.error) { Task { await model.load(force: true) } }
            } else {
                LoadingView()
            }
        }
        .background(Theme.navy.ignoresSafeArea())
        .navigationTitle("En Vivo")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { SearchButton() }
        .task { if model.result == nil { await model.load() } }
        // Al volver a la pestaña se retoma el último canal en la vista previa (como Android).
        .onAppear { if live.channel != nil && live.player.currentItem == nil { live.playChannel() } }
        .onDisappear { if !full { live.stop() } }
        .fullScreenCover(isPresented: $full, onDismiss: { live.player.play() }) {
            LivePlayerView(live: live, model: model)
        }
    }

    /// Vertical: vista previa arriba y carpetas | canales debajo. Horizontal: tres columnas.
    private func browser(width: CGFloat, wide: Bool) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            if model.hasCountries {
                HStack(spacing: 8) {
                    chip("Categorías", model.mode != "country") { setMode("category") }
                    chip("Países", model.mode == "country") { setMode("country") }
                }
            }
            if wide {
                HStack(alignment: .top, spacing: 0) {
                    folderColumn.frame(width: 200)
                    divider.padding(.horizontal, 8)
                    channelColumn.frame(width: 280)
                    divider.padding(.leading, 8).padding(.trailing, 16)
                    preview(width: max(width - 24 - 522, 120), wide: true)
                    Spacer(minLength: 0)
                }
            } else {
                preview(width: width - 24, wide: false)
                HStack(spacing: 0) {
                    folderColumn.frame(width: max(width - 24 - 13, 0) * 0.42)
                    divider.padding(.horizontal, 6)
                    channelColumn
                }
            }
        }
        .padding(.horizontal, 12).padding(.top, 4).padding(.bottom, 8)
    }

    private func setMode(_ mode: String) {
        guard model.mode != mode else { return }
        model.mode = mode
        model.folderIndex = 0
    }

    private var divider: some View { Rectangle().fill(Color.white.opacity(0.12)).frame(width: 1) }

    private func chip(_ title: String, _ selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.system(size: 13, weight: selected ? .bold : .regular)).foregroundColor(Self.chipText)
                .padding(.horizontal, 16).padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: 12).fill(selected ? Self.selectedFill : Theme.field))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(hex: selected ? 0x4A5064 : 0x2E3242), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    // MARK: Carpetas y canales

    private var folderColumn: some View {
        ScrollView {
            LazyVStack(spacing: 3) {
                ForEach(Array(model.folders.enumerated()), id: \.offset) { i, f in
                    Button { model.folderIndex = i } label: {
                        HStack(spacing: 6) {
                            Text(f.title).font(.system(size: 14, weight: .bold)).lineLimit(1)
                            Spacer(minLength: 0)
                            Text("\(f.channels.count)").font(.system(size: 12))
                        }
                        .foregroundColor(i == model.folderIndex ? .white : Color.white.opacity(0.85))
                    }
                    .buttonStyle(LiveRowStyle(selected: i == model.folderIndex))
                }
            }
            .padding(.bottom, 24)
        }
    }

    private var channelColumn: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(model.folder?.title ?? "").font(.system(size: 15, weight: .bold)).foregroundColor(.white).lineLimit(1)
                .padding(.horizontal, 10).padding(.bottom, 8)
            ScrollView {
                LazyVStack(spacing: 3) {
                    if let folder = model.folder {
                        ForEach(Array(folder.channels.enumerated()), id: \.element.id) { i, ch in
                            channelRow(folder, i, ch)
                        }
                    }
                }
                .padding(.bottom, 24)
            }
            // Otra carpeta: la lista empieza arriba.
            .id(model.folder?.id ?? "")
            .refreshable { await model.load(force: true) }
        }
    }

    private func channelRow(_ folder: LiveFolder, _ i: Int, _ ch: Channel) -> some View {
        let playing = live.channel?.id == ch.id
        return Button {
            if playing && live.player.currentItem != nil { full = true } else { live.play(folder, i) }
        } label: {
            HStack(spacing: 0) {
                Text("\(i + 1)").font(.system(size: 12)).lineLimit(1).minimumScaleFactor(0.7).frame(minWidth: 30, alignment: .leading)
                RemoteImage(url: ch.logo, contentMode: .fit).frame(width: 40, height: 28).padding(.trailing, 10)
                Text(ch.name).font(.system(size: 14)).lineLimit(1)
                Spacer(minLength: 4)
                if playing { Text("●").font(.system(size: 12)).foregroundColor(Color(hex: 0xE8EAED)) }
            }
            .foregroundColor(playing ? .white : Color.white.opacity(0.85))
        }
        .buttonStyle(LiveRowStyle(selected: playing))
    }

    // MARK: Vista previa

    private func preview(width: CGFloat, wide: Bool) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack(alignment: .topLeading) {
                Color.black
                if let ch = live.channel {
                    // El logo se ve mientras carga la señal; luego aparece el video.
                    RemoteImage(url: ch.logo, contentMode: .fit).padding(.horizontal, 40).padding(.vertical, 30)
                        .opacity(live.ready ? 0 : 1)
                    PlayerLayerView(player: live.player).opacity(live.ready ? 1 : 0)
                }
                if live.loading { ProgressView().tint(.white).frame(maxWidth: .infinity, maxHeight: .infinity) }
                if live.ready {
                    Text("EN VIVO").font(.system(size: 11, weight: .bold)).foregroundColor(Theme.navy)
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(RoundedRectangle(cornerRadius: 4).fill(Color(hex: 0xE8EAED)))
                        .padding(12)
                }
            }
            .frame(width: width, height: width * 9 / 16)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .contentShape(Rectangle())
            .onTapGesture { if live.channel != nil { full = true } }
            .animation(.easeInOut(duration: 0.25), value: live.ready)

            Text(live.channel?.name ?? "Elige un canal para verlo aquí").font(.system(size: 17, weight: .bold))
                .foregroundColor(.white).lineLimit(1).padding(.top, 12)
            if wide, let f = live.folder {
                Text(f.title).font(.system(size: 13)).foregroundColor(Color.white.opacity(0.7)).lineLimit(1)
            }
            if live.channel != nil {
                Text(live.failed ? live.status : "Pulsa otra vez para ver en pantalla completa")
                    .font(.system(size: 12)).foregroundColor(Color.white.opacity(0.5)).lineLimit(2).padding(.top, 6)
            }
        }
        .frame(width: width, alignment: .leading)
    }
}

/// Fila de carpeta o canal (bg_live_row del celular): elegida en gris oscuro, sin rojo.
private struct LiveRowStyle: ButtonStyle {
    var selected = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .padding(.horizontal, 12).padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 10).fill(
                configuration.isPressed ? Color.white.opacity(0.2) : selected ? Color(hex: 0x2A2D3A) : Color.clear))
            .contentShape(Rectangle())
    }
}

/// Video sin controles para la vista previa (como el PlayerView sin controles de Android).
struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer

    final class LayerView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    func makeUIView(context: Context) -> LayerView {
        let view = LayerView()
        view.backgroundColor = .black
        // Los toques pasan a SwiftUI (tocar la vista previa abre la pantalla completa).
        view.isUserInteractionEnabled = false
        view.playerLayer.videoGravity = .resizeAspect
        view.playerLayer.player = player
        return view
    }

    func updateUIView(_ view: LayerView, context: Context) {
        if view.playerLayer.player !== player { view.playerLayer.player = player }
    }
}

// MARK: - Reproductor de canales (el mismo de la vista previa, a pantalla completa)

@MainActor
final class LivePlayerModel: ObservableObject {
    @Published private(set) var folder: LiveFolder?
    @Published private(set) var index = 0
    @Published var status = ""
    let player = AVPlayer()
    private var sourceIndex = 0
    private var bag = Set<AnyCancellable>()

    var channel: Channel? {
        guard let folder, folder.channels.indices.contains(index) else { return nil }
        return folder.channels[index]
    }

    var loading: Bool { status.hasPrefix("Cargando") }
    var failed: Bool { !status.isEmpty && !loading }
    var ready: Bool { channel != nil && status.isEmpty && player.currentItem != nil }

    func play(_ folder: LiveFolder, _ index: Int) {
        self.folder = folder
        self.index = index
        playChannel()
    }

    func playChannel() {
        sourceIndex = 0
        playSource()
    }

    /// Si una señal falla pasa sola a la misma señal en otra línea.
    private func playSource() {
        bag.removeAll()
        guard let channel else { return }
        let sources = channel.sources
        guard sourceIndex < sources.count, let url = URL(string: sources[sourceIndex].url) else {
            player.replaceCurrentItem(with: nil)
            status = "Este canal no carga ahora. Prueba otro."
            return
        }
        status = "Cargando canal..."
        let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": sources[sourceIndex].headers])
        let item = AVPlayerItem(asset: asset)
        item.publisher(for: \.status).receive(on: RunLoop.main).sink { [weak self] s in
            guard let self else { return }
            if s == .readyToPlay { self.status = ""; self.player.play() }
            if s == .failed { self.sourceIndex += 1; self.playSource() }
        }.store(in: &bag)
        player.replaceCurrentItem(with: item)
        player.play()
    }

    func stop() {
        player.pause()
        player.replaceCurrentItem(with: nil)
        bag.removeAll()
        status = ""
    }
}

struct LivePlayerView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var live: LivePlayerModel
    @ObservedObject var model: LiveModel
    @StateObject private var chrome = AutoHide()

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VideoPlayerController(player: live.player, onTap: { chrome.toggle() }, onSwipeDown: { dismiss() })
                .ignoresSafeArea()
            if !live.status.isEmpty {
                VStack(spacing: 12) {
                    if live.loading { ProgressView().tint(.white) }
                    Text(live.status).foregroundColor(.white).multilineTextAlignment(.center)
                }
                .padding(20).background(RoundedRectangle(cornerRadius: 14).fill(Color.black.opacity(0.7)))
            }
            // Cerrar, nombre y panel de canales: con los controles al tocar, y siempre mientras carga.
            if chrome.visible || !live.ready {
                ZStack(alignment: .topTrailing) {
                    topBar
                    channelPanel
                }
                .transition(.opacity)
            }
        }
        .statusBarHidden()
        .onAppear { chrome.show() }
    }

    private var topBar: some View {
        VStack {
            HStack(spacing: 10) {
                CloseButton { dismiss() }
                if let ch = live.channel {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(ch.name).font(.system(size: 15, weight: .bold)).foregroundColor(.white).lineLimit(1)
                        Text("Canal \(live.index + 1) de \(live.folder?.channels.count ?? 0)" + (model.now(ch).map { " · " + $0.title } ?? ""))
                            .font(.system(size: 12)).foregroundColor(Color.white.opacity(0.8)).lineLimit(1)
                    }
                    .shadow(color: .black, radius: 3)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12).padding(.top, 8)
            Spacer(minLength: 0)
        }
    }

    /// Panel lateral casi transparente con los canales de la carpeta (LiveChannelsOverlay del celular).
    @ViewBuilder private var channelPanel: some View {
        if let folder = live.folder {
            VStack(alignment: .leading, spacing: 0) {
                Text(folder.title).font(.system(size: 15, weight: .bold)).foregroundColor(.white).lineLimit(1)
                    .shadow(color: .black, radius: 3)
                    .padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 8)
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 0) {
                            ForEach(Array(folder.channels.enumerated()), id: \.element.id) { i, ch in
                                panelRow(folder, i, ch).id(i)
                            }
                        }
                        .padding(.bottom, 16)
                    }
                    .onAppear { proxy.scrollTo(live.index, anchor: .center) }
                }
            }
            .frame(width: 260)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color.black.opacity(0.15)))
            .padding(.top, 56).padding(.trailing, 8).padding(.bottom, 72)
        }
    }

    private func panelRow(_ folder: LiveFolder, _ i: Int, _ ch: Channel) -> some View {
        let current = i == live.index
        return Button {
            if current { chrome.hide() } else { live.play(folder, i); chrome.show() }
        } label: {
            HStack(spacing: 0) {
                Text("\(i + 1)").font(.system(size: 12)).foregroundColor(Color.white.opacity(0.7)).lineLimit(1)
                    .minimumScaleFactor(0.7).frame(width: 34, alignment: .trailing)
                RemoteImage(url: ch.logo, contentMode: .fit).frame(width: 40, height: 28).padding(.horizontal, 10)
                Text(ch.name).font(.system(size: 14, weight: current ? .bold : .regular)).foregroundColor(.white).lineLimit(1)
                Spacer(minLength: 0)
            }
            .shadow(color: .black, radius: 2)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(RoundedRectangle(cornerRadius: 6).fill(current ? Color.white.opacity(0.15) : Color.clear))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // Mientras se desliza la lista aparecen filas: los controles siguen visibles.
        .onAppear { chrome.show() }
    }
}
