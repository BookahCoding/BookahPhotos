package dev.bookah.photos

import android.content.Context
import android.net.ConnectivityManager

object NetworkCheck {
    /**
     * True when the phone is on mobile data (or a network the phone itself says is metered, such as a Wi-Fi hotspot of another phone). Asked of Android as a whole, so it also
     * holds while a VPN such as Tailscale is on. When Android cannot tell, the network counts as metered: the safe side for somebody's data plan.
     */
    fun isMetered(context: Context): Boolean = runCatching {
        context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
    }.getOrDefault(true)
}
