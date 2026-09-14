import SwiftUI
import WebKit
import Security
import AVFoundation
import UserNotifications
import UIKit

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

struct MobilePolicy: Decodable, Equatable {
    var qrBarcodeSearch = false
    var watchlist = false
    var notifications = false
    var journeyNotifications = false
    var nativePdfShare = false
    var pollMinutes = 15
    var childSuffixes = "A,B"
    var version = ""

    enum CodingKeys: String, CodingKey {
        case qrBarcodeSearch = "qr_barcode_search"
        case watchlist, notifications
        case journeyNotifications = "journey_notifications"
        case nativePdfShare = "native_pdf_share"
        case pollMinutes = "poll_minutes"
        case childSuffixes = "child_suffixes"
        case version
    }
    static let disabled = MobilePolicy()
}

struct EventEnvelope: Decodable { var events: [MobileEvent] = [] }
struct MobileEvent: Decodable {
    var id = ""
    var coil = ""
    var family = ""
    var machine = ""
    var previousMachine = ""
    var type = ""
    var reportURL = "/"
    enum CodingKeys: String, CodingKey {
        case id, coil, family, machine, type
        case previousMachine = "previous_machine"
        case reportURL = "report_url"
    }
}

struct ScanTarget {
    let machine: String
    let coil: String
    static func parse(_ raw: String) -> ScanTarget? {
        let v = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if v.uppercased().hasPrefix("CR-") {
            let p = v.split(separator: "-", maxSplits: 2).map(String.init)
            if p.count == 3, let m = normalize(p[1]), let c = normalize(p[2]) { return ScanTarget(machine: m, coil: c) }
        }
        if let u = URLComponents(string: v), let items = u.queryItems,
           let c0 = items.first(where: { $0.name == "q" })?.value,
           let c = normalize(c0) {
            let m = normalize(items.first(where: { $0.name == "machine" })?.value ?? "") ?? ""
            return ScanTarget(machine: m, coil: c)
        }
        guard let c = normalize(v) else { return nil }
        return ScanTarget(machine: "", coil: c)
    }
    static func normalize(_ raw: String) -> String? {
        let v = raw.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard (2...64).contains(v.count), v.range(of: "^[A-Z0-9._-]+$", options: .regularExpression) != nil else { return nil }
        return v
    }
}

final class KeychainStore {
    private let service = "com.alcomet.rollingcoilarchive"
    private let account = "operator-server-v1"
    func load() -> ServerConfig? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        return try? JSONDecoder().decode(ServerConfig.self, from: data)
    }
    func save(_ config: ServerConfig) throws {
        let data = try JSONEncoder().encode(config)
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
        let attrs: [String: Any] = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(base as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound {
            var add = base; attrs.forEach { add[$0.key] = $0.value }
            let s = SecItemAdd(add as CFDictionary, nil)
            if s != errSecSuccess { throw NSError(domain: NSOSStatusErrorDomain, code: Int(s)) }
        } else if status != errSecSuccess { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    }
}

final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}

@MainActor final class AppModel: ObservableObject {
    @Published var config: ServerConfig
    @Published var showSettings = false
    @Published var reloadToken = UUID()
    @Published var targetPath = "/"
    @Published var status = "OFFLINE"
    @Published var online = false
    @Published var policy: MobilePolicy = .disabled
    @Published var favorites: [String] = []
    @Published var muted: Set<String> = []
    @Published var message: String?

    private let keychain = KeychainStore()
    private let notificationsDelegate = NotificationDelegate()
    private let pref = UserDefaults.standard
    private let favKey = "ios_favorites_v160"
    private let mutedKey = "ios_muted_v160"
    private let pollKey = "ios_last_poll_v160"
    private let notifiedKey = "ios_notified_v160"

    init() {
        if let stored = keychain.load(), stored.validationError() == nil { config = stored }
        else { config = ServerConfig(); showSettings = true }
        favorites = (pref.stringArray(forKey: favKey) ?? []).sorted()
        muted = Set(pref.stringArray(forKey: mutedKey) ?? [])
        UNUserNotificationCenter.current().delegate = notificationsDelegate
    }

