// Ficha de película o serie (MovieMobileFragment / TvShowMobileFragment): portada, datos,
// "Ver ahora"/continuar, Mi lista, visto, temporadas y episodios, reparto y recomendaciones.
import SwiftUI

@MainActor
final class DetailsModel: ObservableObject {
    @Published var item: Item
    @Published var details: Details?
    @Published var links: [String: SiteLink] = [:]
    @Published var error = ""
    @Published var season = 1
    @Published var episodes: [Episode] = []
    @Published var loadingEpisodes = false

    init(item: Item) { self.item = item }

    func load() async {
        guard details == nil else { return }
        // Registro de la nube guardado por Android con el id de otro sitio: se busca en TMDb por título.
        let match = item.extra["match"] == "1"
        do {
            let d = try await Catalog.details(mediaType: item.mediaType, id: item.itemId, match: match, title: item.title, year: item.year)
            // Se conserva lo que vino de la fila y se completa con la ficha.
            var merged = item
            let n = d.item
            merged.itemId = n.itemId
            if !n.title.isEmpty { merged.title = n.title }
            if !n.overview.isEmpty { merged.overview = n.overview }
            if !n.poster.isEmpty { merged.poster = n.poster }
            if !n.backdrop.isEmpty { merged.backdrop = n.backdrop }
            if !n.rating.isEmpty { merged.rating = n.rating }
            if !n.year.isEmpty { merged.year = n.year }
            item = merged
            details = d
            if item.mediaType == "tv" {
                season = UserData.shared.lastEpisode(item, links: links)?.season ?? d.seasons.first?.number ?? 1
                await loadSeason(season)
            }
            // Ids del título en los sitios: Mi lista y el progreso van al mismo registro que en Android.
            links = await Sources.links(request())
            if item.mediaType == "tv", let last = UserData.shared.lastEpisode(item, links: links), last.season != season {
                await loadSeason(last.season)
            }
        } catch {
            self.error = (error as? CoreError)?.message ?? "No se pudo cargar la información."
        }
    }

    func loadSeason(_ number: Int) async {
        season = number
        loadingEpisodes = true
        episodes = (try? await Catalog.season(tvId: item.itemId, number: number)) ?? []
        loadingEpisodes = false
    }

    func request(episode: Episode? = nil) -> PlaybackRequest {
        var req = PlaybackRequest(mediaType: item.mediaType, id: item.itemId, title: item.title, year: item.year,
                                  season: episode == nil ? 0 : season, episode: episode?.number ?? 0,
                                  episodeId: episode?.id ?? "", episodeTitle: episode?.title ?? "", item: item, links: links)
        req.episodePoster = episode?.poster ?? ""
        req.episodeOverview = episode?.overview ?? ""
        req.seasonTitle = details?.seasons.first(where: { $0.number == season })?.title ?? ""
        return req
    }
}

struct DetailsView: View {
    @EnvironmentObject var session: Session
    @StateObject private var model: DetailsModel
    @State private var playing: PlayerTarget?

    init(item: Item) { _model = StateObject(wrappedValue: DetailsModel(item: item)) }

