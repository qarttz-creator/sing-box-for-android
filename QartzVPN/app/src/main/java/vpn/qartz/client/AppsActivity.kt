package vpn.qartz.client

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import vpn.qartz.client.databinding.ActivityAppsBinding

/** Per-app routing: pick a mode and the apps it applies to. */
class AppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppsBinding
    private data class AppItem(val label: String, val pkg: String)
    private var apps: List<AppItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        binding.modeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.mode_off),
                getString(R.string.mode_exclude),
                getString(R.string.mode_include),
            ),
        )
        binding.modeSpinner.setSelection(ProfileStore.routingMode(this).coerceIn(0, 2))

        binding.saveApps.setOnClickListener { save() }

        loadApps()
    }

    private fun loadApps() {
        binding.appsHint.setText(R.string.apps_loading)
        Thread {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val resolved = pm.queryIntentActivities(intent, 0)
            val list = resolved
                .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .filter { it.pkg != packageName }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                apps = list
                populate()
                binding.appsHint.setText(R.string.apps_hint)
            }
        }.start()
    }

    private fun populate() {
        binding.appList.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        binding.appList.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_multiple_choice,
            apps.map { it.label },
        )
        val selected = ProfileStore.selectedApps(this)
        apps.forEachIndexed { i, a ->
            if (a.pkg in selected) binding.appList.setItemChecked(i, true)
        }
    }

    private fun save() {
        val chosen = mutableSetOf<String>()
        for (i in apps.indices) {
            if (binding.appList.isItemChecked(i)) chosen.add(apps[i].pkg)
        }
        ProfileStore.saveSelectedApps(this, chosen)
        ProfileStore.saveRoutingMode(this, binding.modeSpinner.selectedItemPosition)
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