    var targetURL: URL? {
        guard let root = config.baseURL else { return nil }
        return URL(string: targetPath, relativeTo: root)?.absoluteURL
    }

    func save(_ c: ServerConfig) throws {
        if let e = c.validationError() { throw NSError(domain: "Alcomet", code: 1, userInfo: [NSLocalizedDescriptionKey: e]) }
        try keychain.save(c); config = c; targetPath = "/"; reloadToken = UUID()
    }

    func test(_ c: ServerConfig) async -> Result<String, Error> {
        if let e = c.validationError() { return .failure(NSError(domain: "Alcomet", code: 2, userInfo: [NSLocalizedDescriptionKey: e])) }
        guard let root = c.baseURL, let health = URL(string: "health", relativeTo: root)?.absoluteURL else { return .failure(NSError(domain: "Alcomet", code: 3, userInfo: [NSLocalizedDescriptionKey: "Invalid server URL."])) }
        var req = URLRequest(url: health, cachePolicy: .reloadIgnoringLocalAndRemoteCacheData, timeoutInterval: 5)
        req.setValue("application/json,text/plain", forHTTPHeaderField: "Accept")
        do {
            let (data,response) = try await URLSession.shared.data(for: req)
            guard let h = response as? HTTPURLResponse, h.statusCode == 200 else { throw NSError(domain: "Alcomet", code: 4, userInfo: [NSLocalizedDescriptionKey: "Server did not return HTTP 200."]) }
            var text = "Connected"
            if let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any], let v = obj["version"] as? String { text += " • CoilReport \(v)" }
            return .success(text)
        } catch { return .failure(error) }
    }

    func open(path: String) {
        guard path.hasPrefix("/"), !path.hasPrefix("//") else { return }
        targetPath = path; reloadToken = UUID()
    }
    func home() { open(path: "/") }
    func reload() { reloadToken = UUID() }
    func openCoil(_ coil: String, machine: String = "") {
        guard let c = ScanTarget.normalize(coil) else { return }
        var comps = URLComponents(); comps.path = "/"; var q = [URLQueryItem(name: "q", value: c)]
        if let m = ScanTarget.normalize(machine), !m.isEmpty { q.append(URLQueryItem(name: "machine", value: m)) }
        comps.queryItems = q; open(path: comps.string ?? "/")
    }

    func refreshPolicy() async {
        guard config.validationError() == nil, let root = config.baseURL, let u = URL(string: "mobile-capabilities.json", relativeTo: root)?.absoluteURL else { policy = .disabled; return }
        do {
            var r = URLRequest(url: u, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 8); r.setValue("application/json", forHTTPHeaderField: "Accept")
            let (data,response) = try await URLSession.shared.data(for: r)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
            var p = try JSONDecoder().decode(MobilePolicy.self, from: data)
            p.notifications = p.watchlist && p.notifications
            p.journeyNotifications = p.watchlist && p.notifications && p.journeyNotifications
            p.pollMinutes = min(240,max(15,p.pollMinutes))
            policy = p
        } catch { policy = .disabled }
    }

    func addFavorite(_ raw: String) -> Bool {
        guard policy.watchlist, let c = ScanTarget.normalize(raw) else { return false }
        if favorites.contains(c) { message = "\(c) is already in Favorites"; return true }
        favorites.append(c); favorites.sort(); persistFavorites(); message = "Added \(c)"
        let now = Int(Date().timeIntervalSince1970); if pref.integer(forKey: pollKey) == 0 { pref.set(now, forKey: pollKey) }
        return true
    }
    func removeFavorite(_ coil: String) { favorites.removeAll { $0 == coil }; muted.remove(coil); persistFavorites() }
    func clearFavorites() { favorites.removeAll(); muted.removeAll(); persistFavorites(); pref.set(Int(Date().timeIntervalSince1970), forKey: pollKey) }
    func isMuted(_ coil: String) -> Bool { muted.contains(coil) }
    func setMuted(_ coil: String, _ isMuted: Bool) { if isMuted { muted.insert(coil) } else { muted.remove(coil) }; persistFavorites() }
    private func persistFavorites() { pref.set(favorites, forKey: favKey); pref.set(Array(muted), forKey: mutedKey) }

    func requestNotifications() async -> Bool {
        guard policy.watchlist && policy.notifications else { return false }
        do { return try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert,.sound,.badge]) }
        catch { return false }
    }

    func testNotification() async {
        guard await requestNotifications() else { message = "Notification permission is required."; return }
        let c = UNMutableNotificationContent(); c.title = "CoilReport notification test"; c.body = "Favorite coil notifications are working."; c.sound = .default
        let r = UNNotificationRequest(identifier: "coilreport-test", content: c, trigger: UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false))
        do { try await UNUserNotificationCenter.current().add(r); message = "Test notification sent." }
        catch { message = "Notification test failed." }
    }

    func pollFavoriteEvents() async {
        guard policy.watchlist && policy.notifications, !favorites.isEmpty, let root = config.baseURL else { pref.set(Int(Date().timeIntervalSince1970), forKey: pollKey); return }
        let now = Int(Date().timeIntervalSince1970)
        var last = pref.integer(forKey: pollKey); if last <= 0 { last = now - 120 }
        guard let u = URL(string: "mobile-events.json?since=\(last)", relativeTo: root)?.absoluteURL else { return }
        do {
            let (data,response) = try await URLSession.shared.data(from: u)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { return }
            let env = try JSONDecoder().decode(EventEnvelope.self, from: data)
            var seen = Set(pref.stringArray(forKey: notifiedKey) ?? [])
            for e in env.events where !e.id.isEmpty && !seen.contains(e.id) && matchesFavorite(e) {
                guard !muted.contains(e.coil.uppercased()) else { continue }
                let c = UNMutableNotificationContent(); c.title = "Coil \(e.coil)"; c.sound = .default
                if policy.journeyNotifications && e.type == "handoff" && !e.previousMachine.isEmpty { c.body = "\(machineLabel(e.previousMachine)) → \(machineLabel(e.machine))" }
                else { c.body = "Detected at \(machineLabel(e.machine))" }
                c.userInfo = ["path": e.reportURL]
                try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: e.id, content: c, trigger: UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false)))
                seen.insert(e.id)
            }
            if seen.count > 250 { seen = Set(Array(seen).suffix(250)) }
            pref.set(Array(seen), forKey: notifiedKey); pref.set(now, forKey: pollKey)
        } catch { }
    }

    private func matchesFavorite(_ e: MobileEvent) -> Bool {
        let ec = e.coil.uppercased(), fam = e.family.uppercased()
        for w in favorites where !muted.contains(w) {
            if w == ec || (!isChild(w) && w == fam) { return true }
        }
        return false
    }
    private func isChild(_ coil: String) -> Bool {
        for s in policy.childSuffixes.split(separator: ",").map({ $0.trimmingCharacters(in: .whitespaces).uppercased() }) where !s.isEmpty {
            if coil.count > s.count && coil.hasSuffix(s) { return true }
        }
        return false
    }
    func machineLabel(_ raw: String) -> String {
        switch raw.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() {
        case "FM1": return "Foil Mill 1"; case "FM2": return "Foil Mill 2"; case "IM": return "Intermediate Mill"; case "CM": return "CM"; case "MINO": return "MINO"; case "SMS": return "SMS Cold Mill"; case "FM3": return "Foil Mill 3"; case "COLD": return "Cold Mill"; default: return raw.uppercased()
        }
    }
}

