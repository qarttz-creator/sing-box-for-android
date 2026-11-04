package vpn.qartz.client

import android.net.Uri
import org.json.JSONObject

/** A parsed server: a display name plus the sing-box outbound object it maps to. */
data class ServerSpec(val name: String, val tag: String, val outbound: JSONObject)

/**
 * Parses share links (vless://, hysteria2://) into sing-box outbounds, baking in the settings
 * we confirmed work on-device: minimal TLS for hysteria2 (no stray alpn/insecure), reality/utls
 * for vless, obfs salamander when present.
 */
object LinkParser {

    /** Parse one link into a ServerSpec, or null if it is blank, a comment, or unsupported. */
    fun parse(raw: String, index: Int): ServerSpec? {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return null
        val tag = "s$index"
        return try {
            val uri = Uri.parse(line)
            when (uri.scheme?.lowercase()) {
                "vless" -> vless(uri, tag, index)
                "hysteria2", "hy2" -> hysteria2(uri, tag, index)
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

    private fun vless(uri: Uri, tag: String, index: Int): ServerSpec? {
        val uuid = uri.userInfo ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        fun q(k: String) = uri.getQueryParameter(k)

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

        when (q("type")?.lowercase() ?: "tcp") {
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
        return ServerSpec(name(uri, index), tag, out)
    }

    private fun hysteria2(uri: Uri, tag: String, index: Int): ServerSpec? {
        val password = uri.userInfo ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        fun q(k: String) = uri.getQueryParameter(k)

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

        // Minimal TLS: adding stray alpn/insecure here broke the QUIC handshake on-device.
        val tls = JSONObject().put("enabled", true)
        q("sni")?.takeIf { it.isNotEmpty() }?.let { tls.put("server_name", it) }
        if (q("insecure") == "1" || q("allowInsecure") == "1") tls.put("insecure", true)
        out.put("tls", tls)

        return ServerSpec(name(uri, index), tag, out)
    }
}
