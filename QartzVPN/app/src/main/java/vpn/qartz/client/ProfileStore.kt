package vpn.qartz.client

import android.content.Context

/**
 * Stores the user's server links (vless:// / hysteria2://, one per line) and the chosen selection.
 * Selection -1 = "Авто" (all servers in a urltest group); >=0 = a specific parsed server.
 */
object ProfileStore {
    private const val PREFS = "qartz"
    private const val KEY_LINKS = "links"
    private const val KEY_SELECTION = "selection"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadLinks(c: Context): String = prefs(c).getString(KEY_LINKS, "") ?: ""

    fun saveLinks(c: Context, text: String) {
        prefs(c).edit().putString(KEY_LINKS, text).apply()
    }

    fun loadSelection(c: Context): Int = prefs(c).getInt(KEY_SELECTION, -1)

    fun saveSelection(c: Context, index: Int) {
        prefs(c).edit().putInt(KEY_SELECTION, index).apply()
    }

    /** Parse the saved links into servers (unparseable/blank/comment lines are skipped). */
    fun servers(c: Context): List<ServerSpec> {
        val result = mutableListOf<ServerSpec>()
        loadLinks(c).lines().forEach { line ->
            LinkParser.parse(line, result.size)?.let { result.add(it) }
        }
        return result
    }
}
