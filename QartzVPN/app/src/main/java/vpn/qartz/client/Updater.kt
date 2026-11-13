package vpn.qartz.client

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater. Reads a tiny version.json published next to the APK on the GitHub release,
 * compares versionCode against this build, and (if newer) downloads the universal APK and hands
 * it to the system installer. All builds are signed with the same key, so the update installs in
 * place and keeps the user's activation.
 */
object Updater {
    private const val VERSION_URL =
        "https://github.com/qarttz-creator/sing-box-for-android/releases/download/android-latest/version.json"

    data class Remote(
        val code: Int,
        val name: String,
        val url: String,
        val notes: String,
        val critical: Boolean,
        val minSupported: Int,
    )

    /** Blocking network call — run on a background thread. Returns null on any error. */
    fun fetchLatest(): Remote? = try {
        val conn = (URL(VERSION_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            requestMethod = "GET"
        }
        val code = conn.responseCode
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        if (code !in 200..299) {
            null
        } else {
            JSONObject(text).let {
                Remote(
                    it.getInt("versionCode"),
                    it.optString("versionName"),
                    it.getString("url"),
                    it.optString("notes", ""),
                    it.optBoolean("critical", false),
                    it.optInt("min_supported", 0),
                )
            }
        }
    } catch (e: Exception) {
        null
    }

    fun isNewer(r: Remote): Boolean = r.code > BuildConfig.VERSION_CODE

    /** Обновление обязательно: critical, либо текущий код ниже min_supported (ретайрим старые сборки). */
    fun isForced(r: Remote): Boolean =
        r.critical || (r.minSupported > 0 && BuildConfig.VERSION_CODE < r.minSupported)

    /** Download the APK to the cache dir. Blocking. onProgress(percent) with -1 when size unknown. */
    fun download(ctx: Context, r: Remote, onProgress: (Int) -> Unit): File? = try {
        val conn = (URL(r.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
        }
        val total = conn.contentLengthLong
        val out = File(ctx.cacheDir, "update.apk")
        conn.inputStream.use { ins ->
            out.outputStream().use { os ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                var n: Int
                while (ins.read(buf).also { n = it } >= 0) {
                    os.write(buf, 0, n)
                    read += n
                    onProgress(if (total > 0) ((read * 100) / total).toInt() else -1)
                }
            }
        }
        conn.disconnect()
        out
    } catch (e: Exception) {
        null
    }

    fun installIntent(ctx: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
