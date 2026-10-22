// Reproductor (PlayerMobileFragment): búsqueda automática de servidores en el idioma de
// Ajustes, uno por uno; si un video falla se pasa al siguiente en el mismo punto. Guarda el
// progreso cada 15 s en la nube del perfil y pasa solo al siguiente episodio.
// El menú de audio y subtítulos es el propio de iPhone (botón de burbuja del reproductor).
import SwiftUI
import AVKit
import Combine

struct PlayerTarget: Identifiable {
    let id = UUID()
    var request: PlaybackRequest
    var startAt: Int
    var episodes: [Episode] = []
}

@MainActor
final class PlayerModel: ObservableObject {
    @Published var status = ""
    @Published var busy = true
    @Published var failed = false
    let player = AVPlayer()

    private(set) var request: PlaybackRequest
    private var episodes: [Episode]
    private var servers: [Server] = []
    private var serverIndex = -1
    private var serverLabel = ""
    private var resumeAt = 0
    private var lastSaved = 0
    private var audioChosen = false
    private var closing = false
    private var bag = Set<AnyCancellable>()
    private var timeObserver: Any?
    var onClose: () -> Void = {}

    init(target: PlayerTarget) {
        request = target.request
        episodes = target.episodes
        resumeAt = target.startAt
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 1, preferredTimescale: 1), queue: .main) { [weak self] _ in
            Task { @MainActor in self?.onTick() }
        }
        NotificationCenter.default.publisher(for: .AVPlayerItemDidPlayToEndTime)
            .receive(on: RunLoop.main)
            .sink { [weak self] note in
                guard let self, (note.object as? AVPlayerItem) === self.player.currentItem else { return }
                self.onFinished()
            }
            .store(in: &bag)
    }

    var title: String {
        request.mediaType == "movie" ? request.title
            : "\(request.title) · T\(request.season):E\(request.episode) \(request.episodeTitle)"
    }

    func start() {
        player.pause()
        lastSaved = 0
        servers = []
        serverIndex = -1
        failed = false
        setBusy("Buscando servidores...")
        Task {
            let res = await Sources.servers(request)
            guard !closing else { return }
            servers = res.servers
            // Se suman los ids encontrados sin perder los de la ficha (la nube usa los mismos registros).
            request.links.merge(res.links) { _, new in new }
            await tryServer(0)
        }
    }

    private func setBusy(_ text: String) {
        status = text
        busy = !text.isEmpty
    }

    private func tryServer(_ index: Int) async {
        player.pause()
        guard index < servers.count else {
            setBusy("")
            failed = true
            status = "No se encontró un servidor que funcione para este título."
            await PanelAPI.report("Reproduccion.Servidores", "\(title): \(servers.count) servidores probados")
            return
        }
        serverIndex = index
        let server = servers[index]
        // Nunca se muestra el nombre del proveedor: número de servidor e idioma ("Servidor 1 · Latino").
        serverLabel = Sources.label(index, server)
        setBusy(serverLabel)
        switch await Extract.extract(server, request: request) {
        case .failure:
            if !closing { await tryServer(index + 1) }
        case .success(let stream):
            guard !closing else { return }
            setBusy(serverLabel + "\nCargando video...")
            // Los servidores de los sitios ya vienen en su idioma: solo VixSrc elige audio/subtítulos.
            audioChosen = server.lang != "multi"
            play(stream)
        }
    }

    private func play(_ stream: Stream) {
        guard let url = URL(string: stream.url) else { Task { await tryServer(serverIndex + 1) }; return }
        let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": stream.headers])
        let item = AVPlayerItem(asset: asset)
        item.publisher(for: \.status)
            .receive(on: RunLoop.main)
            .sink { [weak self, weak item] status in
                guard let self, let item, item === self.player.currentItem else { return }
                switch status {
                case .readyToPlay:
                    self.setBusy("")
                    if self.resumeAt > 0 {
                        self.player.seek(to: CMTime(seconds: Double(self.resumeAt), preferredTimescale: 1))
                        self.resumeAt = 0
                    }
                    self.player.play()
                    Task { await self.chooseTracks(item) }
                case .failed:
                    // El servidor no reprodujo: el siguiente, desde donde iba.
                    let pos = Int(self.player.currentTime().seconds.isFinite ? self.player.currentTime().seconds : 0)
                    if pos > 5 { self.resumeAt = pos }
                    Task { await self.tryServer(self.serverIndex + 1) }
                default: break
                }
            }
            .store(in: &bag)
        player.replaceCurrentItem(with: item)
    }

    /// Audio según Ajustes: latino > castellano > inglés > otro (nunca se deja italiano u otro si
    /// hay latino o inglés). Si el audio elegido no es español, se activan subtítulos en español.
    private func chooseTracks(_ item: AVPlayerItem) async {
        guard !audioChosen else { return }
        audioChosen = true
        let asset = item.asset
        let lang = audioLanguage()
        var chosen = false
        var spanish = false
        if let audio = try? await asset.loadMediaSelectionGroup(for: .audible) {
            let tracks: [(language: String, name: String)] = audio.options.map { option in
                (language: option.extendedLanguageTag ?? option.locale?.identifier ?? "", name: option.displayName)
            }
            if let best = Tracks.pickAudio(tracks, lang: lang) {
                item.select(audio.options[best], in: audio)
                chosen = true
                spanish = Tracks.audioScore(tracks[best], lang: "es") >= 2
            }
        }
        guard let legible = try? await asset.loadMediaSelectionGroup(for: .legible) else { return }
        let codes = legible.options.map { $0.extendedLanguageTag ?? $0.locale?.identifier ?? "" }
        if lang == "es", !spanish, let sub = Tracks.pickSubtitle(codes) {
            item.select(legible.options[sub], in: legible)
        } else if chosen {
            item.select(nil, in: legible)
        }
    }

    private func onTick() {
        guard let item = player.currentItem, item.status == .readyToPlay else { return }
        let seconds = Int(player.currentTime().seconds)
        // El progreso se guarda cada 15 s (y al salir), como el historial de Android.
        if abs(seconds - lastSaved) >= 15 {
            lastSaved = seconds
            saveNow()
        }
    }

    func saveNow() {
        guard let item = player.currentItem, item.status == .readyToPlay else { return }
        let duration = item.duration.seconds
        guard duration.isFinite, duration > 0 else { return }
        UserData.shared.saveProgress(request, positionSec: Int(player.currentTime().seconds), durationSec: Int(duration))
    }

    private func onFinished() {
        if let d = player.currentItem?.duration.seconds, d.isFinite, d > 0 {
            UserData.shared.saveProgress(request, positionSec: Int(d), durationSec: Int(d))
        }
        // Siguiente episodio de la temporada.
        guard request.mediaType == "tv", let i = episodes.firstIndex(where: { $0.number == request.episode }), i + 1 < episodes.count else {
            close()
            return
        }
        let next = episodes[i + 1]
        request.episode = next.number
        request.episodeId = next.id
        request.episodeTitle = next.title
        request.episodePoster = next.poster
        request.episodeOverview = next.overview
        resumeAt = 0
        start()
    }

    func close() {
        guard !closing else { return }
        saveNow()
        closing = true
        player.pause()
        player.replaceCurrentItem(with: nil)
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        bag.removeAll()
        onClose()
    }
}

