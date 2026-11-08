package vpn.qartz.client

import android.app.Application
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.util.Locale

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        app = this
        runCatching { Libbox.setLocale(Locale.getDefault().toLanguageTag()) }
    }

    /** Must be called once before creating the libbox command server. */
    @Synchronized
    fun setupLibbox() {
        if (libboxReady) return
        val base = filesDir.also { it.mkdirs() }
        val working = (getExternalFilesDir(null) ?: filesDir).also { it.mkdirs() }
        val temp = cacheDir.also { it.mkdirs() }
        // amnezia-box libbox SetupOptions (older/simpler than upstream 1.14.x — no log/version fields).
        Libbox.setup(
            SetupOptions().apply {
                basePath = base.path
                workingPath = working.path
                tempPath = temp.path
                fixAndroidStack = true
            },
        )
        libboxReady = true
    }

    companion object {
        lateinit var app: App
            private set

        @Volatile
        private var libboxReady = false

        val connectivity: ConnectivityManager
            get() = app.getSystemService()!!
    }
}
