package vpn.qartz.client

import android.content.Context
import java.io.File

/** MVP config storage: a single sing-box JSON config saved as a file. */
object ConfigStore {
    private fun file(context: Context) = File(context.filesDir, "config.json")

    fun load(context: Context): String =
        file(context).let { if (it.exists()) it.readText() else "" }

    fun save(context: Context, content: String) {
        file(context).writeText(content)
    }
}