struct SecureWebView: UIViewRepresentable {
    @EnvironmentObject var model: AppModel
    let token: UUID
    func makeCoordinator() -> Coordinator { Coordinator(model: model) }
    func makeUIView(context: Context) -> WKWebView {
        let cfg = WKWebViewConfiguration(); cfg.websiteDataStore = .default(); cfg.defaultWebpagePreferences.allowsContentJavaScript = true; cfg.preferences.javaScriptCanOpenWindowsAutomatically = false
        let w = WKWebView(frame: .zero, configuration: cfg); w.navigationDelegate = context.coordinator; w.uiDelegate = context.coordinator; w.allowsBackForwardNavigationGestures = true
        return w
    }
    func updateUIView(_ web: WKWebView, context: Context) {
        context.coordinator.model = model
        guard let url = model.targetURL else { return }
        if context.coordinator.lastToken != token { context.coordinator.lastToken = token; web.load(URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)) }
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        var model: AppModel; var lastToken: UUID?
        init(model: AppModel) { self.model = model }
        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            let u = navigationAction.request.url
            if model.config.sameOrigin(u) || u?.absoluteString == "about:blank" { decisionHandler(.allow) } else { decisionHandler(.cancel) }
        }
        func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
            guard let u = navigationResponse.response.url else { decisionHandler(.cancel); return }
            let mime = navigationResponse.response.mimeType?.lowercased() ?? ""
            if model.policy.nativePdfShare && model.config.sameOrigin(u) && (mime == "application/pdf" || u.path.lowercased().contains("download-pdf")) {
                decisionHandler(.cancel); downloadPDF(webView, u); return
            }
            decisionHandler(.allow)
        }
        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) { Task { @MainActor in model.status = "CONNECTING"; model.online = false } }
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { Task { @MainActor in model.status = "ONLINE"; model.online = true } }
        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { offline() }
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { offline() }
        private func offline() { Task { @MainActor in model.status = "OFFLINE"; model.online = false } }
        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? { nil }

        private func downloadPDF(_ webView: WKWebView, _ url: URL) {
            webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies in
                var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 180)
                let headers = HTTPCookie.requestHeaderFields(with: cookies); for (k,v) in headers { req.setValue(v, forHTTPHeaderField: k) }
                URLSession.shared.dataTask(with: req) { data,response,error in
                    guard error == nil, let data, data.count > 5, String(data: data.prefix(5), encoding: .ascii) == "%PDF-" else {
                        Task { @MainActor in self.model.message = "PDF download failed." }; return
                    }
                    let name = self.fileName(response: response, url: url)
                    let file = FileManager.default.temporaryDirectory.appendingPathComponent(name)
                    do { try data.write(to: file, options: .atomic); DispatchQueue.main.async { self.share(file) } }
                    catch { Task { @MainActor in self.model.message = "PDF save failed." } }
                }.resume()
            }
        }
        private func fileName(response: URLResponse?, url: URL) -> String {
            if let h = response as? HTTPURLResponse, let d = h.value(forHTTPHeaderField: "Content-Disposition"), let r = d.range(of: "filename=") {
                let n = String(d[r.upperBound...]).trimmingCharacters(in: CharacterSet(charactersIn: "\"' "))
                if !n.isEmpty { return n.replacingOccurrences(of: "/", with: "_") }
            }
            let last = url.lastPathComponent.lowercased().hasSuffix(".pdf") ? url.lastPathComponent : "Alcomet_Coil_Report.pdf"
            return last.isEmpty ? "Alcomet_Coil_Report.pdf" : last
        }
        private func share(_ file: URL) {
            guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first,
                  let root = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController else { return }
            var top = root; while let p = top.presentedViewController { top = p }
            let a = UIActivityViewController(activityItems: [file], applicationActivities: nil)
            if let pop = a.popoverPresentationController { pop.sourceView = top.view; pop.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 1, height: 1) }
            top.present(a, animated: true)
        }
    }
}

