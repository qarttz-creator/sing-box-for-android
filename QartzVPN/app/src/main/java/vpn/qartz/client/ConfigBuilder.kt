package vpn.qartz.client

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a complete sing-box config from parsed servers, baking in the exact environment that
 * worked on-device: TCP remote DNS via proxy, ipv4_only, gvisor tun, sniff + hijack-dns, and a
 * urltest group so several gateways auto-failover (client-side balancer).
 */
object ConfigBuilder {

    private const val GROUP = "proxy"
    private const val TEST_URL = "https://www.gstatic.com/generate_204"

    fun build(servers: List<ServerSpec>): String {
        require(servers.isNotEmpty()) { "no servers" }

        val outbounds = JSONArray()
        val groupMembers = JSONArray()
        for (s in servers) {
            outbounds.put(s.outbound)
            groupMembers.put(s.tag)
        }
        // urltest = client-side balancer / auto-failover across the given servers
        outbounds.put(
            JSONObject()
                .put("type", "urltest")
                .put("tag", GROUP)
                .put("outbounds", groupMembers)
                .put("url", TEST_URL)
                .put("interval", "30s")                     // fast health re-check for quick failover
                .put("tolerance", 100)
                .put("idle_timeout", "5m")
                .put("interrupt_exist_connections", true),  // drop dead-gateway conns on switch
        )
        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))

        val dns = JSONObject()
            .put(
                "servers",
                JSONArray().put(
                    JSONObject().put("type", "tcp").put("tag", "dns-remote")
                        .put("server", "8.8.8.8").put("detour", GROUP),
                ),
            )
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

        val route = JSONObject()
            .put(
                "rules",
                JSONArray()
                    .put(JSONObject().put("action", "sniff"))
                    .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
                    .put(JSONObject().put("ip_is_private", true).put("outbound", "direct")),
            )
            .put("final", GROUP)
            .put("auto_detect_interface", true)

        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", dns)
            .put("inbounds", JSONArray().put(tun))
            .put("outbounds", outbounds)
            .put("route", route)
            .toString(2)
    }
}
