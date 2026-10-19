// Pestañas de abajo como la versión de celular: Inicio, En Vivo (si el panel asignó canales),
// Películas, Series, Favoritos y Ajustes. La lupa de arriba abre la búsqueda.
import SwiftUI

struct MainTabView: View {
    @EnvironmentObject var session: Session

    var body: some View {
        TabView {
            if Live.hasLive && !hasVod {
                tab(LiveView(), "En Vivo", "dot.radiowaves.left.and.right")
            } else {
                tab(RowsScreen(mode: "home"), "Inicio", "house.fill")
                if Live.hasLive { tab(LiveView(), "En Vivo", "dot.radiowaves.left.and.right") }
                tab(RowsScreen(mode: "movie"), "Películas", "film")
                tab(RowsScreen(mode: "tv"), "Series", "tv")
            }
            tab(FavoritesView(), "Favoritos", "heart.fill")
            tab(SettingsView(), "Ajustes", "gearshape.fill")
        }
        .tint(Theme.navActive)
    }

    /// Cuenta solo con canales (sin servidores de películas): En Vivo es el inicio.
    private var hasVod: Bool { !Reg.read("assigned_servers").isEmpty }

    private func tab<V: View>(_ view: V, _ title: String, _ icon: String) -> some View {
        NavigationStack {
            view.navigationDestination(for: Route.self) { route in
                switch route {
                case .item(let item):
                    if item.mediaType == "person" { PersonView(id: item.itemId, name: item.title) } else { DetailsView(item: item) }
                case .person(let id, let name): PersonView(id: id, name: name)
                case .search: SearchView()
                }
            }
        }
        .tabItem { Label(title, systemImage: icon) }
    }
}

/// Lupa arriba a la derecha (btn_main_search).
struct SearchButton: ToolbarContent {
    var body: some ToolbarContent {
        ToolbarItem(placement: .navigationBarTrailing) {
            NavigationLink(value: Route.search) { Image(systemName: "magnifyingglass").foregroundColor(.white) }
        }
    }
}

// MARK: - Inicio, Películas y Series

@MainActor
final class RowsModel: ObservableObject {
    @Published var rows: [Row] = []
    @Published var error = ""
    @Published var loading = false
    private var loadingPages = Set<String>()
    let mode: String

    init(mode: String) { self.mode = mode }

    func load() async {
        guard rows.isEmpty, !loading else { return }
        loading = true
        error = ""
        do {
            rows = mode == "home" ? try await Catalog.home() : try await Catalog.catalog(mode)
        } catch {
            self.error = (error as? CoreError)?.message ?? "No se pudo cargar el catálogo."
            Task { await PanelAPI.report("Inicio.Catalogo", self.error) }
        }
        loading = false
    }

    /// Al acercarse al final de una fila se pide la página siguiente.
    func more(_ row: Row) {
        guard let paging = row.paging, !loadingPages.contains(row.title) else { return }
        loadingPages.insert(row.title)
        Task {
            let res = await Catalog.page(paging)
            if let i = rows.firstIndex(where: { $0.title == row.title }) {
                rows[i].items += res.items
                rows[i].paging = res.next
            }
            loadingPages.remove(row.title)
        }
    }
}

struct RowsScreen: View {
    @EnvironmentObject var session: Session
    @StateObject private var model: RowsModel
    let mode: String

    init(mode: String) {
        self.mode = mode
        _model = StateObject(wrappedValue: RowsModel(mode: mode))
    }

    var body: some View {
        Group {
            if model.loading && model.rows.isEmpty {
                LoadingView()
            } else if !model.error.isEmpty && model.rows.isEmpty {
                MessageView(text: model.error) { Task { await model.load() } }
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 22) {
                        if let first = model.rows.first, !first.items.isEmpty {
                            FeaturedSwiper(items: Array(first.items.prefix(8)))
                        }
                        // "Continuar viendo" y "Mis favoritos" van arriba en Inicio (nube compartida).
                        if mode == "home" {
                            let _ = session.dataTick
                            let continueItems = UserData.shared.continueWatching()
                            if !continueItems.isEmpty { RowView(row: Row(title: "Continuar viendo", items: continueItems)) }
                            let favs = UserData.shared.favorites()
                            if !favs.isEmpty { RowView(row: Row(title: "Mis favoritos", items: favs)) }
                        }
                        ForEach(model.rows) { row in
                            RowView(row: row) { model.more(row) }
                        }
                    }
                    .padding(.bottom, 20)
                }
                .refreshable { model.rows = []; await model.load() }
            }
        }
        .background(Theme.background.ignoresSafeArea())
        .navigationTitle(mode == "home" ? "" : (mode == "movie" ? "Películas" : "Series"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if mode == "home" {
                ToolbarItem(placement: .navigationBarLeading) { BrandLogo(size: 34) }
            }
            SearchButton()
        }
        .task { await model.load() }
    }
}

/// Carrusel de destacados (content_category_swiper_mobile): portada, datos, "Ver ahora" y puntos.
struct FeaturedSwiper: View {
    let items: [Item]
    @State private var index = 0
    private let timer = Timer.publish(every: 6, on: .main, in: .common).autoconnect()

    var body: some View {
        TabView(selection: $index) {
            ForEach(Array(items.enumerated()), id: \.offset) { i, item in
                slide(item).tag(i)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .always))
        .frame(height: 460)
        .onReceive(timer) { _ in withAnimation { index = (index + 1) % max(items.count, 1) } }
    }

