package org.cabetus.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService

object NetworkUtil {
    /** 現在のアクティブネットワークが従量制（モバイルデータ等）か。 */
    fun isActiveNetworkMetered(context: Context): Boolean {
        val cm = context.getSystemService<ConnectivityManager>() ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return true
        // NOT_METERED が付いていれば非従量制
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
