package vpn.qartz.client

import android.content.Context

/**
 * Turns the saved links into the runtime configs: a sing-box config (always) and, when any XHTTP
 * server is present, an xray config for the hybrid engine. Does DNS resolution (to pin xray server
 * IPs), so it must run OFF the main thread.
 */
object ConfigAssembler {

    sealed class Result {
        object Ok : Result()
        object NoServers : Result()
        data class Error(val message: String) : Result()
    }

    fun assemble(ctx: Context): Result {
        val links = ProfileStore.loadLinks(ctx)

        // Power-user fallback: a raw sing-box JSON pasted via the hidden manual mode.
        if (links.trim().startsWith("{")) {
            ConfigStore.save(ctx, links)
            XrayConfigStore.clear(ctx)
            return Result.Ok
        }

        val all = ProfileStore.servers(ctx)
        if (all.isEmpty()) return Result.NoServers

        val xray = all.filter { it.kind == CoreKind.XRAY }
        val sb = all.filter { it.kind == CoreKind.SINGBOX }

        return try {
            var directIps = emptyList<String>()
            if (xray.isNotEmpty()) {
                val r = XrayConfig.build(xray)
                XrayConfigStore.save(ctx, r.json)
                directIps = r.directIps
            } else {
                XrayConfigStore.clear(ctx)
            }
            val sbCfg = ConfigBuilder.build(
                sbServers = sb,
                xrayPresent = xray.isNotEmpty(),
                socksPort = XrayConfig.SOCKS_PORT,
                directIps = directIps,
                allowLan = ProfileStore.allowLan(ctx),
                ruDirect = ProfileStore.ruDirect(ctx),
            )
            ConfigStore.save(ctx, sbCfg)
            Result.Ok
        } catch (e: Exception) {
            Result.Error(e.message ?: e.toString())
        }
    }
}
