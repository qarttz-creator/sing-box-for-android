package vpn.qartz.client

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Выбор маршрута по пингу и нагрузке шлюза.
 *
 * sing-box сам только меряет пинг (urltest-группа "proxy-probe"), а ехать через какой маршрут —
 * решаем здесь и переключаем selector-группу "proxy" через libbox CommandClient.
 *
 * Правило:
 *  1. Посадка (нет текущего или он умер): живые по возрастанию пинга, первый с нагрузкой ≤ 50%;
 *     если все > 50% — наименее загруженный.
 *  2. Сел — сидит: нагрузка текущего маршрута сама по себе не повод уходить.
 *  3. Переход только когда пинг ТЕКУЩЕГО заметно ухудшился относительно посадки (сглаженный пинг
 *     вырос на ≥ 100 мс и в ≥ 1,5 раза): тогда на маршрут с лучшим пингом и нагрузкой ≤ 50%;
 *     нет такого — остаёмся.
 * Пинг в основном задаётся географией, поэтому массовых переездов не будет — только при реальном
 * троттлинге маршрута.
 */
object RouteSteer {
    const val SELECTOR = "proxy"
    const val PROBE = "proxy-probe"

    private const val LOAD_LIMIT = 50
    private const val WORSE_ABS_MS = 100
    private const val WORSE_RATIO = 1.5
    private const val DEAD_AFTER = 2 // подряд неудачных замеров, чтобы признать текущий мёртвым
    private const val TICK_MS = 30_000L

    /** Загрузка CPU сервера по имени маршрута — приходит в ответ на хартбит (/hb → "load"). */
    private val load = ConcurrentHashMap<String, Int>()

    fun setLoad(m: Map<String, Int>) {
        load.clear(); load.putAll(m)
    }

    // ---------- чистая логика (без Android) ----------

    data class Route(val tag: String, val delayMs: Int, val load: Int?) {
        val alive get() = delayMs > 0
        fun ok() = load == null || load <= LOAD_LIMIT
    }

    /** Посадка: по пингу, первый не перегруженный; иначе наименее загруженный. null — живых нет. */
    fun pickSeat(routes: List<Route>): String? {
        val alive = routes.filter { it.alive }.sortedBy { it.delayMs }
        if (alive.isEmpty()) return null
        alive.firstOrNull { it.ok() }?.let { return it.tag }
        return alive.minByOrNull { it.load ?: 0 }!!.tag
    }

    /** Пинг текущего ухудшился относительно посадки? */
    fun worsened(baselineMs: Int, nowMs: Int): Boolean =
        baselineMs > 0 && nowMs > 0 && nowMs - baselineMs >= WORSE_ABS_MS && nowMs >= baselineMs * WORSE_RATIO

    /** Куда уйти с ухудшившегося текущего: лучший пинг и нагрузка ≤ 50%. null — остаёмся. */
    fun escape(current: Route, routes: List<Route>): String? =
        routes.filter { it.alive && it.tag != current.tag && it.delayMs < current.delayMs && it.ok() }
            .minByOrNull { it.delayMs }?.tag

    // ---------- состояние и цикл ----------

    private class Seat(val tag: String) {
        var baseline = 0                 // пинг при посадке (минимум из первых замеров)
        val recent = ArrayDeque<Int>()   // последние замеры для сглаживания (медиана из 3)
        var deadStreak = 0
        fun smoothed(): Int = recent.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
    }

    @Volatile private var running = false
    @Volatile private var gen = 0 // поколение цикла: перезапуск не оставляет старый поток крутиться
    private var seat: Seat? = null
    private var names: Map<String, String> = emptyMap()

    fun start(ctx: Context) {
        stop()
        names = runCatching {
            val o = JSONObject(File(ctx.filesDir, "gateways.json").readText())
            o.keys().asSequence().associateWith { o.optString(it) }
        }.getOrDefault(emptyMap())
        running = true
        seat = null
        val my = ++gen
        thread(name = "route-steer", isDaemon = true) {
            Thread.sleep(2_000) // дать sing-box подняться
            while (running && my == gen) {
                runCatching { tick() }.onFailure { sbLogSafe("[qartz] route-steer: ${it.message}") }
                // небольшой разброс, чтобы клиенты не решали синхронно
                Thread.sleep(TICK_MS + (Math.random() * 10_000).toLong())
            }
        }
    }

    fun stop() {
        running = false
        seat = null
    }

    private fun tick() {
        // свежий замер пинга всех маршрутов (urltest-группа с проверкой по IP), ждём результатов
        val my = gen
        GatewayStatus.urlTest(PROBE)
        Thread.sleep(9_000)
        if (!running || my != gen) return
        val delays = GatewayStatus.delays
        if (delays.isEmpty()) return
        val routes = delays.map { (tag, d) -> Route(tag, d, load[names[tag] ?: tag]) }
        val cur = seat
        val curRoute = cur?.let { s -> routes.firstOrNull { it.tag == s.tag } }

        // 1) посадка: впервые или текущий мёртв (подряд DEAD_AFTER замеров)
        if (cur == null || curRoute == null || run {
                if (curRoute.alive) cur.deadStreak = 0 else cur.deadStreak++
                cur.deadStreak >= DEAD_AFTER
            }) {
            val target = pickSeat(routes) ?: return
            switchTo(target, closeOld = true)
            return
        }
        if (!curRoute.alive) return // одна неудачная проба — терпим

        // 2) копим сглаженный пинг текущего; база — минимум первых замеров после посадки
        cur.recent.addLast(curRoute.delayMs)
        while (cur.recent.size > 3) cur.recent.removeFirst()
        val sm = cur.smoothed()
        if (cur.baseline == 0 || (cur.recent.size < 3 && sm < cur.baseline)) cur.baseline = sm
        if (cur.recent.size < 3) return

        // 3) уходим только при заметном ухудшении пинга и только на не перегруженный и более быстрый
        if (worsened(cur.baseline, sm)) {
            val target = escape(curRoute.copy(delayMs = sm), routes)
            if (target != null) {
                sbLogSafe("[qartz] route-steer: пинг ${names[cur.tag] ?: cur.tag} ${cur.baseline}→${sm} мс — ухожу на ${names[target] ?: target}")
                switchTo(target, closeOld = true)
            }
        }
    }

    private fun switchTo(tag: String, closeOld: Boolean) {
        if (seat?.tag == tag) return
        if (GatewayStatus.select(SELECTOR, tag)) {
            sbLogSafe("[qartz] route-steer: маршрут ${names[tag] ?: tag}")
            seat = Seat(tag)
            // старые соединения висят на прежнем маршруте — рвём, чтобы приложения переподключились
            if (closeOld) GatewayStatus.closeConnections()
        }
    }

    /** Лог в журнал сервиса — подставляет QartzVpnService. */
    @Volatile var log: (String) -> Unit = {}
    private fun sbLogSafe(s: String) { runCatching { log(s) } }
}
