package vpn.qartz.client

import android.content.Context

/**
 * The activation / test profile: a small set of robust entry routes used to BOOTSTRAP a tunnel when
 * the user's own subscription host is blocked, and as the route test-check.
 *
 * Resolution order:
 *  1. the profile self-updated over the tunnel from the control plane's /activation (ProfileStore), then
 *  2. the baked-in seed shipped in the APK (assets/activation_seed.txt) — the only thing available on a
 *     brand-new install on a blocked network.
 *
 * On a blocked network a fresh client with no cache relies on the seed; once it has connected once, the
 * self-updated cache takes over so the baked-in list never has to be current.
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
