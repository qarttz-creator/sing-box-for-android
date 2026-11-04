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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.configInput.setText(ProfileStore.loadLinks(this))
        binding.subUrl.setText(ProfileStore.subUrl(this))
        populateSpinner()

        binding.subButton.setOnClickListener { refreshFromSub() }

        binding.saveButton.setOnClickListener {
            ProfileStore.saveLinks(this, binding.configInput.text.toString())
            populateSpinner()
            toast("Сохранено")
        }

        binding.connectButton.setOnClickListener {
            when (VpnState.stage.value) {
                VpnState.Stage.Connected, VpnState.Stage.Connecting -> stopVpn()
                else -> connect()
            }
        }

        binding.appsButton.setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }

        binding.logsButton.setOnClickListener { showLogs() }

        VpnState.stage.observe(this) { stage -> render(stage) }
        VpnState.message.observe(this) { msg -> if (!msg.isNullOrBlank()) toast(msg) }

        requestNotificationPermission()
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
        val text = binding.configInput.text.toString()
        ProfileStore.saveLinks(this, text)

        val runtimeConfig: String = if (text.trim().startsWith("{")) {
            // power-user fallback: raw sing-box JSON
            text
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
                ConfigBuilder.build(selected)
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

    private fun refreshFromSub() {
        val url = binding.subUrl.text.toString().trim()
        if (url.isBlank()) {
            toast(getString(R.string.sub_url_hint))
            return
        }
        ProfileStore.saveSubUrl(this, url)
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
                if (code !in 200..299 || text.isBlank()) throw RuntimeException("HTTP $code")
                runOnUiThread {
                    binding.configInput.setText(text)
                    ProfileStore.saveLinks(this, text)
                    populateSpinner()
                    toast(getString(R.string.sub_updated))
                }
            } catch (e: Exception) {
                runOnUiThread { toast(getString(R.string.sub_error) + ": " + (e.message ?: "")) }
            }
        }
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
