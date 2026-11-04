package vpn.qartz.client

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import vpn.qartz.client.databinding.ActivityActivationBinding

/**
 * First-run (and "change subscription") screen. The user pastes one thing — a subscription
 * link from the admin — and the client pulls its servers. No raw config fields are exposed here.
 */
class ActivationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityActivationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActivationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        binding.activateInput.setText(ProfileStore.subUrl(this))
        binding.activateButton.setOnClickListener { activate() }
    }

    private fun activate() {
        val input = binding.activateInput.text.toString().trim()
        if (input.isBlank()) {
            toast(getString(R.string.activate_empty))
            return
        }

        // A pasted subscription link → fetch servers over HTTP.
        if (input.startsWith("http://", true) || input.startsWith("https://", true)) {
            fetchSub(input)
            return
        }

        // Fallback: the user pasted the server lines (or raw JSON) directly.
        if (input.startsWith("{") || input.contains("://")) {
            ProfileStore.saveLinks(this, input)
            ProfileStore.saveSubUrl(this, "")
            done()
            return
        }

        toast(getString(R.string.activate_bad))
    }

    private fun fetchSub(url: String) {
        setBusy(true)
        thread {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000
                    readTimeout = 12000
                    requestMethod = "GET"
                }
                val code = conn.responseCode
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                if (code !in 200..299 || text.isBlank()) throw RuntimeException("HTTP $code")
                runOnUiThread {
                    ProfileStore.saveSubUrl(this, url)
                    ProfileStore.saveLinks(this, text)
                    setBusy(false)
                    done()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    toast(getString(R.string.activate_error) + ": " + (e.message ?: ""))
                }
            }
        }
    }

    private fun done() {
        if (!ProfileStore.hasProfile(this)) {
            toast(getString(R.string.activate_bad))
            return
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun setBusy(busy: Boolean) {
        binding.activateButton.isEnabled = !busy
        binding.activateInput.isEnabled = !busy
        binding.activateProgress.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
