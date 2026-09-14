extension ServerConfig: Hashable {
    func hash(into hasher: inout Hasher) {
        hasher.combine(scheme)
        hasher.combine(host)
        hasher.combine(port)
        hasher.combine(allowLanHTTP)
    }
}
