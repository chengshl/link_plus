package io.github.shaonialife.buicklinkplus.gateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** 只为测试 socket 选择互联网线路，不修改 CarPlay 的进程网络绑定。 */
class GatewayNetworkRoute private constructor(
    private val manager: ConnectivityManager,
    private val network: Network,
    val client: OkHttpClient,
) : AutoCloseable {
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun watch(onLost: () -> Unit) {
        val watcher = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(lost: Network) {
                if (lost == network) onLost()
            }
        }
        manager.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), watcher,
        )
        callback = watcher
        if (manager.getNetworkCapabilities(network) == null) onLost()
    }

    override fun close() {
        callback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        callback = null
    }

    companion object {
        /** 在后台线程创建，以免初始化 TLS 信任存储阻塞界面。 */
        @Suppress("DEPRECATION")
        fun create(context: Context): GatewayNetworkRoute {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.allNetworks.mapNotNull { candidate ->
                val caps = manager.getNetworkCapabilities(candidate) ?: return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
                val rank = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> 0
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> 1
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                    else -> 3
                }
                rank to candidate
            }.minByOrNull { it.first }?.second ?: throw IOException("No Internet network")
            val client = OkHttpClient.Builder()
                .socketFactory(network.socketFactory)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
                })
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()
            return GatewayNetworkRoute(manager, network, client)
        }
    }
}
