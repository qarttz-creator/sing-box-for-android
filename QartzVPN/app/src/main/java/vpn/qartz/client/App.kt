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
        Libbox.setup(
            SetupOptions().apply {
                basePath = base.path
                workingPath = working.path
                tempPath = temp.path
                logMaxLines = 3000
                debug = BuildConfig.DEBUG
                crashReportSource = "QartzVPN"
                appVersion = BuildConfig.VERSION_CODE.toString()
                appMarketingVersion = BuildConfig.VERSION_NAME
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
