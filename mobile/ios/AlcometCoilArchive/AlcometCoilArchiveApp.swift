import SwiftUI
import WebKit
import Security

struct ServerConfig: Codable, Equatable {
    var scheme: String = "https"
    var host: String = ""
    var port: Int = 5051
    var allowLanHTTP: Bool = false

    var baseURL: URL? {
        var c = URLComponents()
        c.scheme = scheme.lowercased()
        c.host = host.trimmingCharacters(in: .whitespacesAndNewlines)
        c.port = port
        c.path = "/"
        return c.url
    }

    func validationError() -> String? {
        let proto = scheme.lowercased()
        let h = host.trimmingCharacters(in: .whitespacesAndNewlines)
        if proto != "https" && proto != "http" { return "Protocol must be HTTPS or HTTP." }
        if h.isEmpty || h.contains("/") || h.contains("@") || h.contains("?") || h.contains("#") { return "Enter only the server IP or hostname." }
        if port < 1 || port > 65535 { return "Port must be between 1 and 65535." }
        if port == 5050 { return "Port 5050 is the Engineering/Admin service and is blocked." }
        if proto == "http" && (!allowLanHTTP || !Self.isPrivateOrLocalHost(h)) { return "HTTP is allowed only for a private/local server when LAN HTTP is enabled." }
        return nil
    }

    func sameOrigin(_ url: URL?) -> Bool {
        guard let u = url, let s = u.scheme?.lowercased(), let h = u.host?.lowercased() else { return false }
        let effectivePort = u.port ?? (s == "https" ? 443 : 80)
        return s == scheme.lowercased() && h == host.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() && effectivePort == port
    }

    static func isPrivateOrLocalHost(_ raw: String) -> Bool {
        let h = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if h == "localhost" || h.hasSuffix(".local") { return true }
        let p = h.split(separator: ".").compactMap { Int($0) }
        guard p.count == 4, p.allSatisfy({ (0...255).contains($0) }) else { return false }
        return p[0] == 10 || p[0] == 127 || (p[0] == 172 && (16...31).contains(p[1])) || (p[0] == 192 && p[1] == 168) || (p[0] == 169 && p[1] == 254)
    }
}

final class KeychainStore {
    private let service = "com.alcomet.rollingcoilarchive"
    private let account = "operator-server-v1"

    func load() -> ServerConfig? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                kSecAttrService as String: service,
                                kSecAttrAccount as String: account,
                                kSecReturnData as String: true,
                                kSecMatchLimit as String: kSecMatchLimitOne]
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return try? JSONDecoder().decode(ServerConfig.self, from: data)
    }

    func save(_ config: ServerConfig) throws {
        let data = try JSONEncoder().encode(config)
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                   kSecAttrService as String: service,
                                   kSecAttrAccount as String: account]
        let attrs: [String: Any] = [kSecValueData as String: data,
                                    kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(base as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound {
            var add = base
            attrs.forEach { add[$0.key] = $0.value }
            let s = SecItemAdd(add as CFDictionary, nil)
            if s != errSecSuccess { throw NSError(domain: NSOSStatusErrorDomain, code: Int(s)) }
        } else if status != errSecSuccess { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    }
}

@MainActor final class AppModel: ObservableObject {
    @Published var config: ServerConfig
    @Published var showSettings = false
    @Published var reloadToken = UUID()
    @Published var status = "offline"
    @Published var online = false
    private let keychain = KeychainStore()

    init() {
        if let stored = keychain.load(), stored.validationError() == nil {
            config = stored
        } else {
            config = ServerConfig()
            showSettings = true
        }
    }

    func save(_ c: ServerConfig) throws {
        if let e = c.validationError() { throw NSError(domain: "Alcomet", code: 1, userInfo: [NSLocalizedDescriptionKey: e]) }
        try keychain.save(c)
        config = c
        reloadToken = UUID()
    }

    func test(_ c: ServerConfig) async -> Result<String, Error> {
        if let e = c.validationError() { return .failure(NSError(domain: "Alcomet", code: 2, userInfo: [NSLocalizedDescriptionKey: e])) }
        guard let root = c.baseURL, let health = URL(string: "health", relativeTo: root)?.absoluteURL else {
            return .failure(NSError(domain: "Alcomet", code: 3, userInfo: [NSLocalizedDescriptionKey: "Invalid server URL."]))
        }
        var req = URLRequest(url: health, cachePolicy: .reloadIgnoringLocalAndRemoteCacheData, timeoutInterval: 4)
        req.setValue("application/json,text/plain", forHTTPHeaderField: "Accept")
        do {
            let (data,response) = try await URLSession.shared.data(for: req)
            guard let h = response as? HTTPURLResponse, h.statusCode == 200 else { throw NSError(domain: "Alcomet", code: 4, userInfo: [NSLocalizedDescriptionKey: "Server did not return HTTP 200."]) }
            var text = "Connected"
            if let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any], let v = obj["version"] as? String { text += " • CoilReport \(v)" }
            return .success(text)
        } catch { return .failure(error) }
    }
}

struct SecureWebView: UIViewRepresentable {
    @EnvironmentObject var model: AppModel
    let token: UUID

