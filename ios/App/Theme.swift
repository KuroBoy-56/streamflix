// Colores y piezas visuales comunes: los mismos de la versión de celular de Android.
import SwiftUI

enum Theme {
    static let background = Color(hex: 0x181818)
    static let card = Color(hex: 0x1C1F2B)
    static let field = Color(hex: 0x1F2230)
    /// Acento neutro (sin rojo): gris claro para selección, foco y botones.
    static let accent = Color(hex: 0xD0D4DC)
    static let loginFocus = accent
    static let navy = Color(hex: 0x0F111A)
    static let textSecondary = Color(hex: 0xBDBDBD)
    static let navInactive = Color(hex: 0x808080)
    static let navActive = Color(hex: 0xC6C6C6)
    static let star = Color(hex: 0xFFD54F)
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xff) / 255, green: Double((hex >> 8) & 0xff) / 255,
                  blue: Double(hex & 0xff) / 255, opacity: alpha)
    }
}

/// Imagen remota con caché en memoria (las carátulas se repiten mucho entre filas).
final class ImageCache {
    static let shared = ImageCache()
    private let cache = NSCache<NSString, UIImage>()
    init() { cache.countLimit = 400 }

    func image(for url: String) -> UIImage? { cache.object(forKey: url as NSString) }

    func load(_ url: String) async -> UIImage? {
        if let img = image(for: url) { return img }
        guard let u = URL(string: url) else { return nil }
        var req = URLRequest(url: u)
        req.cachePolicy = .returnCacheDataElseLoad
        guard let (data, _) = try? await URLSession.shared.data(for: req), let img = UIImage(data: data) else { return nil }
        cache.setObject(img, forKey: url as NSString)
        return img
    }
}

/// Ocupa exactamente el tamaño que le dan y recorta: una imagen "fill" nunca agranda la
/// pantalla (antes la portada ancha empujaba la ficha y el login fuera del iPhone).
struct RemoteImage: View {
    let url: String
    var contentMode: ContentMode = .fill
    @State private var image: UIImage?

    var body: some View {
        Color.clear
            .overlay {
                if let image {
                    Image(uiImage: image).resizable().aspectRatio(contentMode: contentMode)
                }
            }
            .clipped()
            .task(id: url) {
                image = ImageCache.shared.image(for: url)
                if image == nil, !url.isEmpty { image = await ImageCache.shared.load(url) }
            }
    }
}

/// Botón principal de la versión de celular: blanco con texto negro (AppTheme.Widget.Mobile.Button).
struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 15, weight: .semibold))
            .foregroundColor(.black)
            .padding(.horizontal, 18).padding(.vertical, 9)
            .background(Capsule().fill(Color.white.opacity(configuration.isPressed ? 0.75 : 1)))
    }
}

/// Botón secundario: gris translúcido con texto blanco (Mobile.Button.Secondary).
struct SecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 15, weight: .semibold))
            .foregroundColor(.white)
            .padding(.horizontal, 18).padding(.vertical, 9)
            .background(Capsule().fill(Color.white.opacity(configuration.isPressed ? 0.35 : 0.2)))
    }
}

/// Tarjeta de carátula como item_movie_mobile: póster 2:3, cinta de favorito, progreso, año y título.
struct PosterCard: View {
    let item: Item
    var width: CGFloat = 120
    var favorite = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ZStack(alignment: .bottomLeading) {
                Theme.card
                if item.poster.isEmpty {
                    Text(item.title).font(.caption).foregroundColor(.gray).multilineTextAlignment(.center).padding(6)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                RemoteImage(url: item.poster, contentMode: item.mediaType == "channel" ? .fit : .fill)
                    .padding(item.mediaType == "channel" ? 12 : 0)
                if item.progress > 0 {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Rectangle().fill(Color(hex: 0x646464, alpha: 0.8))
                            Rectangle().fill(Color.white).frame(width: geo.size.width * CGFloat(min(item.progress, 100)) / 100)
                        }
                    }
                    .frame(height: 4).padding(.horizontal, 8).padding(.bottom, 10)
                }
                if favorite {
                    Image(systemName: "heart.fill").font(.system(size: 13)).foregroundColor(.white)
                        .padding(5).background(Circle().fill(Color.black.opacity(0.6))).padding(8)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                }
            }
            .frame(width: width, height: width * 1.5)
            .clipShape(RoundedRectangle(cornerRadius: 6))

            if !item.year.isEmpty {
                Text(item.year).font(.system(size: 12)).foregroundColor(Theme.textSecondary)
            }
            Text(item.title).font(.system(size: 13, weight: .bold)).foregroundColor(.white)
                .lineLimit(2, reservesSpace: true).frame(width: width, alignment: .leading)
        }
        .frame(width: width)
    }
}

/// Fila horizontal con título (item_category_mobile).
struct RowView: View {
    let row: Row
    var onAppearLast: (() -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(row.title).font(.system(size: 18, weight: .bold)).foregroundColor(.white).lineLimit(1).padding(.horizontal, 12)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 10) {
                    ForEach(Array(row.items.enumerated()), id: \.offset) { index, item in
                        NavigationLink(value: Route.item(item)) { PosterCard(item: item) }
                            .buttonStyle(.plain)
                            .onAppear { if index >= row.items.count - 6 { onAppearLast?() } }
                    }
                }
                .padding(.horizontal, 12)
            }
        }
    }
}

struct LoadingView: View {
    var body: some View { ProgressView().tint(.white).frame(maxWidth: .infinity, maxHeight: .infinity) }
}

struct MessageView: View {
    let text: String
    var retry: (() -> Void)? = nil
    var body: some View {
        VStack(spacing: 14) {
            Text(text).foregroundColor(.white).multilineTextAlignment(.center)
            if let retry { Button("Reintentar", action: retry).buttonStyle(PrimaryButtonStyle()) }
        }
        .padding(30).frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Destinos de navegación dentro de cada pestaña.
enum Route: Hashable {
    case item(Item)
    case person(String, String)
    case search
}
