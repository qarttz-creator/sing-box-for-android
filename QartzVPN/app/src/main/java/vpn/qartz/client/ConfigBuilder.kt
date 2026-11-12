package vpn.qartz.client

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a complete sing-box config from parsed servers. In the hybrid engine the XHTTP servers are
 * served by a local xray process; here they appear as a single "socks" member of the urltest group,
 * and the xray server IPs are routed DIRECT so xray's own traffic escapes the tun (same as desktop).
 */
object ConfigBuilder {

    private const val GROUP = "proxy"
    // Проверочный URL urltest'а бьём по IP, НЕ по домену: AWG везёт только IP-пакеты и домен цели
    // резолвит клиентски через ещё не поднятый туннель → валится «cannot marshal DNS message», и
    // awg-маршруты числятся мёртвыми. 1.1.1.1/cdn-cgi/trace отдаёт 200 сразу, без редиректа на домен
    // (серт Cloudflare покрывает 1.1.1.1), поэтому резолв не нужен ни одному члену группы.
    private const val TEST_URL = "https://1.1.1.1/cdn-cgi/trace"

    private val LAN_RANGES = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "224.0.0.0/4",
    )

    private val RU_SUFFIXES = listOf(".ru", ".su", "xn--p1ai")

    fun build(
        sbServers: List<ServerSpec>,
        xrayMembers: List<XrayConfig.Member> = emptyList(),
        awgServers: List<ServerSpec> = emptyList(),
        directIps: List<String> = emptyList(),
        allowLan: Boolean = true,
        ruDirect: Boolean = true,
        includePackages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
    ): String {
        require(sbServers.isNotEmpty() || xrayMembers.isNotEmpty() || awgServers.isNotEmpty()) { "no servers" }

        val outbounds = JSONArray()
        val endpoints = JSONArray()
        val groupMembers = JSONArray()
        for (s in sbServers) {
            outbounds.put(s.outbound)
            groupMembers.put(s.tag)
        }
        // Each VLESS gateway appears as its own socks member → the local xray inbound for that gateway.
        for (m in xrayMembers) {
            outbounds.put(
                JSONObject()
                    .put("type", "socks")
                    .put("tag", m.tag)
                    .put("server", "127.0.0.1")
                    .put("server_port", m.port)
                    .put("version", "5"),
            )
            groupMembers.put(m.tag)
        }
        // AmneziaWG gateways are sing-box `awg` endpoints (top-level "endpoints"); their tag is a
        // normal route target, so they join the urltest group like any other member.
        val awgDirectIps = mutableListOf<String>()
        val awgDirectDomains = mutableListOf<String>()
        for (s in awgServers) {
            endpoints.put(s.outbound)
            groupMembers.put(s.tag)
            if (s.host.isNotEmpty()) {
                if (isIpLiteral(s.host)) awgDirectIps.add(s.host) else awgDirectDomains.add(s.host)
            }
        }

        // urltest = client-side balancer / auto-failover across the given members.
        outbounds.put(
            JSONObject()
                .put("type", "urltest")
                .put("tag", GROUP)
                .put("outbounds", groupMembers)
                .put("url", TEST_URL)
                .put("interval", "30s")
                .put("tolerance", 100)
                .put("idle_timeout", "5m")
                .put("interrupt_exist_connections", true),
        )
        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))

        // DNS: remote = DoH over the proxy; direct = the device's own resolver (via the direct
        // outbound) for bootstrap, RU and the health-check host. NOTE: sing-box `type: local` is
        // unusable on Android here — it hits ::1:53 (connection refused) — so we use the real
        // network DNS passed in, falling back to a public resolver.
        val dnsServers = JSONArray()
            .put(
                JSONObject().put("type", "https").put("tag", "dns-remote")
                    .put("server", "8.8.8.8").put("detour", GROUP),
            )
            // DoH with NO detour: sing-box dials a detour-less DNS server directly (past the tun via
            // auto_detect_interface), which is what we want for bootstrap/RU/probe. Detouring to the
            // plain direct outbound is rejected ("detour to an empty direct outbound makes no sense").
            .put(
                JSONObject().put("type", "https").put("tag", "dns-direct")
                    .put("server", "8.8.8.8"),
            )

        val dnsRules = JSONArray()
            .put(
                JSONObject().put("domain", JSONArray().put("www.gstatic.com"))
                    .put("server", "dns-direct"),
            )
        if (ruDirect) {
            val ruDns = JSONArray()
            RU_SUFFIXES.forEach { ruDns.put(it) }
            dnsRules.put(JSONObject().put("domain_suffix", ruDns).put("server", "dns-direct"))
        }

        val dns = JSONObject()
            .put("servers", dnsServers)
            .put("rules", dnsRules)
            .put("final", "dns-remote")
            .put("strategy", "ipv4_only")
            .put("independent_cache", true)

        val tun = JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("address", JSONArray().put("172.19.0.1/30"))
            // Low MTU so TLS/web survives the deeply-nested CDN path (client → WS+TLS → Cloudflare
            // → xray → reality/hysteria → exit). At 9000 small-packet apps (Telegram) worked but web
            // TLS handshakes were dropped. 1280 is the safe floor; raise later if throughput needs it.
            .put("mtu", 1280)
            .put("auto_route", true)
            .put("strict_route", true)
            .put("stack", "gvisor")
        if (allowLan) {
            val exclude = JSONArray()
            LAN_RANGES.forEach { exclude.put(it) }
            tun.put("route_exclude_address", exclude)
        }
        // Per-app routing (sing-box tun handles it; the fork libbox has no runtime OverrideOptions):
        // include = only these apps go through the tunnel; exclude = these apps bypass it.
        if (includePackages.isNotEmpty()) {
            val arr = JSONArray(); includePackages.forEach { arr.put(it) }
            tun.put("include_package", arr)
        }
        if (excludePackages.isNotEmpty()) {
            val arr = JSONArray(); excludePackages.forEach { arr.put(it) }
            tun.put("exclude_package", arr)
        }

        val rules = JSONArray()
            .put(JSONObject().put("action", "sniff"))
            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
        // xray's own traffic to the XHTTP server(s), and the AWG handshake to its gateway, must bypass
        // the tun or they loop. (The awg endpoint dials direct via auto_detect_interface too, but pin
        // the peer IP to be safe.)
        val allDirectIps = directIps + awgDirectIps
        if (allDirectIps.isNotEmpty()) {
            val cidrs = JSONArray()
            allDirectIps.forEach { cidrs.put(if (it.contains(":")) "$it/128" else "$it/32") }
            rules.put(JSONObject().put("ip_cidr", cidrs).put("outbound", "direct"))
        }
        if (awgDirectDomains.isNotEmpty()) {
            val doms = JSONArray()
            awgDirectDomains.forEach { doms.put(it) }
            rules.put(JSONObject().put("domain", doms).put("outbound", "direct"))
        }
        if (ruDirect) {
            val ru = JSONArray()
            RU_SUFFIXES.forEach { ru.put(it) }
            rules.put(JSONObject().put("domain_suffix", ru).put("outbound", "direct"))
        }
        rules.put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))

        val route = JSONObject()
            .put("rules", rules)
            .put("final", GROUP)
            .put("auto_detect_interface", true)
            .put("default_domain_resolver", JSONObject().put("server", "dns-direct"))

        val root = JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", dns)
            .put("inbounds", JSONArray().put(tun))
            .put("outbounds", outbounds)
            .put("route", route)
        if (endpoints.length() > 0) root.put("endpoints", endpoints)
        return root.toString(2)
    }

    /** Rough IP-literal check (IPv4 dotted-quad or anything with ':' = IPv6) to decide direct routing. */
    private fun isIpLiteral(host: String): Boolean =
        host.contains(":") || host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))
}