    func makeCoordinator() -> Coordinator { Coordinator(model: model) }
    func makeUIView(context: Context) -> WKWebView {
        let cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = .default()
        cfg.defaultWebpagePreferences.allowsContentJavaScript = true
        cfg.preferences.javaScriptCanOpenWindowsAutomatically = false
        let w = WKWebView(frame: .zero, configuration: cfg)
        w.navigationDelegate = context.coordinator
        w.uiDelegate = context.coordinator
        w.allowsBackForwardNavigationGestures = true
        return w
    }
    func updateUIView(_ web: WKWebView, context: Context) {
        context.coordinator.model = model
        guard let url = model.config.baseURL else { return }
        if context.coordinator.lastToken != token {
            context.coordinator.lastToken = token
            web.load(URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10))
        }
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        var model: AppModel
        var lastToken: UUID?
        init(model: AppModel) { self.model = model }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            if model.config.sameOrigin(navigationAction.request.url) { decisionHandler(.allow) }
            else { decisionHandler(.cancel) }
        }
        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) { Task { @MainActor in model.status = "connecting…"; model.online = false } }
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { Task { @MainActor in model.status = "\(model.config.host):\(model.config.port) • online"; model.online = true } }
        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { offline(error) }
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { offline(error) }
        private func offline(_ e: Error) { Task { @MainActor in model.status = "offline"; model.online = false } }
        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? { nil }
    }
}

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) var dismiss
    @State private var draft = ServerConfig()
    @State private var state = "Not tested"
    @State private var ok = false
    @State private var testing = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Operator server") {
                    Picker("Protocol", selection: $draft.scheme) {
                        Text("HTTPS (recommended)").tag("https")
                        Text("HTTP (private LAN only)").tag("http")
                    }
                    TextField("192.168.1.100", text: $draft.host).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.numbersAndPunctuation)
                    TextField("5051", value: $draft.port, format: .number).keyboardType(.numberPad)
                    if draft.scheme == "http" { Toggle("Allow unencrypted HTTP on private LAN", isOn: $draft.allowLanHTTP) }
                }
                Section("Security") {
                    Text("Port 5050 is blocked. HTTPS uses normal certificate validation. HTTP is allowed only for private/local addresses and is not encrypted on Wi-Fi.").font(.footnote).foregroundStyle(.secondary)
                }
                Section {
                    Text(state).font(.footnote).foregroundStyle(ok ? .green : .secondary)
                    Button(testing ? "Testing…" : "Test connection") { Task { await test() } }.disabled(testing)
                    Button("Save & connect") { save() }.disabled(testing)
                }
            }
            .navigationTitle("Connection")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
            .onAppear { draft = model.config }
        }.preferredColorScheme(.dark)
    }

    @MainActor private func test() async {
        if let e = draft.validationError() { state = e; ok = false; return }
        testing = true; state = "Testing…"; ok = false
        let r = await model.test(draft); testing = false
        switch r { case .success(let s): state=s; ok=true; case .failure(let e): state="Connection failed: \(e.localizedDescription)"; ok=false }
    }
    private func save() {
        if let e = draft.validationError() { state=e; ok=false; return }
        do { try model.save(draft); dismiss() } catch { state=error.localizedDescription; ok=false }
    }
}

struct ContentView: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Text("A").font(.headline).foregroundStyle(.white).frame(width: 34,height: 34).background(Color(red: 18/255, green: 102/255, blue: 143/255)).clipShape(RoundedRectangle(cornerRadius: 6))
                VStack(alignment: .leading, spacing: 1) {
                    Text("ALCOMET • Coil Archive").font(.subheadline.bold()).foregroundStyle(.white)
                    Text(model.status).font(.caption2).foregroundStyle(model.online ? .green : .secondary)
                }
                Spacer()
                Button { model.reloadToken = UUID() } label: { Image(systemName: "arrow.clockwise").frame(width: 40,height: 40) }
                Button { model.showSettings = true } label: { Image(systemName: "gearshape.fill").frame(width: 40,height: 40) }
            }.padding(.horizontal,12).frame(height: 50).background(Color(red: 8/255, green: 19/255, blue: 29/255))
            if model.config.validationError() == nil && !model.config.host.isEmpty {
                SecureWebView(token: model.reloadToken).environmentObject(model).id(model.config)
            } else {
                VStack(spacing: 16) {
                    Spacer()
                    Text("Operator server offline").font(.title2.bold()).foregroundStyle(.white)
                    Text("Configure the CoilReport Operator server to begin.").foregroundStyle(.secondary).multilineTextAlignment(.center)
                    Button("Connection settings") { model.showSettings = true }.buttonStyle(.borderedProminent)
                    Spacer()
                }.frame(maxWidth: .infinity).background(Color(red: 8/255, green: 19/255, blue: 29/255))
            }
        }
        .tint(Color(red: 70/255, green: 167/255, blue: 223/255))
        .preferredColorScheme(.dark)
        .sheet(isPresented: $model.showSettings) { SettingsView().environmentObject(model) }
    }
}

@main struct AlcometCoilArchiveApp: App {
    @StateObject private var model = AppModel()
    var body: some Scene { WindowGroup { ContentView().environmentObject(model) } }
}
