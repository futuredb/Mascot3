package com.generativemascot.app.data

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Keeps normal DNS and HTTPS verification in place for provider requests. */
internal class ProviderDnsFallback(
    private val system: Dns = Dns.SYSTEM,
    private val acceptsHost: (String) -> Boolean,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> = try {
        system.lookup(hostname)
    } catch (error: UnknownHostException) {
        if (!acceptsHost(hostname)) throw error
        lookupIpv4(hostname).ifEmpty { throw error }
    }

    private fun lookupIpv4(host: String): List<InetAddress> = runCatching {
        val url = "https://1.1.1.1/dns-query".toHttpUrl().newBuilder()
            .addQueryParameter("name", host).addQueryParameter("type", "A").build()
        val query = Request.Builder().url(url).header("Accept", "application/dns-json").build()
        resolverClient.newCall(query).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val result = JSONObject(response.body?.string().orEmpty())
            if (result.optInt("Status", -1) != 0) return@use emptyList()
            val answers = result.optJSONArray("Answer") ?: return@use emptyList()
            (0 until answers.length()).mapNotNull { index ->
                val answer = answers.optJSONObject(index) ?: return@mapNotNull null
                if (answer.optInt("type") != 1) return@mapNotNull null
                val octets = answer.optString("data").split('.').map { it.toIntOrNull() ?: return@mapNotNull null }
                if (octets.size != 4 || octets.any { it !in 0..255 }) return@mapNotNull null
                InetAddress.getByAddress(host, octets.map(Int::toByte).toByteArray())
            }
        }
    }.getOrDefault(emptyList())

    private companion object {
        val resolverClient = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}
