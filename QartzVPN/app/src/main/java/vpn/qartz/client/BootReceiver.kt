package vpn.qartz.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat

/**
 * Starts the tunnel automatically after boot when "autostart" is on. Only works if VPN permission
 * was already granted (the user connected at least once); otherwise it silently does nothing, since
 * a receiver cannot show the system VPN consent dialog.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        if (!ProfileStore.autoStart(ctx) || !ProfileStore.hasProfile(ctx)) return
        if (VpnService.prepare(ctx) != null) return // no VPN permission → cannot auto-start

        // Assembly resolves server IPs (hybrid engine) → off the main thread; keep the receiver alive.
        val pending = goAsync()
        Thread {
            try {
                if (ConfigAssembler.assemble(ctx) is ConfigAssembler.Result.Ok) {
                    ContextCompat.startForegroundService(ctx, Intent(ctx, QartzVpnService::class.java))
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}