struct ScannerView: UIViewControllerRepresentable {
    let onResult: (String) -> Void
    let onCancel: () -> Void
    func makeUIViewController(context: Context) -> ScannerController { ScannerController(onResult: onResult, onCancel: onCancel) }
    func updateUIViewController(_ uiViewController: ScannerController, context: Context) { }
}

final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let session = AVCaptureSession(); private var preview: AVCaptureVideoPreviewLayer?; private var torchOn = false; private var finished = false
    private let onResult: (String) -> Void; private let onCancel: () -> Void
    init(onResult: @escaping (String) -> Void, onCancel: @escaping () -> Void) { self.onResult = onResult; self.onCancel = onCancel; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError() }
    override func viewDidLoad() { super.viewDidLoad(); view.backgroundColor = .black; buildOverlay(); authorize() }
    override func viewDidLayoutSubviews() { super.viewDidLayoutSubviews(); preview?.frame = view.bounds }
    override func viewWillDisappear(_ animated: Bool) { session.stopRunning(); super.viewWillDisappear(animated) }

    private func authorize() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: configure()
        case .notDetermined: AVCaptureDevice.requestAccess(for: .video) { ok in DispatchQueue.main.async { if ok { self.configure() } else { self.onCancel() } } }
        default: onCancel()
        }
    }
    private func configure() {
        guard let device = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else { onCancel(); return }
        session.beginConfiguration(); session.addInput(input)
        let out = AVCaptureMetadataOutput(); guard session.canAddOutput(out) else { session.commitConfiguration(); onCancel(); return }; session.addOutput(out)
        out.setMetadataObjectsDelegate(self, queue: .main)
        let wanted: [AVMetadataObject.ObjectType] = [.qr,.code128,.code39,.code93,.dataMatrix,.ean13,.ean8,.upce,.interleaved2of5]
        out.metadataObjectTypes = wanted.filter { out.availableMetadataObjectTypes.contains($0) }
        session.commitConfiguration()
        let layer = AVCaptureVideoPreviewLayer(session: session); layer.videoGravity = .resizeAspectFill; layer.frame = view.bounds; view.layer.insertSublayer(layer, at: 0); preview = layer
        DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
    }
    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !finished, let m = metadataObjects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject }).first, let value = m.stringValue, !value.isEmpty else { return }
        finished = true; UIImpactFeedbackGenerator(style: .medium).impactOccurred(); session.stopRunning(); onResult(value)
    }
    private func buildOverlay() {
        let title = UILabel(); title.text = "AUTO SCANNING"; title.textColor = .white; title.font = .boldSystemFont(ofSize: 17); title.textAlignment = .center; title.translatesAutoresizingMaskIntoConstraints = false
        let hint = UILabel(); hint.text = "QR + barcode • result opens automatically"; hint.textColor = UIColor(white: 0.82, alpha: 1); hint.font = .systemFont(ofSize: 12); hint.textAlignment = .center; hint.translatesAutoresizingMaskIntoConstraints = false
        let cancel = UIButton(type: .system); cancel.setTitle("Cancel", for: .normal); cancel.addTarget(self, action: #selector(cancelTap), for: .touchUpInside); cancel.translatesAutoresizingMaskIntoConstraints = false
        let torch = UIButton(type: .system); torch.setTitle("Torch", for: .normal); torch.addTarget(self, action: #selector(torchTap(_:)), for: .touchUpInside); torch.translatesAutoresizingMaskIntoConstraints = false
        let guide = UIView(); guide.layer.borderWidth = 2; guide.layer.borderColor = UIColor(red: 0.35, green: 0.78, blue: 1, alpha: 1).cgColor; guide.layer.cornerRadius = 14; guide.translatesAutoresizingMaskIntoConstraints = false
        [guide,title,hint,cancel,torch].forEach(view.addSubview)
        NSLayoutConstraint.activate([
            guide.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 28), guide.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -28), guide.centerYAnchor.constraint(equalTo: view.centerYAnchor), guide.heightAnchor.constraint(equalToConstant: 150),
            title.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 14), title.centerXAnchor.constraint(equalTo: view.centerXAnchor), hint.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 5), hint.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            cancel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 32), cancel.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -18), cancel.widthAnchor.constraint(equalToConstant: 120), cancel.heightAnchor.constraint(equalToConstant: 48),
            torch.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -32), torch.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -18), torch.widthAnchor.constraint(equalToConstant: 120), torch.heightAnchor.constraint(equalToConstant: 48)
        ])
    }
    @objc private func cancelTap() { session.stopRunning(); onCancel() }
    @objc private func torchTap(_ sender: UIButton) {
        guard let d = AVCaptureDevice.default(for: .video), d.hasTorch else { return }
        do { try d.lockForConfiguration(); torchOn.toggle(); try d.setTorchModeOn(level: torchOn ? 1 : 0); if !torchOn { d.torchMode = .off }; d.unlockForConfiguration(); sender.setTitle(torchOn ? "Torch ON" : "Torch", for: .normal) } catch { }
    }
}

