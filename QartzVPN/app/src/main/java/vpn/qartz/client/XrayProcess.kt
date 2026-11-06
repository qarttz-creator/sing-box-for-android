package vpn.qartz.client

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs the bundled xray-core as a child process (libxray.so, extracted to nativeLibraryDir).
 * It serves a local SOCKS inbound that sing-box routes into — the hybrid engine, mirroring how the
 * desktop client runs xray.exe beside sing-box. xray handles XHTTP, which sing-box cannot.
 */
object XrayProcess {
    private const val TAG = "XrayProcess"
    private const val BIN = "libxray.so"

    @Volatile
    private var proc: Process? = null

    fun logFile(context: Context): File = File(context.filesDir, "xray.log")

    fun isAlive(): Boolean = proc?.isAlive == true

    /** Start xray from the saved xray.json. Returns false if there is nothing to run / binary missing. */
    @Synchronized
    fun start(context: Context): Boolean {
        stop()
        val cfg = XrayConfigStore.file(context)
        if (!cfg.exists()) return false
        val bin = File(context.applicationInfo.nativeLibraryDir, BIN)
        if (!bin.exists()) {
            Log.e(TAG, "xray binary missing at ${bin.absolutePath}")
            runCatching { logFile(context).appendText("xray binary missing\n") }
            return false
        }
        return try {
            runCatching { logFile(context).writeText("") }
            val pb = ProcessBuilder(bin.absolutePath, "run", "-c", cfg.absolutePath)
                .redirectErrorStream(true)
                .directory(context.filesDir)
            pb.environment()["XRAY_LOCATION_ASSET"] = context.filesDir.absolutePath
            val p = pb.start()
            proc = p
            thread(name = "xray-log") {
                runCatching {
                    p.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> runCatching { logFile(context).appendText(line + "\n") } }
                    }
                }
            }
            Log.i(TAG, "xray started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "xray start failed", e)
            runCatching { logFile(context).appendText("start failed: ${e.message}\n") }
            proc = null
            false
        }
    }

    @Synchronized
    fun stop() {
        runCatching { proc?.destroy() }
        proc = null
    }
}