    var body: some View {
        let _ = session.dataTick
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header
                if !model.error.isEmpty {
                    Text(model.error).foregroundColor(Color(hex: 0xFF5252)).padding(.horizontal, 16)
                }
                buttons.padding(.horizontal, 16)
                if !model.item.overview.isEmpty {
                    Text(model.item.overview).font(.system(size: 14)).foregroundColor(.white.opacity(0.9)).padding(.horizontal, 16)
                }
                if model.item.mediaType == "tv", let d = model.details, !d.seasons.isEmpty { seasonsSection(d) }
                if let d = model.details {
                    if !d.cast.isEmpty { RowView(row: Row(title: "Elenco", items: d.cast)) }
                    if !d.recommendations.isEmpty { RowView(row: Row(title: "Recomendaciones", items: d.recommendations)) }
                }
            }
            .padding(.bottom, 30)
        }
        .background(Theme.background.ignoresSafeArea())
        .ignoresSafeArea(edges: .top)
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.load() }
        .fullScreenCover(item: $playing) { target in PlayerView(target: target) }
    }

    private var header: some View {
        ZStack(alignment: .bottomLeading) {
            RemoteImage(url: model.item.backdrop.isEmpty ? model.item.poster : model.item.backdrop)
                .frame(maxWidth: .infinity).frame(height: 300).clipped()
            LinearGradient(colors: [.clear, Theme.background], startPoint: .center, endPoint: .bottom).frame(height: 300)
            HStack(alignment: .bottom, spacing: 14) {
                RemoteImage(url: model.item.poster).frame(width: 110, height: 165)
                    .background(Theme.card).clipShape(RoundedRectangle(cornerRadius: 8))
                VStack(alignment: .leading, spacing: 6) {
                    Text(model.item.title).font(.system(size: 24, weight: .bold)).foregroundColor(.white).lineLimit(3)
                    meta
                    if let g = model.details?.genres, !g.isEmpty {
                        Text(g).font(.system(size: 12)).foregroundColor(Theme.textSecondary).lineLimit(2)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16).offset(y: 70)
        }
        .padding(.bottom, 70)
    }

    private var meta: some View {
        HStack(spacing: 10) {
            if !model.item.rating.isEmpty {
                HStack(spacing: 3) { Image(systemName: "star.fill").foregroundColor(Theme.star); Text(model.item.rating) }
            }
            if !model.item.year.isEmpty { Text(model.item.year) }
            if let r = model.details?.runtime, r > 0 { Text("\(r) min") }
            if model.item.mediaType == "tv", let n = model.details?.seasons.count, n > 0 { Text(n == 1 ? "1 temporada" : "\(n) temporadas") }
        }
        .font(.system(size: 13)).foregroundColor(Theme.textSecondary)
    }

    private var buttons: some View {
        let ud = UserData.shared
        let fav = ud.isFavorite(model.item, links: model.links)
        return HStack(spacing: 10) {
            if model.item.mediaType == "movie" {
                let resume = ud.movieResume(model.item, links: model.links)
                Button { play(nil, at: resume > 30 ? resume : 0) } label: {
                    Label(resume > 30 ? "Continuar · " + formatClock(resume) : "Ver ahora", systemImage: "play.fill")
                }.buttonStyle(PrimaryButtonStyle())
                if resume > 30 {
                    Button { play(nil, at: 0) } label: { Image(systemName: "arrow.counterclockwise") }.buttonStyle(SecondaryButtonStyle())
                }
                let watched = ud.movieWatched(model.item, links: model.links)
                Button { ud.setWatched(model.request(), watched: !watched) } label: {
                    Image(systemName: watched ? "checkmark.circle.fill" : "checkmark.circle")
                }.buttonStyle(SecondaryButtonStyle())
            } else if let last = ud.lastEpisode(model.item, links: model.links) {
                Button { continueEpisode(last) } label: {
                    Label("Continuar T\(last.season):E\(last.episode)", systemImage: "play.fill")
                }.buttonStyle(PrimaryButtonStyle())
            } else if let first = model.episodes.first {
                Button { play(first, at: 0) } label: { Label("Ver ahora", systemImage: "play.fill") }.buttonStyle(PrimaryButtonStyle())
            }
            Spacer()
            Button { ud.setFavorite(model.item, links: model.links, favorite: !fav) } label: {
                Image(systemName: fav ? "heart.fill" : "heart").font(.system(size: 24)).foregroundColor(fav ? Theme.red : .white)
            }
        }
    }

    private func seasonsSection(_ d: Details) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Temporadas").font(.system(size: 18, weight: .bold)).foregroundColor(.white).padding(.horizontal, 16)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(d.seasons) { s in
                        Button { Task { await model.loadSeason(s.number) } } label: {
                            Text(s.title.isEmpty ? "Temporada \(s.number)" : s.title)
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundColor(model.season == s.number ? .black : .white)
                                .padding(.horizontal, 14).padding(.vertical, 8)
                                .background(Capsule().fill(model.season == s.number ? Color.white : Color.white.opacity(0.15)))
                        }
                    }
                }
                .padding(.horizontal, 16)
            }
            if model.loadingEpisodes { ProgressView().tint(.white).frame(maxWidth: .infinity).padding() }
            ForEach(model.episodes) { ep in
                Button { play(ep, at: resumeOf(ep)) } label: { EpisodeRow(episode: ep, record: record(ep)) }
                    .buttonStyle(.plain).padding(.horizontal, 16)
                    .contextMenu {
                        let watched = isWatched(record(ep))
                        Button(watched ? "Marcar no visto" : "Marcar visto") {
                            UserData.shared.setWatched(model.request(episode: ep), watched: !watched)
                        }
                    }
            }
        }
    }

    private func record(_ ep: Episode) -> JSON? {
        UserData.shared.episodeRecord(model.item, links: model.links, season: model.season, number: ep.number, tmdbEpisodeId: ep.id)
    }

    private func isWatched(_ rec: JSON?) -> Bool { asStr(rec?["isWatched"]) == "true" }

    private func resumeOf(_ ep: Episode) -> Int {
        let rec = record(ep)
        return UserData.shared.percent(of: rec) >= 95 ? 0 : UserData.shared.resume(of: rec)
    }

    private func continueEpisode(_ last: (season: Int, episode: Int)) {
        Task {
            if model.season != last.season { await model.loadSeason(last.season) }
            if let ep = model.episodes.first(where: { $0.number == last.episode }) { play(ep, at: resumeOf(ep)) }
        }
    }

    private func play(_ episode: Episode?, at start: Int) {
        let req = model.request(episode: episode)
        playing = PlayerTarget(request: req, startAt: start, episodes: episode == nil ? [] : model.episodes)
    }
}

struct EpisodeRow: View {
    let episode: Episode
    let record: JSON?

    var body: some View {
        let percent = UserData.shared.percent(of: record)
        HStack(alignment: .top, spacing: 12) {
            ZStack(alignment: .bottomLeading) {
                Theme.card
                RemoteImage(url: episode.poster)
                if percent > 0 {
                    GeometryReader { g in
                        Rectangle().fill(Theme.red).frame(width: g.size.width * CGFloat(min(percent, 100)) / 100, height: 3)
                            .frame(maxHeight: .infinity, alignment: .bottom)
                    }
                }
            }
            .frame(width: 140, height: 79).clipShape(RoundedRectangle(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 4) {
                Text("\(episode.number). \(episode.title)").font(.system(size: 14, weight: .semibold)).foregroundColor(.white).lineLimit(2)
                HStack(spacing: 8) {
                    if episode.runtime > 0 { Text("\(episode.runtime) min") }
                    if asStr(record?["isWatched"]) == "true" || percent >= 95 { Text("Visto").foregroundColor(Theme.red) }
                }
                .font(.system(size: 12)).foregroundColor(Theme.textSecondary)
                Text(episode.overview).font(.system(size: 12)).foregroundColor(Theme.textSecondary).lineLimit(3)
            }
            Spacer(minLength: 0)
        }
    }
}
