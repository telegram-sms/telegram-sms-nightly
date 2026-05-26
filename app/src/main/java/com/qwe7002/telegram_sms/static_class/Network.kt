package com.qwe7002.telegram_sms.static_class

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.util.Log
import com.qwe7002.telegram_sms.MMKV.MMKVConst
import com.qwe7002.telegram_sms.value.Const
import com.tencent.mmkv.MMKV
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.Authenticator
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

object Network {
    private const val DNS_OVER_HTTPS_ADDRESS = "https://1.1.1.1/dns-query"

    /**
     * Single source of truth for the "doh_switch" preference default so every
     * call site resolves the same value (previously some read `true`, others `false`).
     */
    const val DOH_SWITCH_DEFAULT = true

    /**
     * Shared base client. Derived clients are created via [OkHttpClient.newBuilder],
     * which reuses the connection pool and dispatcher thread pools instead of
     * spinning up fresh ones on every request.
     */
    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // java.net.Authenticator.setDefault is a process-wide global; install it once.
    @Volatile
    private var proxyAuthenticatorInstalled = false

    @Suppress("DEPRECATION")
    @JvmStatic
    fun checkNetworkStatus(context: Context): Boolean {
        val manager =
            checkNotNull(context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
        var networkStatus = false
        val networks = manager.allNetworks
        for (network in networks) {
            val networkCapabilities = checkNotNull(manager.getNetworkCapabilities(network))
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                networkStatus = true
            }
        }
        return networkStatus
    }

    @JvmStatic
    fun getUrl( token: String, func: String): String {
        val preferences = MMKV.defaultMMKV()
        val telegramAPIAddress = preferences.getString(
            "api_address",
            "api.telegram.org"
        )
        return "https://$telegramAPIAddress/bot$token/$func"
    }

    @JvmStatic
    fun getOkhttpObj(dohSwitch: Boolean): OkHttpClient {
        val proxyMMKV = MMKV.mmkvWithID(MMKVConst.PROXY_ID)
        // Derive from the shared base client so the connection pool and dispatcher
        // thread pools are reused instead of allocated per request.
        val okhttp = baseClient.newBuilder()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && proxyMMKV.getBoolean(
                "enable",
                false
            )
        ) {
            val policy = ThreadPolicy.Builder().permitAll().build()
            StrictMode.setThreadPolicy(policy)
            val host = proxyMMKV.getString("host", "")
            val port = proxyMMKV.getInt("port", 0)
            val proxyAddr = InetSocketAddress(host, port)
            val proxy = Proxy(Proxy.Type.SOCKS, proxyAddr)
            installProxyAuthenticator()
            okhttp.proxy(proxy)
        }

        if (dohSwitch) {
            // The DoH resolver must travel the same path (incl. SOCKS5 proxy) as the
            // main client, otherwise DNS leaks the proxy and fails on proxy-only
            // networks. It carries no `dns()` of its own and only ever connects to
            // the literal bootstrap IPs below, so there is no resolution recursion.
            val dohHttpClient = okhttp.build()
            okhttp.dns(
                DnsOverHttps.Builder().client(dohHttpClient)
                    .url(DNS_OVER_HTTPS_ADDRESS.toHttpUrl())
                    // Cloudflare-only: the DoH URL host is the literal 1.1.1.1, whose
                    // TLS certificate is valid only for Cloudflare addresses. Google
                    // bootstrap IPs would fail hostname verification against it.
                    .bootstrapDnsHosts(
                        getByIp("1.1.1.1"),
                        getByIp("1.0.0.1"),
                        getByIp("2606:4700:4700::1111"),
                        getByIp("2606:4700:4700::1001")
                    )
                    .includeIPv6(true)
                    .build()
            )
        }

        return okhttp.build()
    }

    /**
     * Installs a single process-wide [Authenticator] for SOCKS5 proxy auth. SOCKS
     * credentials are resolved lazily from MMKV on each challenge, so runtime proxy
     * changes are honoured without re-registering the global default every call.
     */
    private fun installProxyAuthenticator() {
        if (proxyAuthenticatorInstalled) {
            return
        }
        synchronized(this) {
            if (proxyAuthenticatorInstalled) {
                return
            }
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? {
                    val proxyMMKV = MMKV.mmkvWithID(MMKVConst.PROXY_ID)
                    if (!proxyMMKV.getBoolean("enable", false)) {
                        return null
                    }
                    val host = proxyMMKV.getString("host", "") ?: ""
                    val port = proxyMMKV.getInt("port", 0)
                    if (!requestingHost.equals(host, ignoreCase = true) ||
                        port != requestingPort
                    ) {
                        return null
                    }
                    val username = proxyMMKV.getString("username", "") ?: ""
                    val password = proxyMMKV.getString("password", "") ?: ""
                    return PasswordAuthentication(username, password.toCharArray())
                }
            })
            proxyAuthenticatorInstalled = true
        }
    }

    private fun getByIp(host: String): InetAddress {
        try {
            return InetAddress.getByName(host)
        } catch (e: UnknownHostException) {
            Log.e(Const.TAG, "get_by_ip: ", e.fillInStackTrace())
            throw RuntimeException(e)
        }
    }
}
