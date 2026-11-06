package vpn.qartz.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress

/**
 * Builds an xray-core config for the VLESS servers. xray runs as a child process and exposes ONE
 * local SOCKS inbound per gateway (routed to that gateway's outbound). sing-box then has one urltest
 * member per gateway, so the active gateway selection is made — and readable — in sing-box.
 * Each gateway IP is pinned (resolved here) and returned so sing-box routes it DIRECT (xray's own
 * traffic to the gateway must bypass the tun).
 */
object XrayConfig {

    const val SOCKS_PORT_BASE = 24808

    /** A sing-box-facing member: the urltest tag, the human gateway name, and the local socks port. */
    data class Member(val tag: String, val name: String, val port: Int)

    data class Result(val json: String, val directIps: List<String>, val members: List<Member>)

    private fun resolve(host: String): String? = try {
        if (host.isEmpty()) null else InetAddress.getByName(host).hostAddress
    } catch (e: Exception) {
        null
    }

    fun build(xrayServers: List<ServerSpec>): Result {
        val inbounds = JSONArray()
        val outbounds = JSONArray()
        val rules = JSONArray()
        val ips = linkedSetOf<String>()
        val members = mutableListOf<Member>()

        xrayServers.forEachIndexed { i, s ->
            val ob = s.xray ?: return@forEachIndexed
            val tag = ob.optString("tag").ifEmpty { "x$i" }
            resolve(s.host)?.let { ip ->
                ips.add(ip)
                runCatching {
                    ob.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).put("address", ip)
                }
            }
            outbounds.put(ob)

            val port = SOCKS_PORT_BASE + i
            val inTag = "in-$tag"
            inbounds.put(
                JSONObject()
                    .put("tag", inTag)
                    .put("protocol", "socks")
                    .put("listen", "127.0.0.1")
                    .put("port", port)
                    .put("settings", JSONObject().put("udp", true).put("auth", "noauth")),
            )
            rules.put(
                JSONObject().put("type", "field")
                    .put("inboundTag", JSONArray().put(inTag))
                    .put("outboundTag", tag),
            )
            members.add(Member(tag, s.name, port))
        }

        val cfg = JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("rules", rules))

        return Result(cfg.toString(), ips.toList(), members)
    }
}
