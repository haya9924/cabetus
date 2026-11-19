package org.cabetus.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 端末のデフォルトネットワーク状態を監視する。
 *
 * 画面が購読している間だけ callback を登録する（[callbackFlow]）。
 * このクラスは「端末がどのネットワークに繋がっているか」だけを扱い、
 * CLASS への到達性・ログイン状態・出席状況は判定しない。
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val connectivityManager: ConnectivityManager?
        get() = context.getSystemService<ConnectivityManager>()

    val status: Flow<NetworkStatus> = callbackFlow {
        val cm = connectivityManager
        if (cm == null) {
            trySend(NetworkStatus.Unknown)
            awaitClose { }
            return@callbackFlow
        }

        fun currentStatus(): NetworkStatus {
            val network = runCatching { cm.activeNetwork }.getOrNull()
                ?: return NetworkStatus(isKnown = true, isConnected = false)
            val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
                ?: return NetworkStatus(isKnown = true, isConnected = false)
            return caps.toNetworkStatus()
        }

        trySend(currentStatus())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(currentStatus())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                trySend(networkCapabilities.toNetworkStatus())
            }

            override fun onLost(network: Network) {
                // 切替直後は activeNetwork がまだ旧ネットワークのことがある。
                trySend(currentStatus())
            }

            override fun onUnavailable() {
                trySend(NetworkStatus(isKnown = true, isConnected = false))
            }
        }

        val registered = runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
        if (!registered) {
            trySend(NetworkStatus.Unknown)
        }

        awaitClose {
            if (registered) {
                runCatching { cm.unregisterNetworkCallback(callback) }
            }
        }
    }.distinctUntilChanged()

    private fun NetworkCapabilities.toNetworkStatus(): NetworkStatus = NetworkStatus(
        isKnown = true,
        isConnected = true,
        hasInternetCapability = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        isValidated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        isMetered = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        transport = NetworkTransport.classify(
            hasWifi = hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            hasCellular = hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            hasEthernet = hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            hasVpn = hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            hasBluetooth = hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH),
        ),
    )
}
