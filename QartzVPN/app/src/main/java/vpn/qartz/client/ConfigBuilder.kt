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
    private const val TEST_URL = "https://www.gstatic.com/generate_204"

    private val LAN_RANGES = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "224.0.0.0/4",
    )

    private val RU_SUFFIXES = listOf(".ru", ".su", "xn--p1ai")

    fun build(
        sbServers: List<ServerSpec>,
        xrayPresent: Boolean = false,
        socksPort: Int = XrayConfig.SOCKS_PORT,
        directIps: List<String> = emptyList(),
        allowLan: Boolean = true,
        ruDirect: Boolean = true,
        directDns: String? = null,
    ): String {
        require(sbServers.isNotEmpty() || xrayPresent) { "no servers" }

        val outbounds = JSONArray()
        val groupMembers = JSONArray()
        for (s in sbServers) {
            outbounds.put(s.outbound)
            groupMembers.put(s.tag)
        }
        // xray (XHTTP) appears as one socks member pointing at the local xray process.
        if (xrayPresent) {
            outbounds.put(
                JSONObject()
                    .put("type", "socks")
                    .put("tag", "xray")
                    .put("server", "127.0.0.1")
                    .put("server_port", socksPort)
                    .put("version", "5"),
            )
            groupMembers.put("xray")
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
            .put(
                JSONObject().put("type", "udp").put("tag", "dns-direct")
                    .put("server", directDns ?: "8.8.8.8").put("detour", "direct"),
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
            .put("mtu", 9000)
            .put("auto_route", true)
            .put("strict_route", true)
            .put("stack", "gvisor")
        if (allowLan) {
            val exclude = JSONArray()
            LAN_RANGES.forEach { exclude.put(it) }
            tun.put("route_exclude_address", exclude)
        }

        val rules = JSONArray()
            .put(JSONObject().put("action", "sniff"))
            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
        // xray's own traffic to the XHTTP server(s) must bypass the tun, or it loops.
        if (directIps.isNotEmpty()) {
            val cidrs = JSONArray()
            directIps.forEach { cidrs.put(if (it.contains(":")) "$it/128" else "$it/32") }
            rules.put(JSONObject().put("ip_cidr", cidrs).put("outbound", "direct"))
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

        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", dns)
            .put("inbounds", JSONArray().put(tun))
            .put("outbounds", outbounds)
            .put("route", route)
            .toString(2)
    }
}
