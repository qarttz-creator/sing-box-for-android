package vpn.qartz.client

import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.Connections
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import kotlin.concurrent.thread

/**
 * Reads the active member of the "proxy" urltest group from the running sing-box via a libbox
 * CommandClient, so the heartbeat can report WHICH gateway the mobile client is using (like desktop).
 *
 * Targets the amnezia-box libbox CommandClientHandler (Connected/Disconnected/ClearLogs/WriteLogs/
 * WriteStatus/WriteGroups/Initialize+UpdateClashMode/WriteConnections).
 */
object GatewayStatus : CommandClientHandler {
    private const val GROUP = "proxy"

    @Volatile
    var selectedTag: String = ""
        private set

    // Per-route liveness from the urltest group (URLTestDelay > 0 = a working latency was measured):
    // the client's "test-check" — which routes are alive — reported in the heartbeat for the panel.
    @Volatile
    var routes: List<RouteStat> = emptyList()
        private set

    data class RouteStat(val tag: String, val up: Boolean)

    // Гистерезис здоровья: urltest гоняет все маршруты ОДНИМ одновременным проходом каждые 30с с
    // фиксированным таймаутом. На Wi-Fi 9 проб конкурируют за канал (+ AWG добавляет RTT на хендшейк),
    // часть не укладывается → getURLTestDelay()==0 в этом цикле → ложный «мёртв», отсюда мигание 5–6/9.
    // Поэтому считаем маршрут живым, если последняя проба прошла ИЛИ он был жив недавно (≈5 интервалов).
    // Это сглаживает ТОЛЬКО отображаемое/репортируемое здоровье; выбор аутбаунда urltest'ом не трогаем.
    private const val UP_HOLD_MS = 150_000L
    private val lastUpAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private var client: CommandClient? = null

    fun start() {
        stop()
        thread(name = "gw-status") {
            runCatching {
                val options = CommandClientOptions()
                options.command = Libbox.CommandGroup
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
        lastUpAt.clear()
        if (c != null) thread { runCatching { c.disconnect() } }
    }

    // ---- CommandClientHandler (amnezia-box) ----
    override fun writeGroups(message: OutboundGroupIterator?) {
        val it = message ?: return
        while (it.hasNext()) {
            val g = it.next()
            if (g.tag == GROUP) {
                selectedTag = g.selected ?: ""
                val now = System.currentTimeMillis()
                val list = mutableListOf<RouteStat>()
                val items = g.getItems()
                while (items.hasNext()) {
                    val item = items.next() ?: continue
                    val tag = item.tag ?: ""
                    val rawUp = item.getURLTestDelay() > 0
                    if (rawUp) lastUpAt[tag] = now
                    val up = rawUp || (lastUpAt[tag]?.let { now - it < UP_HOLD_MS } ?: false)
                    list.add(RouteStat(tag, up))
                }
                routes = list
            }
        }
    }

    override fun connected() {}
    override fun disconnected(message: String?) {}
    override fun clearLogs() {}
    override fun writeLogs(messageList: StringIterator?) {}
    override fun writeStatus(message: StatusMessage?) {}
    override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) {}
    override fun updateClashMode(newMode: String?) {}
    override fun writeConnections(message: Connections?) {}
}
