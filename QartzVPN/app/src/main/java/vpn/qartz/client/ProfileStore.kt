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
    private const val KEY_SUB_URL = "sub_url"
    private const val KEY_ROUTE_MODE = "route_mode"
    private const val KEY_ROUTE_APPS = "route_apps"

    // Per-app routing modes
    const val MODE_OFF = 0          // everything through the tunnel
    const val MODE_EXCLUDE = 1      // selected apps bypass the tunnel
    const val MODE_INCLUDE = 2      // only selected apps go through the tunnel

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadLinks(c: Context): String = prefs(c).getString(KEY_LINKS, "") ?: ""

    fun saveLinks(c: Context, text: String) {
        prefs(c).edit().putString(KEY_LINKS, text).apply()
    }

    fun loadSelection(c: Context): Int = prefs(c).getInt(KEY_SELECTION, -1)

    fun saveSelection(c: Context, index: Int) {
        prefs(c).edit().putInt(KEY_SELECTION, index).apply()
    }

    fun subUrl(c: Context): String = prefs(c).getString(KEY_SUB_URL, "") ?: ""

    fun saveSubUrl(c: Context, url: String) {
        prefs(c).edit().putString(KEY_SUB_URL, url).apply()
    }

    fun routingMode(c: Context): Int = prefs(c).getInt(KEY_ROUTE_MODE, MODE_OFF)

    fun saveRoutingMode(c: Context, mode: Int) {
        prefs(c).edit().putInt(KEY_ROUTE_MODE, mode).apply()
    }

    fun selectedApps(c: Context): Set<String> =
        prefs(c).getStringSet(KEY_ROUTE_APPS, emptySet())?.toSet() ?: emptySet()

    fun saveSelectedApps(c: Context, apps: Set<String>) {
        prefs(c).edit().putStringSet(KEY_ROUTE_APPS, apps).apply()
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
