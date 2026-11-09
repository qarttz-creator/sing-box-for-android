package vpn.qartz.client

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.URL
import java.net.NetworkInterface as JavaNetworkInterface
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface

class QartzVpnService :
    VpnService(),
    PlatformInterface,
    CommandServerHandler {

    companion object {
        private const val TAG = "QartzVpnService"
        const val ACTION_STOP = "vpn.qartz.client.STOP"
        private const val NOTIF_CHANNEL = "qartz-vpn"
        private const val NOTIF_ID = 1

        fun logFile(context: android.content.Context): java.io.File =
            java.io.File(context.filesDir, "box.log")
    }

    // Force verbose logging into a file we can show in-app, whatever the pasted config says.
    private fun instrumentedConfig(raw: String): String = try {
        val obj = org.json.JSONObject(raw)
        val log = obj.optJSONObject("log") ?: org.json.JSONObject()
        log.put("level", "trace")
        log.put("timestamp", true)
        log.put("output", logFile(this).absolutePath)
        obj.put("log", log)
        obj.toString()
    } catch (e: Exception) {
        raw
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var boxService: BoxService? = null
    private var commandServer: CommandServer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var monitorJob: Job? = null
    @Volatile
    private var running = false

    // ---------------- lifecycle ----------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (running) return START_NOT_STICKY
        running = true
        VpnState.set(VpnState.Stage.Connecting, "")
        scope.launch {
            try {
                App.app.setupLibbox()
                val config = ConfigStore.load(this@QartzVpnService)
                if (config.isBlank()) {
                    fail("Конфиг пуст — вставьте sing-box конфиг и сохраните")
                    return@launch
                }
                // Hybrid engine: bring up xray (XHTTP) first so its local SOCKS is ready before
                // sing-box routes into it.
                val xrayOn = XrayConfigStore.exists(this@QartzVpnService)
                if (xrayOn) {
                    XrayProcess.start(this@QartzVpnService)
                    delay(500)
                }
                runCatching { logFile(this@QartzVpnService).writeText("") }
                sbLog("[qartz] starting sing-box (xray=$xrayOn, alive=${XrayProcess.isAlive()})")
                startService(instrumentedConfig(config))
                sbLog("[qartz] sing-box service started")
                // Tunnel is up; only report "Connected" once traffic actually flows through it.
                VpnState.set(VpnState.Stage.Connecting, "")
                startConnectivityMonitor()
                GatewayStatus.start() // read the active urltest member for heartbeat reporting
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                sbLog("[qartz] start FAILED: ${e.message ?: e}")
                fail(e.message ?: e.toString())
            }
        }
        return START_NOT_STICKY
    }

    /** amnezia-box libbox service model: NewService(config, platform) → Start; a CommandServer with
     *  SetService exposes the status/command API. (Upstream's startOrReloadService + OverrideOptions
     *  do not exist here — per-app routing lives in the config.) */
    private fun startService(config: String) {
        val bs = Libbox.newService(config, this)
        val cs = Libbox.newCommandServer(this, 300)
        cs.start()
        bs.start()
        cs.setService(bs)
        boxService = bs
        commandServer = cs
    }

    /** Rebuild the running service from the current ConfigStore (used after sub-over-tunnel swaps the
     *  active routes, and on a reload command). TUN stays owned by us across the swap. */
    private fun reloadService() {
        val cfg = instrumentedConfig(ConfigStore.load(this))
        runCatching { boxService?.close() }
        val bs = Libbox.newService(cfg, this)
        bs.start()
        commandServer?.setService(bs)
        boxService = bs
        sbLog("[qartz] service reloaded onto current config")
    }

    private fun fail(message: String) {
        VpnState.set(VpnState.Stage.Disconnected, message)
        stopVpn()
    }

    /** Append a line to the sing-box log file so it shows up in the in-app Logs view. */
    private fun sbLog(line: String) {
        runCatching { logFile(this).appendText(line + "\n") }
    }

    // Report "Connected" only when a probe actually passes through the tunnel; flip back if it stops.
    private fun startConnectivityMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var connected = false
            var everConnected = false
            var subRefreshed = false
            var fails = 0
            var hbTick = 0
            val startedAt = System.currentTimeMillis()
            while (isActive && running) {
                if (probeTunnel()) {
                    fails = 0
                    if (!connected) { connected = true; everConnected = true; VpnState.set(VpnState.Stage.Connected, "") }
                    // A route is confirmed up → pull the latest server list THROUGH the tunnel, once.
                    if (!subRefreshed) { subRefreshed = true; refreshSubOverTunnel() }
                    if (hbTick % 4 == 0) sendHeartbeat()  // ~every 60s
                    hbTick++
                } else {
                    fails++
                    if (connected) {
                        if (fails >= 2) {
                            connected = false
                            VpnState.set(VpnState.Stage.Connecting, "нет ответа через туннель…")
                        }
                    } else if (!everConnected && System.currentTimeMillis() - startedAt > 45000L) {
                        // No route ever came up — be honest instead of spinning forever.
                        sbLog("[qartz] no route came up within 45s — reporting honest failure")
                        fail(getString(R.string.activate_no_route))
                        return@launch
                    }
                }
                delay(if (connected) 15000L else 4000L)
            }
        }
    }

    // Report this client's online status to the control plane (derived from the saved sub URL).
    private fun sendHeartbeat() {
        val sub = ProfileStore.subUrl(this)
        if (sub.isBlank()) return
        try {
            val u = URL(sub)
            val token = android.net.Uri.parse(sub).getQueryParameter("token") ?: return
            val base = "${u.protocol}://${u.authority}"
            val conn = (URL("$base/hb").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val gateway = GatewayMap.name(this, GatewayStatus.selectedTag)
            val routesArr = org.json.JSONArray()
            for (r in GatewayStatus.routes) {
                val nm = GatewayMap.name(this, r.tag).ifBlank { r.tag }
                routesArr.put(org.json.JSONObject().put("name", nm).put("up", r.up))
            }
            val body = org.json.JSONObject()
                .put("token", token)
                .put("version", BuildConfig.VERSION_NAME)
                .put("gateway", gateway)
                .put("platform", "android")
                .put("routes", routesArr)
                .toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {
            // best-effort
        }
    }

    // Pull the latest server list THROUGH the tunnel, once a route is confirmed up.
    private fun refreshSubOverTunnel() {
        val sub = ProfileStore.subUrl(this)
        if (sub.isBlank()) return // manual-config user (pasted links/JSON) — nothing to refresh
        scope.launch {
            runCatching {
                val conn = (URL(sub).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    requestMethod = "GET"
                }
                val code = conn.responseCode
                val text = if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else ""
                conn.disconnect()
                // Guard against HTML error/captive-portal pages masquerading as a sub body.
                if (code in 200..299 && text.isNotBlank() && !text.trimStart().startsWith("<")) {
                    val before = ProfileStore.loadLinks(this@QartzVpnService).trim()
                    if (text.trim() != before) {
                        ProfileStore.saveLinks(this@QartzVpnService, text)
                        sbLog("sub refreshed over tunnel (${text.length} bytes) — switching to per-user routes")
                        // Rebuild the runtime config from the user's own routes and hot-reload onto them.
                        runCatching {
                            if (ConfigAssembler.assemble(this@QartzVpnService) is ConfigAssembler.Result.Ok) {
                                reloadService()
                            } else {
                                sbLog("[qartz] per-user config assemble failed; staying on activation routes")
                            }
                        }
                    } else {
                        sbLog("sub refreshed over tunnel — unchanged, staying put")
                    }
                }
                // Also self-update the shared activation / test profile (bootstrap routes) over the tunnel.
                runCatching {
                    val u = URL(sub)
                    val base = "${u.protocol}://${u.authority}"
                    val ac = (URL("$base/activation").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                        requestMethod = "GET"
                    }
                    val acode = ac.responseCode
                    val atext = if (acode in 200..299) ac.inputStream.bufferedReader().use { it.readText() } else ""
                    ac.disconnect()
                    if (acode in 200..299 && atext.isNotBlank() && !atext.trimStart().startsWith("<")) {
                        ProfileStore.saveActivationLinks(this@QartzVpnService, atext)
                        sbLog("activation profile refreshed over tunnel (${atext.length} bytes)")
                    }
                }
            }
        }
    }

    private fun probeTunnel(): Boolean = try {
        val c = (URL("http://www.gstatic.com/generate_204").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            instanceFollowRedirects = false
            requestMethod = "GET"
        }
        val code = c.responseCode
        c.disconnect()
        code == 204 || code in 200..299
    } catch (e: Exception) {
        false
    }

    private fun stopVpn() {
        if (!running && boxService == null && commandServer == null) {
            stopSelfCompat()
            return
        }
        running = false
        monitorJob?.cancel()
        monitorJob = null
        scope.launch {
            runCatching { GatewayStatus.stop() }
            runCatching { boxService?.close() }
            boxService = null
            runCatching { commandServer?.close() }
            commandServer = null
            runCatching { XrayProcess.stop() }
            runCatching { fileDescriptor?.close() }
            fileDescriptor = null
            VpnState.set(VpnState.Stage.Disconnected, "")
            stopSelfCompat()
        }
    }

    private fun stopSelfCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
    }

    override fun onRevoke() {
        stopVpn()
    }

    override fun onDestroy() {
        runCatching { GatewayStatus.stop() }
        runCatching { boxService?.close() }
        runCatching { commandServer?.close() }
        runCatching { XrayProcess.stop() }
        runCatching { fileDescriptor?.close() }
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    NOTIF_CHANNEL,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_connected))
            .setSmallIcon(R.drawable.ic_crystal)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---------------- PlatformInterface: TUN ----------------

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing vpn permission")

        val builder = Builder()
            .setSession("QartzVPN")
            .setMtu(options.getMTU())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val inet4Address = options.getInet4Address()
        while (inet4Address.hasNext()) {
            val address = inet4Address.next()
            builder.addAddress(address.address(), address.prefix())
        }
        val inet6Address = options.getInet6Address()
        while (inet6Address.hasNext()) {
            val address = inet6Address.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.getAutoRoute()) {
            runCatching {
                val dns = options.getDNSServerAddress()
                if (dns != null && dns.value.isNotEmpty()) builder.addDnsServer(dns.value)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val inet4Route = options.getInet4RouteAddress()
                if (inet4Route.hasNext()) {
                    while (inet4Route.hasNext()) builder.addRoute(inet4Route.next().toIpPrefix())
                } else {
                    builder.addRoute("0.0.0.0", 0)
                }
                val inet6Route = options.getInet6RouteAddress()
                if (inet6Route.hasNext()) {
                    while (inet6Route.hasNext()) builder.addRoute(inet6Route.next().toIpPrefix())
                } else {
                    builder.addRoute("::", 0)
                }
                val inet4Exclude = options.getInet4RouteExcludeAddress()
                while (inet4Exclude.hasNext()) builder.excludeRoute(inet4Exclude.next().toIpPrefix())
                val inet6Exclude = options.getInet6RouteExcludeAddress()
                while (inet6Exclude.hasNext()) builder.excludeRoute(inet6Exclude.next().toIpPrefix())
            } else {
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
            }

            val includePackage = options.getIncludePackage()
            while (includePackage.hasNext()) {
                try {
                    builder.addAllowedApplication(includePackage.next())
                } catch (e: NameNotFoundException) {
                    Log.e(TAG, "addAllowedApplication", e)
                }
            }
            val excludePackage = options.getExcludePackage()
            while (excludePackage.hasNext()) {
                try {
                    builder.addDisallowedApplication(excludePackage.next())
                } catch (e: NameNotFoundException) {
                    Log.e(TAG, "addDisallowedApplication", e)
                }
            }
        }

        val pfd = builder.establish() ?: error("android: the application is not prepared or is revoked")
        fileDescriptor = pfd
        return pfd.fd
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun io.nekohasekai.libbox.RoutePrefix.toIpPrefix(): IpPrefix =
        IpPrefix(InetAddress.getByName(address()), prefix())

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun writeLog(message: String?) {
        if (!message.isNullOrBlank()) sbLog("[core] $message")
    }

    override fun useProcFS(): Boolean = false

    // ---------------- PlatformInterface: connection owner ----------------

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int,
    ): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) error("android: unsupported below API 29")
        val uid = App.connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        if (uid == Process.INVALID_UID) error("android: connection owner not found")
        return uid
    }

    override fun packageNameByUid(uid: Int): String {
        val packages = packageManager.getPackagesForUid(uid)
        return packages?.firstOrNull() ?: error("android: package not found for uid $uid")
    }

    override fun uidByPackageName(packageName: String?): Int {
        val name = packageName ?: error("android: empty package name")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageUid(name, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageUid(name, 0)
            }
        } catch (e: Exception) {
            error("android: package not found: $name")
        }
    }

    // ---------------- PlatformInterface: network info ----------------

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val cm = App.connectivity
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = pushDefaultInterface(listener, network)
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                pushDefaultInterface(listener, network)

            override fun onLost(network: Network) {
                listener.updateDefaultInterface("", -1, false, false)
            }
        }
        networkCallback = callback
        cm.registerNetworkCallback(request, callback)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        networkCallback?.let { runCatching { App.connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    private fun pushDefaultInterface(listener: InterfaceUpdateListener, network: Network) {
        val linkProperties = App.connectivity.getLinkProperties(network) ?: return
        val name = linkProperties.interfaceName ?: return
        val index = try {
            JavaNetworkInterface.getByName(name)?.index ?: return
        } catch (e: Exception) {
            return
        }
        listener.updateDefaultInterface(name, index, false, false)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val cm = App.connectivity
        val interfaces = mutableListOf<LibboxNetworkInterface>()
        for (network in cm.allNetworks) {
            try {
                val linkProperties = cm.getLinkProperties(network) ?: continue
                val caps = cm.getNetworkCapabilities(network) ?: continue
                val name = linkProperties.interfaceName ?: continue
                val jni = JavaNetworkInterface.getByName(name) ?: continue
                val boxInterface = LibboxNetworkInterface()
                boxInterface.name = name
                boxInterface.index = jni.index
                runCatching { boxInterface.mtu = jni.mtu }
                boxInterface.type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                // Addresses MUST be clean CIDR — the core parses each with netip.MustParsePrefix
                // (panics on a scope zone like fe80::1%wlan0), so strip the zone and skip bad ones.
                boxInterface.dnsServer =
                    StringArray(linkProperties.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') })
                boxInterface.addresses =
                    StringArray(jni.interfaceAddresses.mapNotNull { it.toPrefix() })
                var dumpFlags = 0
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    dumpFlags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                }
                if (jni.isLoopback) dumpFlags = dumpFlags or OsConstants.IFF_LOOPBACK
                if (jni.isPointToPoint) dumpFlags = dumpFlags or OsConstants.IFF_POINTOPOINT
                if (jni.supportsMulticast()) dumpFlags = dumpFlags or OsConstants.IFF_MULTICAST
                boxInterface.flags = dumpFlags
                boxInterface.metered =
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                interfaces.add(boxInterface)
            } catch (e: Exception) {
                sbLog("[qartz] getInterfaces: skip a network: ${e.message ?: e}")
            }
        }
        sbLog("[qartz] getInterfaces -> ${interfaces.size}: ${interfaces.joinToString { it.name ?: "?" }}")
        return InterfaceArray(interfaces.iterator())
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState? = null

    override fun systemCertificates(): StringIterator = StringArray(emptyList())

    override fun clearDNSCache() {}

    override fun sendNotification(notification: Notification?) {}

    // ---------------- CommandServerHandler (amnezia-box) ----------------

    override fun serviceReload() {
        scope.launch { runCatching { reloadService() } }
    }

    override fun postServiceClose() {}

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
        available = false
        enabled = false
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {}

    // ---------------- iterator helpers (gomobile <-> Java) ----------------

    // len() MUST return the real count: the core reads addresses.Len() when enumerating interfaces,
    // and a 0 there means "this interface has no addresses" → it's dropped → "no available network
    // interface" even though getInterfaces returned it. So back it with a List, not a bare Iterator.
    private class StringArray(private val items: List<String>) : StringIterator {
        private val iterator = items.iterator()
        override fun len(): Int = items.size
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
    }

    private class InterfaceArray(private val iterator: Iterator<LibboxNetworkInterface>) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): LibboxNetworkInterface = iterator.next()
    }

    @SuppressLint("DefaultLocale")
    private fun InterfaceAddress.toPrefix(): String? {
        val host = (address?.hostAddress ?: return null).substringBefore('%') // strip IPv6 zone
        val len = networkPrefixLength.toInt()
        if (host.isEmpty() || len < 0) return null
        return "$host/$len"
    }
}
