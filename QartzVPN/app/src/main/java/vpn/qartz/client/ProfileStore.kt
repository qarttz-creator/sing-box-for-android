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
    private const val KEY_DEVICE_ID = "device_id"

    // Per-app routing modes
    const val MODE_OFF = 0          // everything through the tunnel
    const val MODE_EXCLUDE = 1      // selected apps bypass the tunnel
    const val MODE_INCLUDE = 2      // only selected apps go through the tunnel

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Стабильный id этой установки для учёта устройств в панели: генерится один раз, переживает
     *  смену IP/сети. Не секрет (просто случайный UUID) — без AtRest. */
    fun deviceId(c: Context): String {
        val p = prefs(c)
        var id = p.getString(KEY_DEVICE_ID, "") ?: ""
        if (id.isBlank()) {
            id = java.util.UUID.randomUUID().toString().replace("-", "")
            p.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

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

    /** Авто-миграция адреса центра (например, со старого 222 на Токио): переписываем
     *  scheme://host:port в sub_url, сохраняя путь и ?token=… */
    fun migrateSubBase(c: Context, newBase: String): Boolean {
        val trimmed = newBase.trim().trimEnd('/')
        if (trimmed.isBlank()) return false
        return runCatching {
            val nb = java.net.URL(trimmed)
            if (nb.protocol != "http" && nb.protocol != "https") return false
            val curRaw = subUrl(c).trim()
            if (curRaw.isBlank()) return false
            val cur = java.net.URL(curRaw)
            val curBase = "${cur.protocol}://${cur.authority}"
            val targetBase = "${nb.protocol}://${nb.authority}"
            if (curBase.equals(targetBase, ignoreCase = true)) return false
            val pathAndQuery = buildString {
                append(cur.path.ifEmpty { "/sub" })
                if (!cur.query.isNullOrEmpty()) append("?").append(cur.query)
            }
            saveSubUrl(c, targetBase + pathAndQuery)
            true
        }.getOrDefault(false)
    }

    /** Проверяет, что тело /sub содержит хотя бы один валидный маршрут (защита от затирания мусором/HTML). */
    fun hasValidServer(text: String): Boolean {
        val t = text.trim()
        if (t.isBlank() || t.startsWith("<")) return false
        if (t.startsWith("{") || AwgConfig.looksLikeIni(t)) return true
        return t.lines().any { line -> LinkParser.parse(line, 0) != null }
    }

    /** Нормализованный отсортированный список строк подписки для честного сравнения без ложных реконнектов. */
    fun normalizeLinks(text: String): String =
        text.replace("\r\n", "\n").lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .sorted()
            .joinToString("\n")

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

    /** Keep local devices (printers, car, NAS, router, corporate LAN) reachable while connected. Default off (all in tunnel). */
    fun allowLan(c: Context): Boolean = prefs(c).getBoolean(KEY_ALLOW_LAN, false)

    fun saveAllowLan(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean(KEY_ALLOW_LAN, v).apply()
    }

    /** Auto-connect after device boot. Needs VPN permission already granted (one manual connect). */
    fun autoStart(c: Context): Boolean = prefs(c).getBoolean(KEY_AUTOSTART, false)

    fun saveAutoStart(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean(KEY_AUTOSTART, v).apply()
    }

    /** Route RU domains (.ru/.рф/.su) directly with system DNS. Default off (all in tunnel). */
    fun ruDirect(c: Context): Boolean = prefs(c).getBoolean(KEY_RU_DIRECT, false)

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
