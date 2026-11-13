package vpn.qartz.client

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ProgressBar
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import vpn.qartz.client.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startVpn() else toast("VPN-разрешение не выдано")
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Periodic UI refresh of the active-route line while the screen is visible.
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val routeTick = object : Runnable {
        override fun run() { updateRouteInfo(); ui.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Not activated yet → send to the activation screen.
        if (!ProfileStore.hasProfile(this)) {
            startActivity(Intent(this, ActivationActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        binding.connectButton.setOnClickListener {
            when (VpnState.stage.value) {
                VpnState.Stage.Connected, VpnState.Stage.Connecting -> stopVpn()
                else -> connect()
            }
        }

        binding.appsButton.setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }

        binding.updateButton.setOnClickListener { checkForUpdate(manual = true) }

        // Settings gear (visible) opens the menu; long-press on the title still works as a fallback.
        binding.settingsButton.setOnClickListener { showDevMenu() }
        binding.titleText.setOnLongClickListener { showDevMenu(); true }

        VpnState.stage.observe(this) { stage -> render(stage); updateRouteInfo() }
        VpnState.message.observe(this) { msg -> if (!msg.isNullOrBlank()) toast(msg) }

        requestNotificationPermission()
        silentSubRefresh()
        checkForUpdate(manual = false)
    }

    private fun render(stage: VpnState.Stage) {
        binding.status.setText(
            when (stage) {
                VpnState.Stage.Connected -> R.string.status_connected
                VpnState.Stage.Connecting -> R.string.status_connecting
                else -> R.string.status_disconnected
            },
        )
        binding.status.setTextColor(
            when (stage) {
                VpnState.Stage.Connected -> 0xFF1E6E72.toInt()   // kyanite accent
                VpnState.Stage.Connecting -> 0xFF9A6B16.toInt()  // warn
                else -> 0xFF6D7076.toInt()                       // muted
            },
        )
        binding.connectButton.setImageResource(
            if (stage == VpnState.Stage.Connected) R.drawable.power_active else R.drawable.power_idle,
        )
    }

    override fun onResume() {
        super.onResume()
        ui.post(routeTick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(routeTick)
    }

    /** Show which gateway/protocol is currently active and how many routes are alive — read live
     *  from GatewayStatus (the running sing-box urltest group). On desktop this is visible; now on
     *  Android too. */
    private fun updateRouteInfo() {
        val connected = VpnState.stage.value == VpnState.Stage.Connected
        val tag = GatewayStatus.selectedTag
        if (!connected || tag.isBlank()) {
            binding.routeInfo.visibility = android.view.View.GONE
            return
        }
        val gw = GatewayMap.name(this, tag).ifBlank { tag }
        val proto = when (tag.firstOrNull()) {
            'x' -> "vless"
            's' -> "hysteria2"
            'a' -> "awg"
            else -> ""
        }
        val routes = GatewayStatus.routes
        val up = routes.count { it.up }
        val head = if (proto.isNotEmpty()) "$gw · $proto" else gw
        binding.routeInfo.text =
            if (routes.isNotEmpty()) "через $head\nживых маршрутов: $up/${routes.size}" else "через $head"
        binding.routeInfo.visibility = android.view.View.VISIBLE
    }

    private fun connect() {
        // Config assembly resolves server IPs (for the hybrid engine), so do it off the main thread.
        thread {
            val res = ConfigAssembler.assemble(this)
            runOnUiThread {
                when (res) {
                    is ConfigAssembler.Result.NoServers -> toast(getString(R.string.no_servers))
                    is ConfigAssembler.Result.Error -> toast("Ошибка сборки конфига: ${res.message}")
                    is ConfigAssembler.Result.Ok -> {
                        val prepare = VpnService.prepare(this)
                        if (prepare != null) vpnPermission.launch(prepare) else startVpn()
                    }
                }
            }
        }
    }

    private fun startVpn() {
        ContextCompat.startForegroundService(this, Intent(this, QartzVpnService::class.java))
    }

    private fun stopVpn() {
        startService(Intent(this, QartzVpnService::class.java).setAction(QartzVpnService.ACTION_STOP))
    }

    /** Pull the latest servers from the saved subscription in the background, silently. */
    private fun silentSubRefresh() {
        val url = ProfileStore.subUrl(this).trim()
        if (url.isBlank()) return
        thread {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    requestMethod = "GET"
                }
                val code = conn.responseCode
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                if (code in 200..299 && text.isNotBlank() && text != ProfileStore.loadLinks(this)) {
                    runOnUiThread { ProfileStore.saveLinks(this, text) }
                }
            } catch (_: Exception) {
                // offline / DPI — keep the servers we already have
            }
        }
    }

    // ---------- in-app update ----------

    private fun checkForUpdate(manual: Boolean) {
        thread {
            val r = Updater.fetchLatest()
            runOnUiThread {
                when {
                    r == null -> if (manual) toast(getString(R.string.upd_check_failed))
                    Updater.isNewer(r) -> promptUpdate(r)
                    manual -> toast(getString(R.string.upd_up_to_date, BuildConfig.VERSION_NAME))
                }
            }
        }
    }

    private fun promptUpdate(r: Updater.Remote) {
        AlertDialog.Builder(this)
            .setTitle(R.string.upd_available_title)
            .setMessage(getString(R.string.upd_available_msg, r.name, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.upd_update) { _, _ -> startUpdate(r) }
            .setNegativeButton(R.string.upd_later, null)
            .show()
    }

    private fun startUpdate(r: Updater.Remote) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.upd_perm_title)
                .setMessage(R.string.upd_perm_msg)
                .setPositiveButton(R.string.upd_perm_open) { _, _ ->
                    try {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
                        )
                    } catch (e: Exception) {
                        toast(e.message ?: "")
                    }
                }
                .setNegativeButton(R.string.close, null)
                .show()
            return
        }

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            setPadding(50, 40, 50, 40)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.upd_downloading)
            .setView(bar)
            .setCancelable(false)
            .create()
        dlg.show()

        thread {
            val apk = Updater.download(this, r) { p ->
                runOnUiThread {
                    if (p >= 0) {
                        bar.isIndeterminate = false
                        bar.progress = p
                    }
                }
            }
            runOnUiThread {
                dlg.dismiss()
                if (apk == null) {
                    toast(getString(R.string.upd_download_failed))
                    return@runOnUiThread
                }
                try {
                    startActivity(Updater.installIntent(this, apk))
                } catch (e: Exception) {
                    toast(getString(R.string.upd_install_failed, e.message ?: ""))
                }
            }
        }
    }

    // ---------- hidden maintenance menu ----------

    private fun showDevMenu() {
        // Клиентская сборка (DEV_TOOLS=false): только безопасные пункты. Просмотр логов и ручной
        // конфиг (показывают сырые ссылки с адресами шлюзов) — лишь в инженерной сборке.
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            Pair(getString(R.string.dev_update), { checkForUpdate(manual = true) }),
            Pair(getString(R.string.dev_refresh), { silentSubRefresh(); toast(getString(R.string.dev_refresh_done)) }),
            Pair(getString(R.string.dev_change_sub), { startActivity(Intent(this, ActivationActivity::class.java)) }),
        )
        if (BuildConfig.DEV_TOOLS) {
            actions.add(Pair(getString(R.string.dev_logs), { showLogs() }))
            actions.add(Pair(getString(R.string.dev_manual), { showManualConfig() }))
        }
        val labels = actions.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.dev_title)
            .setItems(labels) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showManualConfig() {
        val input = EditText(this).apply {
            setText(ProfileStore.loadLinks(this@MainActivity))
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(40, 30, 40, 30)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            minLines = 8
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dev_manual)
            .setView(ScrollView(this).apply { addView(input) })
            .setPositiveButton(R.string.save) { _, _ ->
                ProfileStore.saveLinks(this, input.text.toString())
                // Manual config is an authoritative override — detach the subscription so the next
                // silent /sub refresh (or sub-over-tunnel) does not overwrite what was just pasted
                // (e.g. an AmneziaWG config that isn't in the subscription). Re-add via "Сменить подписку".
                ProfileStore.saveSubUrl(this, "")
                toast(getString(R.string.saved))
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showLogs() {
        val sb = QartzVpnService.logFile(this).let { if (it.exists()) it.readText() else "" }
        val xr = XrayProcess.logFile(this).let { if (it.exists()) it.readText() else "" }
        val text = buildString {
            if (xr.isNotBlank()) {
                append("=== xray ===\n").append(xr.takeLast(8000)).append("\n\n")
            }
            append("=== sing-box ===\n").append(sb)
        }
        val shown = when {
            sb.isBlank() && xr.isBlank() -> "(лог пуст — нажми Подключить хотя бы раз)"
            text.length > 24000 -> text.takeLast(24000)
            else -> text
        }
        val tv = TextView(this).apply {
            setText(shown)
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(40, 30, 40, 30)
        }
        AlertDialog.Builder(this)
            .setTitle("Логи sing-box")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Копировать") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("qartz-log", text))
                toast("Скопировано")
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
