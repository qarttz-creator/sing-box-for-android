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
    private const val KEY_ACTIVATION = "activation"
    private const val KEY_ROUTE_MODE = "route_mode"
    private const val KEY_ROUTE_APPS = "route_apps"
    private const val KEY_ALLOW_LAN = "allow_lan"
    private const val KEY_AUTOSTART = "autostart"
    private const val KEY_RU_DIRECT = "ru_direct"

    // Per-app routing modes
    const val MODE_OFF = 0          // everything through the tunnel
    const val MODE_EXCLUDE = 1      // selected apps bypass the tunnel
    const val MODE_INCLUDE = 2      // only selected apps go through the tunnel

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // links/sub_url/activation содержат адреса шлюзов и креды → шифруем на диске (AtRest).
    fun loadLinks(c: Context): String = AtRest.dec(prefs(c).getString(KEY_LINKS, "") ?: "")

    /** True once the client has been activated (has at least one server / raw config saved). */
    fun hasProfile(c: Context): Boolean = loadLinks(c).isNotBlank()

    fun saveLinks(c: Context, text: String) {
        prefs(c).edit().putString(KEY_LINKS, AtRest.enc(text)).apply()
    }

    fun loadSelection(c: Context): Int = prefs(c).getInt(KEY_SELECTION, -1)

    fun saveSelection(c: Context, index: Int) {
        prefs(c).edit().putInt(KEY_SELECTION, index).apply()
    }

    fun subUrl(c: Context): String = AtRest.dec(prefs(c).getString(KEY_SUB_URL, "") ?: "")

    fun saveSubUrl(c: Context, url: String) {
        prefs(c).edit().putString(KEY_SUB_URL, AtRest.enc(url)).apply()
    }

    /** The shared activation / test profile (bootstrap routes), self-updated over the tunnel from
     *  the control plane's /activation. Used to build a tunnel when the user's own sub host is
     *  blocked, and as the route test-check. Empty until first fetched. */
    fun activationLinks(c: Context): String = AtRest.dec(prefs(c).getString(KEY_ACTIVATION, "") ?: "")

    fun saveActivationLinks(c: Context, text: String) {
        prefs(c).edit().putString(KEY_ACTIVATION, AtRest.enc(text)).apply()
    }

    fun routingMode(c: Context): Int = prefs(c).getInt(KEY_ROUTE_MODE, MODE_OFF)

    fun saveRoutingMode(c: Context, mode: Int) {
        prefs(c).edit().putInt(KEY_ROUTE_MODE, mode).apply()
    }

    /** Keep local devices (printers, car, NAS, router) reachable while connected. Default on. */
    fun allowLan(c: Context): Boolean = prefs(c).getBoolean(KEY_ALLOW_LAN, true)

    fun saveAllowLan(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean(KEY_ALLOW_LAN, v).apply()
    }

    /** Auto-connect after device boot. Needs VPN permission already granted (one manual connect). */
    fun autoStart(c: Context): Boolean = prefs(c).getBoolean(KEY_AUTOSTART, false)

    fun saveAutoStart(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean(KEY_AUTOSTART, v).apply()
    }

    /** Route RU domains (.ru/.рф/.su) directly, bypassing the VPN. Default on. */
    fun ruDirect(c: Context): Boolean = prefs(c).getBoolean(KEY_RU_DIRECT, true)

    fun saveRuDirect(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean(KEY_RU_DIRECT, v).apply()
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
