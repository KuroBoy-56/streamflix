// Aviso de vencimiento (ntf_api.php) en una página web sobre la app, igual que en Android.
// La página llama a Android.closeModal() para cerrarse: aquí se ofrece el mismo objeto.
import SwiftUI
import WebKit

struct NoticeView: View {
    @EnvironmentObject var session: Session
    @State private var loaded = false

    var body: some View {
        ZStack {
            Color.black.opacity(loaded ? 0.8 : 0).ignoresSafeArea()
            if let url = PanelAPI.noticeURL() {
                NoticeWebView(url: url, onLoaded: { loaded = true }, onClose: { session.showNotice = false })
                    .opacity(loaded ? 1 : 0)
                    .ignoresSafeArea()
            }
        }
    }
}

struct NoticeWebView: UIViewRepresentable {
    let url: URL
    let onLoaded: () -> Void
    let onClose: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        let bridge = "window.Android = { closeModal: function () { window.webkit.messageHandlers.android.postMessage('close'); } };"
        config.userContentController.addUserScript(WKUserScript(source: bridge, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        config.userContentController.add(context.coordinator, name: "android")
        let web = WKWebView(frame: .zero, configuration: config)
        web.isOpaque = false
        web.backgroundColor = .clear
        web.scrollView.backgroundColor = .clear
        web.navigationDelegate = context.coordinator
        web.customUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
        web.load(URLRequest(url: url, cachePolicy: .reloadIgnoringLocalAndRemoteCacheData))
        return web
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    @MainActor final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        let parent: NoticeWebView
        init(_ parent: NoticeWebView) { self.parent = parent }

        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            parent.onClose()
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            // Página vacía o sin aviso para esta cuenta: no se muestra nada.
            webView.evaluateJavaScript("document.body ? document.body.innerText.trim().length : 0") { result, _ in
                if (result as? Int ?? 0) > 5 { self.parent.onLoaded() } else { self.parent.onClose() }
            }
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { parent.onClose() }
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { parent.onClose() }
    }
}