    private func slide(_ item: Item) -> some View {
        ZStack(alignment: .bottomLeading) {
            RemoteImage(url: item.backdrop.isEmpty ? item.poster : item.backdrop)
                .frame(maxWidth: .infinity).frame(height: 460).clipped()
            LinearGradient(colors: [.clear, Theme.background.opacity(0.7), Theme.background], startPoint: .top, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 8) {
                Text(item.title).font(.system(size: 20, weight: .bold)).foregroundColor(.white).lineLimit(2)
                HStack(spacing: 12) {
                    if !item.year.isEmpty { Text(item.year) }
                    if !item.rating.isEmpty {
                        HStack(spacing: 3) { Image(systemName: "star.fill").foregroundColor(Theme.star); Text(item.rating) }
                    }
                    Text(item.mediaType == "tv" ? "Serie" : "Película")
                }
                .font(.system(size: 13)).foregroundColor(Theme.textSecondary)
                Text(item.overview).font(.system(size: 13)).foregroundColor(.white.opacity(0.85)).lineLimit(3)
                NavigationLink(value: Route.item(item)) { Text("Ver ahora") }
                    .buttonStyle(PrimaryButtonStyle()).padding(.top, 4)
            }
            .padding(.horizontal, 16).padding(.bottom, 40)
        }
    }
}

// MARK: - Favoritos (FavoritesMobileFragment: películas y series favoritas)

struct FavoritesView: View {
    @EnvironmentObject var session: Session
    private let columns = [GridItem(.adaptive(minimum: 108), spacing: 10, alignment: .top)]

    var body: some View {
        let _ = session.dataTick
        let favs = UserData.shared.favorites()
        let movies = favs.filter { $0.mediaType == "movie" }
        let shows = favs.filter { $0.mediaType == "tv" }
        Group {
            if favs.isEmpty {
                MessageView(text: "Aún no tienes favoritos.\nMárcalos con el corazón en la ficha de cada título.")
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        section("Películas favoritas", movies)
                        section("Series favoritas", shows)
                    }
                    .padding(12)
                }
            }
        }
        .background(Theme.background.ignoresSafeArea())
        .navigationTitle("Favoritos")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { SearchButton() }
    }

    @ViewBuilder private func section(_ title: String, _ items: [Item]) -> some View {
        if !items.isEmpty {
            Text(title).font(.system(size: 18, weight: .bold)).foregroundColor(.white)
            LazyVGrid(columns: columns, spacing: 14) {
                ForEach(items) { item in
                    NavigationLink(value: Route.item(item)) { PosterCard(item: item, width: 108, favorite: true) }.buttonStyle(.plain)
                }
            }
        }
    }
}

// MARK: - Búsqueda (SearchMobileFragment)

struct SearchView: View {
    @State private var query = ""
    @State private var items: [Item] = []
    @State private var searching = false
    @State private var searched = false
    private let columns = [GridItem(.adaptive(minimum: 108), spacing: 10, alignment: .top)]

    var body: some View {
        ScrollView {
            if searching { ProgressView().tint(.white).padding(40) }
            else if searched && items.isEmpty { Text("Sin resultados").foregroundColor(Theme.textSecondary).padding(40) }
            LazyVGrid(columns: columns, spacing: 14) {
                ForEach(items) { item in
                    NavigationLink(value: Route.item(item)) { PosterCard(item: item, width: 108) }.buttonStyle(.plain)
                }
            }
            .padding(12)
        }
        .background(Theme.background.ignoresSafeArea())
        .navigationTitle("Buscar")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Buscar película, serie")
        .onSubmit(of: .search) { run() }
        .task(id: query) {
            // Búsqueda mientras se escribe, con una pequeña espera.
            try? await Task.sleep(nanoseconds: 600_000_000)
            if !Task.isCancelled && query.count >= 2 { run() }
        }
    }

    private func run() {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        searching = true
        Task {
            let found = await Catalog.search(q)
            if q == query.trimmingCharacters(in: .whitespaces) {
                items = found
                searched = true
                searching = false
            }
        }
    }
}

// MARK: - Persona del reparto (PeopleMobileFragment)

struct PersonView: View {
    let id: String
    let name: String
    @State private var page: PersonPage?
    @State private var error = ""
    private let columns = [GridItem(.adaptive(minimum: 108), spacing: 10, alignment: .top)]

    var body: some View {
        Group {
            if let page {
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        HStack(alignment: .top, spacing: 14) {
                            RemoteImage(url: page.poster).frame(width: 110, height: 165).clipShape(RoundedRectangle(cornerRadius: 8))
                            VStack(alignment: .leading, spacing: 6) {
                                Text(page.title).font(.system(size: 22, weight: .bold)).foregroundColor(.white)
                                Text(page.overview).font(.system(size: 13)).foregroundColor(Theme.textSecondary).lineLimit(8)
                            }
                        }
                        LazyVGrid(columns: columns, spacing: 14) {
                            ForEach(page.items) { item in
                                NavigationLink(value: Route.item(item)) { PosterCard(item: item, width: 108) }.buttonStyle(.plain)
                            }
                        }
                    }
                    .padding(12)
                }
            } else if !error.isEmpty {
                MessageView(text: error)
            } else {
                LoadingView()
            }
        }
        .background(Theme.background.ignoresSafeArea())
        .navigationTitle(name)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            do { page = try await Catalog.person(id) } catch { self.error = (error as? CoreError)?.message ?? "No se pudo cargar la información." }
        }
    }
}
