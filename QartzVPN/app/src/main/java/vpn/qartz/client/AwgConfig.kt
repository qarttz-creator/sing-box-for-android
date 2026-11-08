package vpn.qartz.client

import org.json.JSONArray
import org.json.JSONObject

/**
 * AmneziaWG (obfuscated WireGuard) — the transport that survives Beeline's TSPU where reality and
 * hysteria do not. Parses the AmneziaWG client config (the INI block Amnezia / the panel export) into
 * a sing-box `awg` endpoint object (amnezia-box fork schema, type "awg").
 *
 * Field notes from the fork's AwgEndpointOptions: jc/jmin/jmax and s1..s4 are ints; h1..h4, i1..i5,
 * header_protection_key and content_padding_addition are STRINGS; address / allowed_ips are CIDR lists.
 */
object AwgConfig {

    /** True if [text] looks like an AmneziaWG INI config block (as opposed to a share link or JSON). */
    fun looksLikeIni(text: String): Boolean =
        text.contains("[Interface]", true) && text.contains("PrivateKey", true)

    /** Parse an AmneziaWG INI config into a sing-box `awg` endpoint JSON, or null if it isn't one. */
    fun parseIni(text: String, tag: String): JSONObject? {
        val iface = HashMap<String, String>()
        val peer = HashMap<String, String>()
        var inPeer = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.equals("[Interface]", true)) { inPeer = false; continue }
            if (line.startsWith("[Peer]", true)) { inPeer = true; continue }
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val k = line.substring(0, eq).trim().lowercase()
            val v = line.substring(eq + 1).trim()
            (if (inPeer) peer else iface)[k] = v
        }

        val priv = iface["privatekey"]?.takeIf { it.isNotEmpty() } ?: return null
        val pub = peer["publickey"]?.takeIf { it.isNotEmpty() } ?: return null
        val endpoint = peer["endpoint"]?.takeIf { it.isNotEmpty() } ?: return null
        val host = endpoint.substringBeforeLast(':', "").trim('[', ']').ifEmpty { return null }
        val port = endpoint.substringAfterLast(':', "").toIntOrNull() ?: return null

        val ep = JSONObject()
            .put("type", "awg")
            .put("tag", tag)
            .put("useIntegratedTun", false)
            .put("private_key", priv)
            .put("address", cidrList(iface["address"] ?: "10.8.0.2/32"))

        iface["mtu"]?.toIntOrNull()?.let { ep.put("mtu", it) }
        for (p in listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4"))
            iface[p]?.trim()?.toIntOrNull()?.let { ep.put(p, it) }
        for (s in listOf("h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5"))
            iface[s]?.takeIf { it.isNotEmpty() }?.let { ep.put(s, it) }
        iface["headerprotectionkey"]?.takeIf { it.isNotEmpty() }?.let { ep.put("header_protection_key", it) }
        iface["contentpaddingaddition"]?.takeIf { it.isNotEmpty() }?.let { ep.put("content_padding_addition", it) }
        // AmneziaWG 2.0 timers/behaviour (strings/ranges per the fork schema) — include them so the
        // endpoint matches exactly what the stock Amnezia client sends.
        iface["rekeyaftertime"]?.takeIf { it.isNotEmpty() }?.let { ep.put("rekey_after_time", it) }
        iface["rekeytimeout"]?.takeIf { it.isNotEmpty() }?.let { ep.put("rekey_timeout", it) }
        iface["rejectaftertime"]?.takeIf { it.isNotEmpty() }?.let { ep.put("reject_after_time", it) }
        iface["keepalivetimeout"]?.takeIf { it.isNotEmpty() }?.let { ep.put("keepalive_timeout", it) }
        iface["maxhandshakeattempts"]?.takeIf { it.isNotEmpty() }?.let { ep.put("max_handshake_attempts", it) }
        if (boolOn(iface["randomtrailers"])) ep.put("random_trailers", true)
        if (boolOn(iface["disablecookies"])) ep.put("disable_cookies", true)

        val peerObj = JSONObject()
            .put("address", host)
            .put("port", port)
            .put("public_key", pub)
            .put("allowed_ips", cidrList(peer["allowedips"] ?: "0.0.0.0/0"))
        peer["presharedkey"]?.takeIf { it.isNotEmpty() }?.let { peerObj.put("preshared_key", it) }
        peer["persistentkeepalive"]?.trim()?.toIntOrNull()?.let { peerObj.put("persistent_keepalive_interval", it) }

        return ep.put("peers", JSONArray().put(peerObj))
    }

    private fun boolOn(v: String?): Boolean =
        v?.trim()?.lowercase() in setOf("on", "true", "1", "yes")

    private fun cidrList(v: String): JSONArray {
        val arr = JSONArray()
        v.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
            arr.put(if (it.contains("/")) it else if (it.contains(":")) "$it/128" else "$it/32")
        }
        if (arr.length() == 0) arr.put("0.0.0.0/0")
        return arr
    }
}