struct SettingsView: View {
    @EnvironmentObject var model: AppModel; @Environment(\.dismiss) var dismiss
    @State private var draft = ServerConfig(); @State private var state = "Not tested"; @State private var ok = false; @State private var testing = false
    var body: some View {
        NavigationStack {
            Form {
                Section("Operator server") {
                    Picker("Protocol", selection: $draft.scheme) { Text("HTTPS").tag("https"); Text("HTTP • private LAN").tag("http") }
                    TextField("Server IP / hostname", text: $draft.host).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.numbersAndPunctuation)
                    TextField("5051", value: $draft.port, format: .number).keyboardType(.numberPad)
                    if draft.scheme == "http" { Toggle("Allow private-LAN HTTP", isOn: $draft.allowLanHTTP) }
                }
                Section { Text("Operator service only. Port 5050 is blocked. HTTPS certificate errors are never bypassed.").font(.footnote).foregroundStyle(.secondary) }
                Section {
                    Text(state).font(.footnote).foregroundStyle(ok ? .green : .secondary)
                    Button(testing ? "Testing…" : "Test connection") { Task { await test() } }.disabled(testing)
                    Button("Save & connect") { save() }.disabled(testing)
                }
                Section { Text("Alcomet Coil Archive • iOS v1.6.0").font(.caption).foregroundStyle(.secondary) }
            }
            .navigationTitle("Connection").navigationBarTitleDisplayMode(.inline)
            .toolbar { if model.config.validationError() == nil && !model.config.host.isEmpty { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } } }
            .onAppear { draft = model.config }
        }.preferredColorScheme(.dark)
    }
    @MainActor private func test() async { if let e = draft.validationError() { state=e;ok=false;return }; testing=true;state="Testing…";ok=false;let r=await model.test(draft);testing=false;switch r{case .success(let s):state=s;ok=true;case .failure(let e):state="Connection failed: \(e.localizedDescription)";ok=false} }
    private func save() { if let e=draft.validationError(){state=e;ok=false;return};do{try model.save(draft);Task{await model.refreshPolicy()};dismiss()}catch{state=error.localizedDescription;ok=false} }
}

