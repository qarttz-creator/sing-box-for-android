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

        // An AmneziaWG config pasted directly (the INI Amnezia exports) → a single awg-endpoint
        // profile. This is the Beeline-surviving transport; the user adds their own config here.
        if (AwgConfig.looksLikeIni(links)) {
            return try {
                val ep = AwgConfig.parseIni(links, "a0")
                    ?: return Result.Error("Не удалось разобрать конфиг AmneziaWG")
                val peer = ep.optJSONArray("peers")?.optJSONObject(0)
                val spec = ServerSpec(
                    "AmneziaWG", "a0", CoreKind.AWG, ep,
                    peer?.optString("address").orEmpty(), peer?.optInt("port") ?: 0,
                )
                XrayConfigStore.clear(ctx)
                GatewayMap.save(ctx, mapOf("a0" to "AmneziaWG"))
                val cfg = ConfigBuilder.build(
                    sbServers = emptyList(),
                    awgServers = listOf(spec),
                    allowLan = ProfileStore.allowLan(ctx),
                    ruDirect = ProfileStore.ruDirect(ctx),
                )
                ConfigStore.save(ctx, cfg)
                Result.Ok
            } catch (e: Exception) {
                Result.Error(e.message ?: e.toString())
            }
        }

        val all = ProfileStore.servers(ctx)
        if (all.isEmpty()) return Result.NoServers

        val xray = all.filter { it.kind == CoreKind.XRAY }
        val sb = all.filter { it.kind == CoreKind.SINGBOX }
        val awg = all.filter { it.kind == CoreKind.AWG }

        return try {
            var directIps = emptyList<String>()
            var xrayMembers = emptyList<XrayConfig.Member>()
            if (xray.isNotEmpty()) {
                val r = XrayConfig.build(xray)
                XrayConfigStore.save(ctx, r.json)
                directIps = r.directIps
                xrayMembers = r.members
            } else {
                XrayConfigStore.clear(ctx)
            }
            // urltest tag -> human gateway name, so the heartbeat can report the active gateway.
            val gwMap = mutableMapOf<String, String>()
            sb.forEach { gwMap[it.tag] = it.name }
            awg.forEach { gwMap[it.tag] = it.name }
            xrayMembers.forEach { gwMap[it.tag] = it.name }
            GatewayMap.save(ctx, gwMap)

            val sbCfg = ConfigBuilder.build(
                sbServers = sb,
                xrayMembers = xrayMembers,
                awgServers = awg,
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
