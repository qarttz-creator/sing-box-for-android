package vpn.qartz.client

import android.content.Context

/**
 * The activation / test profile: the current entry routes every client uses to BUILD its tunnel. These
 * are the same gateways the subscription rides on, shipped in the APK so the client can come up without
 * first fetching anything over the open internet. Bringing this tunnel up IS the health check; the
 * user's own per-user subscription is then pulled over that tunnel and the client reloads onto it.
 *
 * Resolution order:
 *  1. the profile self-updated over the tunnel from the control plane's /activation (ProfileStore), then
 *  2. the baked-in seed shipped in the APK (assets/activation_seed.txt).
 *
 * When the routes change, ship a new build (the seed is refreshed) — and already-connected clients also
 * self-update the cache over the tunnel. Later the activation profile's egress can be limited to the
 * control plane alone, since the client switches to its own routes right after bootstrap.
 */
object ActivationProfile {

    fun routes(ctx: Context): String {
        val cached = ProfileStore.activationLinks(ctx)
        if (cached.isNotBlank()) return cached
        return readSeed(ctx)
    }

    fun hasAny(ctx: Context): Boolean = routes(ctx).isNotBlank()

    private fun readSeed(ctx: Context): String = try {
        ctx.assets.open("activation_seed.txt").bufferedReader().use { it.readText() }
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString("\n")
    } catch (e: Exception) {
        ""
    }
}
