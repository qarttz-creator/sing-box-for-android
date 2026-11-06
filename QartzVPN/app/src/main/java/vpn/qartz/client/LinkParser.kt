package vpn.qartz.client

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Which core runs a given server. sing-box handles most transports; xray handles XHTTP. */
enum class CoreKind { SINGBOX, XRAY }

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
 * Parses share links (vless://, hysteria2://) into servers.
 * VLESS over XHTTP is routed to the xray core (sing-box can't do XHTTP); everything else
 * stays on sing-box (reality/vision/tcp, ws, grpc, httpupgrade, hysteria2).
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

    private fun vless(uri: Uri, index: Int): ServerSpec? {
        val uuid = uri.userInfo ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        fun q(k: String) = uri.getQueryParameter(k)
        val network = (q("type") ?: "tcp").lowercase()

        // XHTTP -> xray core.
        if (network == "xhttp" || network == "splithttp") {
            return vlessXray(uri, uuid, host, port, index)
        }

        // Everything else -> sing-box native outbound.
        val tag = "s$index"
        val out = JSONObject()
            .put("type", "vless")
            .put("tag", tag)
            .put("server", host)
            .put("server_port", port)
            .put("uuid", uuid)

        val flow = q("flow").orEmpty()
        if (flow.isNotEmpty()) out.put("flow", flow)

        val security = q("security")?.lowercase()
        if (security == "reality" || security == "tls") {
            val tls = JSONObject().put("enabled", true)
            q("sni")?.takeIf { it.isNotEmpty() }?.let { tls.put("server_name", it) }
            tls.put(
                "utls",
                JSONObject().put("enabled", true).put("fingerprint", q("fp")?.takeIf { it.isNotEmpty() } ?: "chrome"),
            )
            if (security == "reality") {
                tls.put(
                    "reality",
                    JSONObject()
                        .put("enabled", true)
                        .put("public_key", q("pbk").orEmpty())
                        .put("short_id", q("sid").orEmpty()),
                )
            }
            out.put("tls", tls)
        }

        when (network) {
            "grpc" -> out.put(
                "transport",
                JSONObject().put("type", "grpc")
                    .put("service_name", q("serviceName") ?: q("servicename") ?: ""),
            )
            "ws" -> {
                val tr = JSONObject().put("type", "ws")
                q("path")?.let { tr.put("path", it) }
                q("host")?.takeIf { it.isNotEmpty() }?.let { tr.put("headers", JSONObject().put("Host", it)) }
                out.put("transport", tr)
            }
            "httpupgrade" -> {
                val tr = JSONObject().put("type", "httpupgrade")
                q("path")?.let { tr.put("path", it) }
                q("host")?.takeIf { it.isNotEmpty() }?.let { tr.put("host", it) }
                out.put("transport", tr)
            }
            // "tcp" (raw) and anything else -> no transport block
        }
        return ServerSpec(name(uri, index), tag, CoreKind.SINGBOX, out)
    }

    /** VLESS + XHTTP (+ reality/tls) as an xray outbound object. */
    private fun vlessXray(uri: Uri, uuid: String, host: String, port: Int, index: Int): ServerSpec {
        fun q(k: String) = uri.getQueryParameter(k)
        val tag = "x$index"

        val user = JSONObject().put("id", uuid).put("encryption", "none")
        q("flow")?.takeIf { it.isNotEmpty() }?.let { user.put("flow", it) }

        val stream = JSONObject().put("network", "xhttp")
        val security = q("security")?.lowercase() ?: "none"
        stream.put("security", if (security == "reality") "reality" else if (security == "tls") "tls" else "none")
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
            if (q("allowInsecure") == "1" || q("insecure") == "1") t.put("allowInsecure", true)
            stream.put("tlsSettings", t)
        }

        val xh = JSONObject()
            .put("path", q("path")?.takeIf { it.isNotEmpty() } ?: "/")
            .put("mode", q("mode")?.takeIf { it.isNotEmpty() } ?: "auto")
        q("host")?.takeIf { it.isNotEmpty() }?.let { xh.put("host", it) }
        stream.put("xhttpSettings", xh)

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
}
