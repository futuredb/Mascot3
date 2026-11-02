package com.generativemascot.app.overlay

import android.content.Context
import com.generativemascot.app.widget.refreshWidgetsForOverlay

/** User intent outlives a service process; destroying a window must not disable it. */
internal object OverlaySession {
    private fun prefs(context: Context) =
        context.getSharedPreferences(OverlaySize.PREFERENCES, Context.MODE_PRIVATE)

    fun validHero(id: String?) = id != null && id.matches(Regex("[A-Za-z0-9_-]{1,100}"))

    fun hero(context: Context): String? = prefs(context).let {
        if (it.getBoolean("enabled", false)) it.getString("hero_id", null)?.takeIf(::validHero) else null
    }

    fun enable(context: Context, heroId: String) {
        require(validHero(heroId))
        val previous = hero(context)
        prefs(context).edit().putString("hero_id", heroId).putBoolean("enabled", true).apply()
        if (previous != heroId) refreshWidgetsForOverlay(context)
    }

    fun disable(context: Context) {
        val wasEnabled = hero(context) != null
        prefs(context).edit().putBoolean("enabled", false).remove("hero_id").apply()
        if (wasEnabled) refreshWidgetsForOverlay(context)
    }
}
