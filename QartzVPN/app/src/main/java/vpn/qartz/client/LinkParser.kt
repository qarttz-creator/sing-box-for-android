package vpn.qartz.client

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Which core runs a given server. xray handles all VLESS (reality/grpc/xhttp/ws/vision) — it is
 *  proven to connect to these gateways where sing-box does not; sing-box handles hysteria2
 *  (which xray can't). */
enum class CoreKind { SINGBOX, XRAY, AWG }

/**
 * A parsed server.
 *  - SINGBOX: [outbound] is a ready sing-box outbound object.
 *  - XRAY:    [xray] is a ready xray outbound object; [host]/[port] are the real endpoint
 *             (used to pin a direct route so xray's own traffic escapes the sing-box tun).
 */
data class ServerSpec(
    val name: String,
    val tag: String,
    val kind: CoreKind,
    val outbound: JSONObject,
    val host: String = "",
    val port: Int = 0,
    val xray: JSONObject? = null,
)

/**
 * Parses share links. VLESS → xray core (the gateways' vless+reality connects via xray but not via
 * sing-box, whatever the exact cause); hysteria2 → sing-box core.
 */
object LinkParser {

    fun parse(raw: String, index: Int): ServerSpec? {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return null
        return try {
            val uri = Uri.parse(line)
            when (uri.scheme?.lowercase()) {
                "vless" -> vless(uri, index)
                "hysteria2", "hy2" -> hysteria2(uri, index)
                "awg", "amneziawg" -> awg(line, index)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun name(uri: Uri, index: Int): String {
        val frag = uri.fragment?.trim().orEmpty()
        return if (frag.isNotEmpty()) frag else "Сервер ${index + 1}"
    }

    /** VLESS (any transport/security) → an xray outbound object. */
    private fun vless(uri: Uri, index: Int): ServerSpec? {
        val uuid = uri.userInfo ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        fun q(k: String) = uri.getQueryParameter(k)
        val tag = "x$index"

        val user = JSONObject().put("id", uuid).put("encryption", "none")
        q("flow")?.takeIf { it.isNotEmpty() }?.let { user.put("flow", it) }

        val network = (q("type") ?: "tcp").lowercase().let { if (it == "splithttp") "xhttp" else it }
        val stream = JSONObject().put("network", network)

        // security
        val security = q("security")?.lowercase() ?: "none"
        stream.put("security", if (security == "reality" || security == "tls") security else "none")
        val sni = q("sni")?.takeIf { it.isNotEmpty() }
        val fp = q("fp")?.takeIf { it.isNotEmpty() } ?: "chrome"
        if (security == "reality") {
            val r = JSONObject()
                .put("publicKey", q("pbk").orEmpty())
                .put("shortId", q("sid").orEmpty())
                .put("fingerprint", fp)
                .put("spiderX", q("spx") ?: "/")
            if (sni != null) r.put("serverName", sni)
            stream.put("realitySettings", r)
        } else if (security == "tls") {
            val t = JSONObject().put("fingerprint", fp)
            if (sni != null) t.put("serverName", sni)
            q("alpn")?.takeIf { it.isNotEmpty() }
                ?.let { t.put("alpn", JSONArray(it.split(",").map { a -> a.trim() })) }
            if (q("allowInsecure") == "1" || q("insecure") == "1") t.put("allowInsecure", true)
            stream.put("tlsSettings", t)
        }

        // transport
        when (network) {
            "grpc" -> {
                val g = JSONObject().put("serviceName", q("serviceName") ?: q("servicename") ?: "")
                if ((q("mode") ?: "").equals("multi", true)) g.put("multiMode", true)
                q("authority")?.takeIf { it.isNotEmpty() }?.let { g.put("authority", it) }
                stream.put("grpcSettings", g)
            }
            "ws" -> {
                val w = JSONObject().put("path", q("path")?.takeIf { it.isNotEmpty() } ?: "/")
                q("host")?.takeIf { it.isNotEmpty() }?.let { w.put("headers", JSONObject().put("Host", it)) }
                stream.put("wsSettings", w)
            }
            "httpupgrade" -> {
                val h = JSONObject().put("path", q("path")?.takeIf { it.isNotEmpty() } ?: "/")
                q("host")?.takeIf { it.isNotEmpty() }?.let { h.put("host", it) }
                stream.put("httpupgradeSettings", h)
            }
            "xhttp" -> {
                val x = JSONObject()
                    .put("path", q("path")?.takeIf { it.isNotEmpty() } ?: "/")
                    .put("mode", q("mode")?.takeIf { it.isNotEmpty() } ?: "auto")
                q("host")?.takeIf { it.isNotEmpty() }?.let { x.put("host", it) }
                stream.put("xhttpSettings", x)
            }
            // "tcp" and anything else: no transport settings
        }

        val outbound = JSONObject()
            .put("tag", tag)
            .put("protocol", "vless")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", host) // XrayConfig pins this to a resolved IP
                            .put("port", port)
                            .put("users", JSONArray().put(user)),
                    ),
                ),
            )
            .put("streamSettings", stream)

        return ServerSpec(name(uri, index), tag, CoreKind.XRAY, JSONObject(), host, port, outbound)
    }

    private fun hysteria2(uri: Uri, index: Int): ServerSpec? {
        val password = uri.userInfo ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        fun q(k: String) = uri.getQueryParameter(k)
        val tag = "s$index"

        val out = JSONObject()
            .put("type", "hysteria2")
            .put("tag", tag)
            .put("server", host)
            .put("server_port", port)
            .put("password", password)

        if (q("obfs")?.lowercase() == "salamander") {
            out.put(
                "obfs",
                JSONObject().put("type", "salamander").put("password", q("obfs-password").orEmpty()),
            )
        }

        val tls = JSONObject().put("enabled", true)
        q("sni")?.takeIf { it.isNotEmpty() }?.let { tls.put("server_name", it) }
        if (q("insecure") == "1" || q("allowInsecure") == "1") tls.put("insecure", true)
        out.put("tls", tls)

        return ServerSpec(name(uri, index), tag, CoreKind.SINGBOX, out)
    }

    /** awg://<base64(AmneziaWG INI)>#name → a sing-box `awg` endpoint (for subscriptions). */
    private fun awg(line: String, index: Int): ServerSpec? {
        val hash = line.indexOf('#')
        val name = if (hash >= 0) Uri.decode(line.substring(hash + 1)) else "AWG ${index + 1}"
        val afterScheme = line.substringAfter("://", "")
        val b64 = (if (hash >= 0) afterScheme.substringBefore('#') else afterScheme).trim()
        if (b64.isEmpty()) return null
        val ini = decodeB64(b64) ?: return null
        val tag = "a$index"
        val ep = AwgConfig.parseIni(ini, tag) ?: return null
        val peer = ep.optJSONArray("peers")?.optJSONObject(0)
        val host = peer?.optString("address").orEmpty()
        val port = peer?.optInt("port") ?: 0
        return ServerSpec(name, tag, CoreKind.AWG, ep, host, port)
    }

    private fun decodeB64(s: String): String? = try {
        String(android.util.Base64.decode(s, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING))
    } catch (e: Exception) {
        try { String(android.util.Base64.decode(s, android.util.Base64.DEFAULT)) } catch (e2: Exception) { null }
    }
}
