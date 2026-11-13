package vpn.qartz.client

import android.content.Context
import java.io.File

/** MVP config storage: a single sing-box JSON config saved as a file. */
object ConfigStore {
    private fun file(context: Context) = File(context.filesDir, "config.json")

    // config.json содержит адреса шлюзов → шифруем на диске (AtRest), читаем прозрачно.
    fun load(context: Context): String =
        file(context).let { if (it.exists()) AtRest.dec(it.readText()) else "" }

    fun save(context: Context, content: String) {
        file(context).writeText(AtRest.enc(content))
    }
}
