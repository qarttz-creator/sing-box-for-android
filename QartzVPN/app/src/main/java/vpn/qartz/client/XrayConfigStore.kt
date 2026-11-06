package vpn.qartz.client

import android.content.Context
import java.io.File

/** The xray config for the hybrid engine. Absent file = no XHTTP servers = pure sing-box run. */
object XrayConfigStore {
    fun file(context: Context) = File(context.filesDir, "xray.json")

    fun exists(context: Context): Boolean = file(context).exists()

    fun save(context: Context, content: String) {
        file(context).writeText(content)
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
