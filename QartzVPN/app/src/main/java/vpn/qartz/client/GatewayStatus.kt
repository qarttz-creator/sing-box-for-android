package vpn.qartz.client

import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import kotlin.concurrent.thread

/**
 * Reads the active member of the "proxy" urltest group from the running sing-box via a libbox
 * CommandClient, so the heartbeat can report WHICH gateway the mobile client is using (like desktop).
 */
object GatewayStatus : CommandClientHandler {
    private const val GROUP = "proxy"

    @Volatile
    var selectedTag: String = ""
        private set

    // Per-route liveness from the urltest group (URLTestDelay > 0 = a working latency was measured).
    // This is the client's "test-check": which routes are alive, how many backups exist. Reported
    // in the heartbeat so the admin panel can show each user's options.
    @Volatile
    var routes: List<RouteStat> = emptyList()
        private set

    data class RouteStat(val tag: String, val up: Boolean)

    private var client: CommandClient? = null

    fun start() {
        stop()
        thread(name = "gw-status") {
            runCatching {
                val options = CommandClientOptions()
                options.addCommand(Libbox.CommandGroup)
                options.statusInterval = 1_000_000_000L // 1s, nanoseconds
                val c = CommandClient(this, options)
                client = c
                c.connect() // blocks while connected
            }
        }
    }

    fun stop() {
        val c = client
        client = null
        selectedTag = ""
        routes = emptyList()
        if (c != null) thread { runCatching { c.disconnect() } }
    }

    // ---- CommandClientHandler ----
    override fun writeGroups(message: OutboundGroupIterator?) {
        val it = message ?: return
        while (it.hasNext()) {
            val g = it.next()
            if (g.tag == GROUP) {
                selectedTag = g.selected ?: ""
                val list = mutableListOf<RouteStat>()
                val items = g.getItems()
                while (items.hasNext()) {
                    val item = items.next() ?: continue
                    list.add(RouteStat(item.tag ?: "", item.getURLTestDelay() > 0))
                }
                routes = list
            }
        }
    }

    override fun connected() {}
    override fun disconnected(message: String?) {}
    override fun writeOutbounds(message: OutboundGroupItemIterator?) {}
    override fun setDefaultLogLevel(level: Int) {}
    override fun clearLogs() {}
    override fun writeLogs(messageList: LogIterator?) {}
    override fun writeStatus(message: StatusMessage?) {}
    override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) {}
    override fun updateClashMode(newMode: String?) {}
    override fun writeConnectionEvents(events: ConnectionEvents?) {}
}
