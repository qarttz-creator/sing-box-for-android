package vpn.qartz.client

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import android.widget.ArrayAdapter
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

        populateSpinner()

        binding.connectButton.setOnClickListener {
            when (VpnState.stage.value) {
                VpnState.Stage.Connected, VpnState.Stage.Connecting -> stopVpn()
                else -> connect()
            }
        }

        binding.appsButton.setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }

        // Hidden maintenance menu — long-press the title. End users never need it.
        binding.titleText.setOnLongClickListener { showDevMenu(); true }

        VpnState.stage.observe(this) { stage -> render(stage) }
        VpnState.message.observe(this) { msg -> if (!msg.isNullOrBlank()) toast(msg) }

        requestNotificationPermission()
        silentSubRefresh()
    }

    private fun populateSpinner() {
        val names = ProfileStore.servers(this).map { it.name }
        val items = listOf(getString(R.string.auto_server)) + names
        binding.serverSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        val saved = ProfileStore.loadSelection(this) + 1   // -1 (Auto) -> 0
        binding.serverSpinner.setSelection(saved.coerceIn(0, items.size - 1))
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
                VpnState.Stage.Connected -> 0xFF4CAF50.toInt()   // green
                VpnState.Stage.Connecting -> 0xFFE0A52B.toInt()  // amber
                else -> 0xFF9AA0A6.toInt()                       // grey
            },
        )
        binding.connectButton.setText(
            if (stage == VpnState.Stage.Disconnected) R.string.connect else R.string.disconnect,
        )
        binding.serverSpinner.isEnabled = stage == VpnState.Stage.Disconnected
    }

    private fun connect() {
        val links = ProfileStore.loadLinks(this)

        val runtimeConfig: String = if (links.trim().startsWith("{")) {
            // power-user fallback: raw sing-box JSON (set via the hidden manual mode)
            links
        } else {
            val servers = ProfileStore.servers(this)
            if (servers.isEmpty()) {
                toast(getString(R.string.no_servers))
                return
            }
            val pos = binding.serverSpinner.selectedItemPosition
            ProfileStore.saveSelection(this, pos - 1)
            val selected = if (pos <= 0) servers else listOf(servers[(pos - 1).coerceIn(0, servers.size - 1)])
            try {
                ConfigBuilder.build(selected, ProfileStore.allowLan(this))
            } catch (e: Exception) {
                toast("Ошибка сборки конфига: ${e.message}")
                return
            }
        }

        ConfigStore.save(this, runtimeConfig)

        val prepare = VpnService.prepare(this)
        if (prepare != null) vpnPermission.launch(prepare) else startVpn()
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
                    runOnUiThread {
                        ProfileStore.saveLinks(this, text)
                        if (VpnState.stage.value == VpnState.Stage.Disconnected) populateSpinner()
                    }
                }
            } catch (_: Exception) {
                // offline / DPI — keep the servers we already have
            }
        }
    }

    // ---------- hidden maintenance menu ----------

    private fun showDevMenu() {
        val items = arrayOf(
            getString(R.string.dev_refresh),
            getString(R.string.dev_change_sub),
            getString(R.string.dev_logs),
            getString(R.string.dev_manual),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.dev_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { silentSubRefresh(); toast(getString(R.string.dev_refresh_done)) }
                    1 -> startActivity(Intent(this, ActivationActivity::class.java))
                    2 -> showLogs()
                    3 -> showManualConfig()
                }
            }
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
                populateSpinner()
                toast(getString(R.string.saved))
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showLogs() {
        val file = QartzVpnService.logFile(this)
        val text = if (file.exists()) file.readText() else ""
        val shown = when {
            text.isBlank() -> "(лог пуст — нажми Подключить хотя бы раз)"
            text.length > 20000 -> text.takeLast(20000)
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
