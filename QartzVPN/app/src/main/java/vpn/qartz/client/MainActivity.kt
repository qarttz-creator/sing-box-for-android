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

        binding.configInput.setText(ConfigStore.load(this))

        binding.saveButton.setOnClickListener {
            ConfigStore.save(this, binding.configInput.text.toString())
            toast("Сохранено")
        }

        binding.connectButton.setOnClickListener {
            when (VpnState.stage.value) {
                VpnState.Stage.Connected, VpnState.Stage.Connecting -> stopVpn()
                else -> connect()
            }
        }

        binding.logsButton.setOnClickListener { showLogs() }

        VpnState.stage.observe(this) { stage -> render(stage) }
        VpnState.message.observe(this) { msg -> if (!msg.isNullOrBlank()) toast(msg) }

        requestNotificationPermission()
    }

    private fun render(stage: VpnState.Stage) {
        binding.status.setText(
            when (stage) {
                VpnState.Stage.Connected -> R.string.status_connected
                VpnState.Stage.Connecting -> R.string.status_connecting
                else -> R.string.status_disconnected
            },
        )
        binding.connectButton.setText(
            if (stage == VpnState.Stage.Disconnected) R.string.connect else R.string.disconnect,
        )
    }

    private fun connect() {
        ConfigStore.save(this, binding.configInput.text.toString())
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            vpnPermission.launch(prepare)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        val intent = Intent(this, QartzVpnService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpn() {
        startService(Intent(this, QartzVpnService::class.java).setAction(QartzVpnService.ACTION_STOP))
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