struct PlayerView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model: PlayerModel

    init(target: PlayerTarget) { _model = StateObject(wrappedValue: PlayerModel(target: target)) }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VideoPlayerController(player: model.player).ignoresSafeArea()
            if model.busy || model.failed {
                VStack(spacing: 14) {
                    if model.busy { ProgressView().tint(.white).scaleEffect(1.4) }
                    Text(model.status).foregroundColor(.white).multilineTextAlignment(.center).padding(.horizontal, 30)
                    if model.failed { Button("Volver") { model.close() }.buttonStyle(PrimaryButtonStyle()) }
                }
                .padding(24).background(RoundedRectangle(cornerRadius: 16).fill(Color.black.opacity(0.7)))
            }
            VStack {
                HStack {
                    Button { model.close() } label: {
                        Image(systemName: "xmark").font(.system(size: 18, weight: .bold)).foregroundColor(.white)
                            .padding(12).background(Circle().fill(Color.black.opacity(0.5)))
                    }
                    Text(model.title).font(.system(size: 14, weight: .semibold)).foregroundColor(.white).lineLimit(1)
                        .opacity(model.busy || model.failed ? 1 : 0)
                    Spacer()
                }
                .padding(.horizontal, 12).padding(.top, 8)
                Spacer()
            }
        }
        .statusBarHidden()
        .onAppear {
            model.onClose = { dismiss() }
            model.start()
        }
        .onDisappear { model.close() }
    }
}

/// AVPlayerViewController de iOS: controles, AirPlay, imagen en imagen y menú de audio/subtítulos.
struct VideoPlayerController: UIViewControllerRepresentable {
    let player: AVPlayer

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        vc.player = player
        vc.allowsPictureInPicturePlayback = true
        vc.canStartPictureInPictureAutomaticallyFromInline = true
        vc.videoGravity = .resizeAspect
        return vc
    }

    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {
        if vc.player !== player { vc.player = player }
    }
}
