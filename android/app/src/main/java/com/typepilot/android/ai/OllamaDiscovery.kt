package com.typepilot.android.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

/** Finds Ollama servers on the phone's Wi-Fi (port 11434) and the models they have. Takes ~1–2 s for a /24 network. */
object OllamaDiscovery {
    data class Server(val baseUrl: String, val host: String, val models: List<String>)

    private val quick = OkHttpClient.Builder().connectTimeout(450, TimeUnit.MILLISECONDS).readTimeout(1500, TimeUnit.MILLISECONDS).build()

    fun wifiPrefix(context: Context): String? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lp: LinkProperties = cm.getLinkProperties(cm.activeNetwork) ?: return@runCatching null
        val ip = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: return@runCatching null
        ip.substringBeforeLast('.')
    }.getOrNull()

    private fun ownIp(context: Context): String? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }.getOrNull()

    private fun probe(host: String): Server? = runCatching {
        quick.newCall(Request.Builder().url("http://$host:11434/api/tags").build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val arr = JSONObject(resp.body.string()).optJSONArray("models") ?: return@use Server("http://$host:11434/v1", host, emptyList())
            Server("http://$host:11434/v1", host, (0 until arr.length()).map { arr.getJSONObject(it).getString("name") })
        }
    }.getOrNull()

    /** 127.0.0.1 also covers `adb reverse tcp:11434 tcp:11434` over USB, and 10.0.2.2 the Android emulator. */
    suspend fun scan(context: Context): List<Server> = withContext(Dispatchers.IO) {
        val prefix = wifiPrefix(context)
        val own = ownIp(context)
        val hosts = listOf("127.0.0.1", "10.0.2.2") + (if (prefix != null) (1..254).map { "$prefix.$it" }.filter { it != own } else emptyList())
        coroutineScope { hosts.map { h -> async { probe(h) } }.awaitAll() }.filterNotNull().distinctBy { it.models to it.host }
    }

    /** Reachable but not on Wi-Fi? Ollama listening only on localhost is the usual reason. */
    suspend fun reachable(host: String): Boolean = withContext(Dispatchers.IO) { probe(host) != null }
}
