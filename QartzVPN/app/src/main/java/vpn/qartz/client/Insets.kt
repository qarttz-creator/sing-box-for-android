package vpn.qartz.client

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Pad [root] by the system bars (status bar + navigation bar) on top of whatever padding it
 * already declares. Needed on Android 15 (targetSdk 35) where apps draw edge-to-edge by default,
 * otherwise bottom content (e.g. the Save button, the last list rows) slips under the nav bar.
 */
fun applySystemBarInsets(root: View) {
    val l = root.paddingLeft
    val t = root.paddingTop
    val r = root.paddingRight
    val b = root.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(root)
}
