package vpn.qartz.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress

/**
 * Builds an xray-core config for the XHTTP servers. xray runs as a child process exposing a local
 * SOCKS inbound; sing-box routes its final outbound into that SOCKS (hybrid engine, same shape as
 * the desktop client). Each server's real IP is pinned (resolved here) and returned so sing-box can
 * route that IP DIRECT — otherwise xray's own traffic to the server would loop back into the tun.
 */
object XrayConfig {

    const val SOCKS_PORT = 24808
    private const val PROBE_URL = "https://www.gstatic.com/generate_204"

    data class Result(val json: String, val directIps: List<String>)

    /** Resolve a host to a literal IP; returns the input unchanged if it already is one / on failure. */
    private fun resolve(host: String): String? = try {
        if (host.isEmpty()) null else InetAddress.getByName(host).hostAddress
    } catch (e: Exception) {
        null
    }

    fun build(xrayServers: List<ServerSpec>): Result {
        val outbounds = JSONArray()
        val ips = mutableSetOf<String>()

        for (s in xrayServers) {
            val ob = s.xray ?: continue
            val ip = resolve(s.host)
            if (ip != null) {
                ips.add(ip)
                runCatching {
                    ob.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).put("address", ip)
                }
            }
            outbounds.put(ob)
        }

        val inbound = JSONObject()
            .put("tag", "socks-in")
            .put("protocol", "socks")
            .put("listen", "127.0.0.1")
            .put("port", SOCKS_PORT)
            .put("settings", JSONObject().put("udp", true).put("auth", "noauth"))

        val cfg = JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", outbounds)

        // Health-based failover across multiple XHTTP servers (xray observatory + balancer).
        // A single server is just the default outbound — no balancer needed.
        if (outbounds.length() > 1) {
            cfg.put(
                "observatory",
                JSONObject()
                    .put("subjectSelector", JSONArray().put("x"))
                    .put("probeURL", PROBE_URL)
                    .put("probeInterval", "10s"),
            )
            cfg.put(
                "routing",
                JSONObject()
                    .put(
                        "balancers",
                        JSONArray().put(
                            JSONObject()
                                .put("tag", "b")
                                .put("selector", JSONArray().put("x"))
                                .put("strategy", JSONObject().put("type", "leastPing")),
                        ),
                    )
                    .put(
                        "rules",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "field")
                                .put("inboundTag", JSONArray().put("socks-in"))
                                .put("balancerTag", "b"),
                        ),
                    ),
            )
        }

        return Result(cfg.toString(), ips.toList())
    }
}
