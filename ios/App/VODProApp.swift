// Punto de entrada: login, "¿Quién está viendo?" y la app principal según la sesión.
import SwiftUI
import AVFoundation

@main
struct VODProApp: App {
    @StateObject private var session = Session()

    init() {
        // Audio de películas aunque el teléfono esté en silencio, y fondo oscuro en todas las barras.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        let tab = UITabBarAppearance()
        tab.configureWithOpaqueBackground()
        tab.backgroundColor = UIColor(Theme.background)
        UITabBar.appearance().standardAppearance = tab
        UITabBar.appearance().scrollEdgeAppearance = tab
        let nav = UINavigationBarAppearance()
        nav.configureWithTransparentBackground()
        nav.titleTextAttributes = [.foregroundColor: UIColor.white]
        nav.largeTitleTextAttributes = [.foregroundColor: UIColor.white]
        UINavigationBar.appearance().standardAppearance = nav
        UINavigationBar.appearance().scrollEdgeAppearance = nav
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(session)
                .preferredColorScheme(.dark)
                .tint(.white)
        }
    }
}

struct RootView: View {
    @EnvironmentObject var session: Session

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            if session.autoLoggingIn {
                SplashView()
            } else {
                switch session.screen {
                case .login: LoginView()
                case .profiles: ProfilesView()
                case .main: MainTabView()
                }
            }
        }
        .sheet(isPresented: $session.showNotice) {
            NoticeView()
        }
    }
}

struct SplashView: View {
    @EnvironmentObject var session: Session
    var body: some View {
        ZStack {
            Theme.navy.ignoresSafeArea()
            if !Reg.read("custom_splash_url").isEmpty {
                RemoteImage(url: Reg.read("custom_splash_url"), contentMode: .fill).ignoresSafeArea()
            }
            VStack(spacing: 18) {
                BrandLogo(size: 96)
                ProgressView().tint(.white)
            }
        }
    }
}

/// Logo del distribuidor (o el de la app si el panel no tiene uno).
struct BrandLogo: View {
    @EnvironmentObject var session: Session
    var size: CGFloat
    var body: some View {
        Group {
            if session.logoURL.isEmpty {
                Image("Logo").resizable().scaledToFit()
            } else {
                RemoteImage(url: session.logoURL, contentMode: .fit)
            }
        }
        .frame(width: size, height: size)
        .id(session.brandTick)
    }
}
