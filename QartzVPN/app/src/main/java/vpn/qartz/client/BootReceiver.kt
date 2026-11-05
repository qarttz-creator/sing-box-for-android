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

        try {
            val servers = ProfileStore.servers(ctx)
            if (servers.isEmpty()) return
            val cfg = ConfigBuilder.build(servers, ProfileStore.allowLan(ctx), ProfileStore.ruDirect(ctx))
            ConfigStore.save(ctx, cfg)
            ContextCompat.startForegroundService(ctx, Intent(ctx, QartzVpnService::class.java))
        } catch (_: Exception) {
        }
    }
}