struct FavoritesView: View {
    @EnvironmentObject var model: AppModel; @Environment(\.dismiss) var dismiss
    @State private var coil = ""; @State private var scanning = false; @State private var clearConfirm = false
    var body: some View {
        NavigationStack {
            List {
                Section("Add coil") {
                    HStack { TextField("Coil ID", text: $coil).textInputAutocapitalization(.characters).autocorrectionDisabled(); Button("Add") { if model.addFavorite(coil) { coil = "" } } }
                    if model.policy.qrBarcodeSearch { Button { scanning = true } label: { Label("Scan + Add", systemImage: "barcode.viewfinder") } }
                }
                Section("Favorites • \(model.favorites.count)") {
                    if model.favorites.isEmpty { Text("No favorite coils.").foregroundStyle(.secondary) }
                    ForEach(model.favorites, id: \.self) { c in
                        VStack(alignment: .leading, spacing: 8) {
                            HStack { Text(c).font(.headline); Spacer(); if model.policy.notifications { Toggle("", isOn: Binding(get: { !model.isMuted(c) }, set: { on in model.setMuted(c,!on); if on { Task { _ = await model.requestNotifications() } } })).labelsHidden() } }
                            HStack { Button("Open") { dismiss(); model.openCoil(c) }.buttonStyle(.borderedProminent); Button("Remove", role: .destructive) { model.removeFavorite(c) }.buttonStyle(.bordered) }
                        }.padding(.vertical,4)
                    }
                }
                if model.policy.notifications { Section { Button { Task { await model.testNotification() } } label: { Label("Test notification", systemImage: "bell.badge") } } }
                if !model.favorites.isEmpty { Section { Button("Clear all favorites", role: .destructive) { clearConfirm = true } } }
            }
            .navigationTitle("Favorites").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .confirmationDialog("Clear all favorites?", isPresented: $clearConfirm) { Button("Clear All", role: .destructive) { model.clearFavorites() } }
            .sheet(isPresented: $scanning) { ScannerView(onResult: { raw in scanning=false; if let t=ScanTarget.parse(raw) { _=model.addFavorite(t.coil) } }, onCancel: { scanning=false }).ignoresSafeArea() }
            .onChange(of: model.policy.watchlist) { enabled in if !enabled { dismiss() } }
        }.preferredColorScheme(.dark)
    }
}

