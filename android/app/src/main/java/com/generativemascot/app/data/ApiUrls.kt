package com.generativemascot.app.data

import com.generativemascot.app.BuildConfig

fun rewriteHost(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val host = BuildConfig.API_BASE_URL.trimEnd('/')
    if (url.startsWith('/')) return "$host$url"
    if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("file:")) {
        return "$host/$url"
    }
    return url.replace(Regex("^https?://[^/]+"), host)
}

fun resolveMediaUrl(url: String?): String? {
    return HeroLocalStore.current?.resolve(url) ?: rewriteHost(url)
}
