// En Vivo (LiveMobileFragment): carpetas por categoría o por país, canales con su guía y
// reproductor a pantalla completa con la lista de la carpeta para cambiar de canal.
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
    @State private var playing: LiveTarget?

    var body: some View {
        VStack(spacing: 0) {
            if let r = model.result {
                header(r)
                channelList
            } else if !model.error.isEmpty {
                MessageView(text: model.error) { Task { await model.load(force: true) } }
            } else {
                LoadingView()
            }
        }
        .background(Theme.background.ignoresSafeArea())
        .navigationTitle("En Vivo")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { SearchButton() }
        .task { if model.result == nil { await model.load() } }
        .refreshable { await model.load(force: true) }
        .fullScreenCover(item: $playing) { target in LivePlayerView(target: target, model: model) }
    }

    private func header(_ r: LiveResult) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("\(r.total) canales").font(.system(size: 13)).foregroundColor(Theme.textSecondary).padding(.horizontal, 12)
            if model.hasCountries {
                Picker("", selection: $model.mode) {
                    Text("Categorías").tag("category")
                    Text("Países").tag("country")
                }
                .pickerStyle(.segmented).padding(.horizontal, 12)
                .onChange(of: model.mode) { _ in model.folderIndex = 0 }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(model.folders.enumerated()), id: \.offset) { i, f in
                        Button { model.folderIndex = i } label: {
                            Text("\(f.title)  (\(f.channels.count))").font(.system(size: 14, weight: .semibold))
                                .foregroundColor(i == model.folderIndex ? .black : .white)
                                .padding(.horizontal, 14).padding(.vertical, 8)
                                .background(Capsule().fill(i == model.folderIndex ? Color.white : Color.black.opacity(0.2)))
                                .overlay(Capsule().stroke(Color.white.opacity(i == model.folderIndex ? 0 : 0.5), lineWidth: 1))
                        }
                    }
                }
                .padding(.horizontal, 12)
            }
        }
        .padding(.vertical, 10)
    }

    private var channelList: some View {
        List {
            if let folder = model.folder {
                ForEach(Array(folder.channels.enumerated()), id: \.element.id) { i, ch in
                    Button { playing = LiveTarget(folder: folder, index: i) } label: { channelRow(ch, number: i + 1) }
                        .listRowBackground(Theme.background)
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    private func channelRow(_ ch: Channel, number: Int) -> some View {
        HStack(spacing: 12) {
            Text("\(number)").font(.system(size: 13)).foregroundColor(Theme.textSecondary).frame(width: 30)
            RemoteImage(url: ch.logo, contentMode: .fit).frame(width: 64, height: 40)
                .background(RoundedRectangle(cornerRadius: 6).fill(Theme.card))
            VStack(alignment: .leading, spacing: 3) {
                Text(ch.name).font(.system(size: 15, weight: .semibold)).foregroundColor(.white).lineLimit(1)
                if let p = model.now(ch) {
                    Text("Ahora: " + p.title).font(.system(size: 12)).foregroundColor(Theme.textSecondary).lineLimit(1)
                } else if !ch.country.isEmpty && ch.country != ch.group {
                    Text(ch.country).font(.system(size: 12)).foregroundColor(Theme.textSecondary)
                }
            }
            Spacer()
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Reproductor de canales

struct LiveTarget: Identifiable {
    let id = UUID()
    var folder: LiveFolder
    var index: Int
}

@MainActor
final class LivePlayerModel: ObservableObject {
    @Published var index: Int
    @Published var status = ""
    let folder: LiveFolder
    let player = AVPlayer()
    private var sourceIndex = 0
    private var bag = Set<AnyCancellable>()

    init(target: LiveTarget) {
        folder = target.folder
        index = target.index
    }

    var channel: Channel { folder.channels[index] }

    func playChannel() {
        sourceIndex = 0
        playSource()
    }

    /// Si una señal falla pasa sola a la misma señal en otra línea.
    private func playSource() {
        let sources = channel.sources
        guard sourceIndex < sources.count, let url = URL(string: sources[sourceIndex].url) else {
            status = "Este canal no está disponible en este momento."
            return
        }
        status = "Cargando canal..."
        bag.removeAll()
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

    func zap(_ delta: Int) {
        let n = folder.channels.count
        guard n > 0 else { return }
        index = (index + delta + n) % n
        playChannel()
    }

    func stop() {
        player.pause()
        player.replaceCurrentItem(with: nil)
        bag.removeAll()
    }
}

struct LivePlayerView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var player: LivePlayerModel
    @ObservedObject var model: LiveModel
    @State private var showList = false

    init(target: LiveTarget, model: LiveModel) {
        _player = StateObject(wrappedValue: LivePlayerModel(target: target))
        _model = ObservedObject(wrappedValue: model)
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VideoPlayerController(player: player.player).ignoresSafeArea()
            if !player.status.isEmpty {
                VStack(spacing: 12) {
                    if player.status.hasPrefix("Cargando") { ProgressView().tint(.white) }
                    Text(player.status).foregroundColor(.white)
                }
                .padding(20).background(RoundedRectangle(cornerRadius: 14).fill(Color.black.opacity(0.7)))
            }
            VStack {
                HStack(spacing: 10) {
                    Button { player.stop(); dismiss() } label: { circleIcon("xmark") }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(player.channel.name).font(.system(size: 15, weight: .bold)).foregroundColor(.white).lineLimit(1)
                        Text("Canal \(player.index + 1) de \(player.folder.channels.count)" + (model.now(player.channel).map { " · " + $0.title } ?? ""))
                            .font(.system(size: 12)).foregroundColor(.white.opacity(0.8)).lineLimit(1)
                    }
                    Spacer()
                    Button { player.zap(-1) } label: { circleIcon("chevron.up") }
                    Button { player.zap(1) } label: { circleIcon("chevron.down") }
                    Button { showList = true } label: { circleIcon("list.bullet") }
                }
                .padding(.horizontal, 12).padding(.top, 8)
                Spacer()
            }
        }
        .statusBarHidden()
        .onAppear { player.playChannel() }
        .onDisappear { player.stop() }
        .sheet(isPresented: $showList) {
            NavigationStack {
                List {
                    ForEach(Array(player.folder.channels.enumerated()), id: \.element.id) { i, ch in
                        Button {
                            player.index = i
                            player.playChannel()
                            showList = false
                        } label: {
                            HStack {
                                RemoteImage(url: ch.logo, contentMode: .fit).frame(width: 48, height: 30)
                                Text(ch.name).foregroundColor(i == player.index ? Theme.red : .white)
                            }
                        }
                    }
                }
                .navigationTitle(player.folder.title)
                .navigationBarTitleDisplayMode(.inline)
            }
            .presentationDetents([.medium, .large])
        }
    }

    private func circleIcon(_ name: String) -> some View {
        Image(systemName: name).font(.system(size: 16, weight: .bold)).foregroundColor(.white)
            .frame(width: 40, height: 40).background(Circle().fill(Color.black.opacity(0.5)))
    }
}