struct ContentView: View {
    @EnvironmentObject var model: AppModel; @Environment(\.scenePhase) var scenePhase
    @State private var scanning = false; @State private var showFavorites = false
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Text("A").font(.headline).foregroundStyle(.white).frame(width: 32,height: 32).background(Color(red: 18/255,green: 102/255,blue: 143/255)).clipShape(RoundedRectangle(cornerRadius: 7))
                VStack(alignment:.leading,spacing:0){Text("COIL ARCHIVE").font(.caption.bold()).foregroundStyle(.white);Text(model.status).font(.caption2).foregroundStyle(model.online ? .green : .secondary)}
                Spacer()
                Button { model.home() } label: { Image(systemName:"house").frame(width:36,height:36) }
                if model.policy.qrBarcodeSearch { Button { scanning=true } label: { Image(systemName:"barcode.viewfinder").frame(width:36,height:36) } }
                if model.policy.watchlist { Button { showFavorites=true } label: { Image(systemName:"star.fill").frame(width:36,height:36) } }
                Menu {
                    Button { model.reload() } label: { Label("Reload",systemImage:"arrow.clockwise") }
                    Button { model.showSettings=true } label: { Label("Connection",systemImage:"slider.horizontal.3") }
                } label: { Image(systemName:"ellipsis.circle").frame(width:36,height:36) }
            }.padding(.horizontal,10).frame(height:48).background(Color(red:8/255,green:19/255,blue:29/255))

            if model.config.validationError() == nil && !model.config.host.isEmpty {
                SecureWebView(token:model.reloadToken).environmentObject(model).id(model.config)
            } else {
                VStack(spacing:14){Spacer();Text("Operator server offline").font(.title2.bold()).foregroundStyle(.white);Text("Configure the CoilReport Operator server.").foregroundStyle(.secondary);Button("Connection settings"){model.showSettings=true}.buttonStyle(.borderedProminent);Spacer()}.frame(maxWidth:.infinity).background(Color(red:8/255,green:19/255,blue:29/255))
            }
        }
        .tint(Color(red:70/255,green:167/255,blue:223/255)).preferredColorScheme(.dark)
        .sheet(isPresented:$model.showSettings){SettingsView().environmentObject(model)}
        .sheet(isPresented:$showFavorites){FavoritesView().environmentObject(model)}
        .sheet(isPresented:$scanning){ScannerView(onResult:{ raw in scanning=false;if let t=ScanTarget.parse(raw){model.openCoil(t.coil,machine:t.machine)}else{model.message="Barcode does not contain a valid coil ID."}},onCancel:{scanning=false}).ignoresSafeArea()}
        .alert("Coil Archive",isPresented:Binding(get:{model.message != nil},set:{if !$0{model.message=nil}})){Button("OK"){model.message=nil}} message:{Text(model.message ?? "")}
        .task { while !Task.isCancelled { await model.refreshPolicy(); await model.pollFavoriteEvents(); try? await Task.sleep(nanoseconds:60_000_000_000) } }
        .onChange(of:scenePhase){ phase in if phase == .active { Task { await model.refreshPolicy(); await model.pollFavoriteEvents() } } }
    }
}

@main struct AlcometCoilArchiveApp: App {
    @StateObject private var model=AppModel()
    var body: some Scene { WindowGroup { ContentView().environmentObject(model) } }
}
