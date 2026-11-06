package vpn.qartz.client

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
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
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import io.nekohasekai.libbox.NeighborUpdateListener
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

    // Per-app routing: translate the saved mode + app set into sing-box override options.
    private fun perAppOverride(): OverrideOptions {
        val over = OverrideOptions()
        val apps = ProfileStore.selectedApps(this)
        if (apps.isEmpty()) return over
        when (ProfileStore.routingMode(this)) {
            ProfileStore.MODE_EXCLUDE ->
                over.excludePackage = StringArray((apps - packageName).iterator())
            ProfileStore.MODE_INCLUDE ->
                over.includePackage = StringArray((apps + packageName).iterator())
        }
        return over
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
                val server = CommandServer(this@QartzVpnService, this@QartzVpnService)
                server.start()
                commandServer = server
                server.startOrReloadService(instrumentedConfig(config), perAppOverride())
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
            var fails = 0
            var hbTick = 0
            while (isActive && running) {
                if (probeTunnel()) {
                    fails = 0
                    if (!connected) { connected = true; VpnState.set(VpnState.Stage.Connected, "") }
                    if (hbTick % 4 == 0) sendHeartbeat()  // ~every 60s
                    hbTick++
                } else {
                    fails++
                    if (fails >= 2) {
                        connected = false
                        VpnState.set(VpnState.Stage.Connecting, "нет ответа через туннель…")
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
            val body = org.json.JSONObject()
                .put("token", token)
                .put("version", BuildConfig.VERSION_NAME)
                .put("gateway", gateway)
                .put("platform", "android")
                .toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {
            // best-effort
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
        if (!running && commandServer == null) {
            stopSelfCompat()
            return
        }
        running = false
        monitorJob?.cancel()
        monitorJob = null
        scope.launch {
            runCatching { GatewayStatus.stop() }
            runCatching { commandServer?.closeService() }
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
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val inet4Address = options.inet4Address
        while (inet4Address.hasNext()) {
            val address = inet4Address.next()
            builder.addAddress(address.address(), address.prefix())
        }

        val inet6Address = options.inet6Address
        while (inet6Address.hasNext()) {
            val address = inet6Address.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dnsServerAddress = options.dnsServerAddress
                while (dnsServerAddress.hasNext()) {
                    builder.addDnsServer(dnsServerAddress.next())
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val inet4RouteAddress = options.inet4RouteAddress
                if (inet4RouteAddress.hasNext()) {
                    while (inet4RouteAddress.hasNext()) {
                        builder.addRoute(inet4RouteAddress.next().toIpPrefix())
                    }
                } else if (options.inet4Address.hasNext()) {
                    builder.addRoute("0.0.0.0", 0)
                }

                val inet6RouteAddress = options.inet6RouteAddress
                if (inet6RouteAddress.hasNext()) {
                    while (inet6RouteAddress.hasNext()) {
                        builder.addRoute(inet6RouteAddress.next().toIpPrefix())
                    }
                } else if (options.inet6Address.hasNext()) {
                    builder.addRoute("::", 0)
                }

                val inet4RouteExcludeAddress = options.inet4RouteExcludeAddress
                while (inet4RouteExcludeAddress.hasNext()) {
                    builder.excludeRoute(inet4RouteExcludeAddress.next().toIpPrefix())
                }

                val inet6RouteExcludeAddress = options.inet6RouteExcludeAddress
                while (inet6RouteExcludeAddress.hasNext()) {
                    builder.excludeRoute(inet6RouteExcludeAddress.next().toIpPrefix())
                }
            } else {
                val inet4RouteAddress = options.inet4RouteRange
                if (inet4RouteAddress.hasNext()) {
                    while (inet4RouteAddress.hasNext()) {
                        val address = inet4RouteAddress.next()
                        builder.addRoute(address.address(), address.prefix())
                    }
                }

                val inet6RouteAddress = options.inet6RouteRange
                if (inet6RouteAddress.hasNext()) {
                    while (inet6RouteAddress.hasNext()) {
                        val address = inet6RouteAddress.next()
                        builder.addRoute(address.address(), address.prefix())
                    }
                }
            }

            val includePackage = options.includePackage
            while (includePackage.hasNext()) {
                try {
                    builder.addAllowedApplication(includePackage.next())
                } catch (e: NameNotFoundException) {
                    Log.e(TAG, "addAllowedApplication", e)
                }
            }

            val excludePackage = options.excludePackage
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
    private fun RoutePrefix.toIpPrefix(): IpPrefix = IpPrefix(InetAddress.getByName(address()), prefix())

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    // ---------------- PlatformInterface: network info ----------------

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val cm = App.connectivity
        // Track the underlying PHYSICAL network, never the VPN tun. A plain
        // registerDefaultNetworkCallback() reports our own tun once it is up, so sing-box would
        // bind proxy sockets back into the tunnel (loop -> "connected but no traffic").
        // NET_CAPABILITY_NOT_VPN excludes the tun and leaves only wifi/cellular/ethernet.
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
        val networks = App.connectivity.allNetworks
        val networkInterfaces = JavaNetworkInterface.getNetworkInterfaces().toList()
        val interfaces = mutableListOf<LibboxNetworkInterface>()
        for (network in networks) {
            val boxInterface = LibboxNetworkInterface()
            val linkProperties = App.connectivity.getLinkProperties(network) ?: continue
            val networkCapabilities = App.connectivity.getNetworkCapabilities(network) ?: continue
            boxInterface.name = linkProperties.interfaceName
            val networkInterface = networkInterfaces.find { it.name == boxInterface.name } ?: continue
            boxInterface.dnsServer =
                StringArray(linkProperties.dnsServers.mapNotNull { it.hostAddress }.iterator())
            boxInterface.type = when {
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                else -> Libbox.InterfaceTypeOther
            }
            boxInterface.index = networkInterface.index
            runCatching { boxInterface.mtu = networkInterface.mtu }
            boxInterface.addresses =
                StringArray(networkInterface.interfaceAddresses.mapNotNull { it.toPrefix() }.iterator())
            var dumpFlags = 0
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                dumpFlags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (networkInterface.isLoopback) dumpFlags = dumpFlags or OsConstants.IFF_LOOPBACK
            if (networkInterface.isPointToPoint) dumpFlags = dumpFlags or OsConstants.IFF_POINTOPOINT
            if (networkInterface.supportsMulticast()) dumpFlags = dumpFlags or OsConstants.IFF_MULTICAST
            boxInterface.flags = dumpFlags
            boxInterface.metered =
                !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            interfaces.add(boxInterface)
        }
        return InterfaceArray(interfaces.iterator())
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) error("android: unsupported below API 29")
        val uid = App.connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        if (uid == Process.INVALID_UID) error("android: connection owner not found")
        val owner = ConnectionOwner().also { it.userId = uid }
        val packages = packageManager.getPackagesForUid(uid)
        owner.userName = packages?.firstOrNull() ?: ""
        owner.setAndroidPackageNames(StringArray((packages?.toList() ?: emptyList()).iterator()))
        return owner
    }

    override fun readWIFIState(): WIFIState? = null

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() {}

    override fun localDNSTransport(): LocalDNSTransport? = null

    // ---------------- PlatformInterface: notifications ----------------

    override fun sendNotification(notification: Notification) {}

    override fun cancelNotification(identifier: String, typeID: Int) {}

    // ---------------- PlatformInterface: unused advanced features ----------------

    override fun startNeighborMonitor(listener: NeighborUpdateListener) {}

    override fun closeNeighborMonitor(listener: NeighborUpdateListener) {}

    override fun registerMyInterface(name: String) {}

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {}

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): ShellSession = error("not supported")

    override fun lookupUser(username: String?): PlatformUser = error("not supported")

    override fun lookupSFTPServer(): String = error("not supported")

    override fun readSystemSSHHostKey(): String = error("not supported")

    override fun tailscaleHostname(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions?): BridgeSession = error("not supported")

    // ---------------- CommandServerHandler ----------------

    override fun serviceStop() {
        stopVpn()
    }

    override fun serviceReload() {
        scope.launch {
            runCatching {
                val config = ConfigStore.load(this@QartzVpnService)
                commandServer?.startOrReloadService(config, OverrideOptions())
            }
        }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
        available = false
        enabled = false
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {}

    override fun triggerNativeCrash() {}

    override fun writeDebugMessage(message: String?) {
        Log.d(TAG, message ?: "")
    }

    override fun connectSSHAgent(): Int = -1

    // ---------------- iterator helpers (gomobile <-> Java) ----------------

    private class StringArray(private val iterator: Iterator<String>) : StringIterator {
        override fun len(): Int = 0
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
    }

    private class InterfaceArray(private val iterator: Iterator<LibboxNetworkInterface>) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): LibboxNetworkInterface = iterator.next()
    }

    @SuppressLint("DefaultLocale")
    private fun InterfaceAddress.toPrefix(): String? {
        val host = address?.hostAddress ?: return null
        return if (address is Inet6Address) {
            "${Inet6Address.getByAddress(address.address).hostAddress}/$networkPrefixLength"
        } else {
            "$host/$networkPrefixLength"
        }
    }
}
