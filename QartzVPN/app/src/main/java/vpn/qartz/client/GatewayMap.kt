package vpn.qartz.client

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Maps a urltest member tag (s0/x1/…) to its human gateway name, for heartbeat reporting. */
object GatewayMap {
    private fun file(c: Context) = File(c.filesDir, "gateways.json")

    fun save(c: Context, map: Map<String, String>) {
        runCatching {
            val o = JSONObject()
            map.forEach { (k, v) -> o.put(k, v) }
            file(c).writeText(o.toString())
        }
    }

    fun name(c: Context, tag: String?): String {
        if (tag.isNullOrBlank()) return ""
        return try {
            JSONObject(file(c).readText()).optString(tag, "")
        } catch (e: Exception) {
            ""
        }
    }
}
