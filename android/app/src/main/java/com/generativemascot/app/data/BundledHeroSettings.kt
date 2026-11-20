package com.generativemascot.app.data

import android.content.Context
import java.io.File
import org.json.JSONObject

/** Restore explicitly bundled style and chat mode only when no user setting exists. */
internal object BundledHeroSettings {
    private val lock = Any()

    fun importIfAbsent(context: Context, heroId: String, directory: File) = synchronized(lock) {
        val file = File(directory, "team-settings.json")
        if (!file.isFile) return@synchronized
        val data = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@synchronized
        val choice = data.optJSONObject("choice")
        val prefs = context.getSharedPreferences("hero_character_choices", Context.MODE_PRIVATE)
        if (choice != null && !prefs.contains("$heroId:type") && !prefs.contains("$heroId:style")) {
            val x = if (choice.has("x") && !choice.isNull("x")) choice.optDouble("x").toFloat() else null
            val y = if (choice.has("y") && !choice.isNull("y")) choice.optDouble("y").toFloat() else null
            val parsed = runCatching { CharacterCreationChoice(choice.optString("type"), choice.optString("style"), x, y) }.getOrNull()
            if (parsed != null) HeroLocalStore(context).saveCharacterChoice(heroId, parsed)
        }
        val interaction = data.optString("interaction").takeIf { value -> interactionPersonalities.any { it.id == value } }
        val chat = context.getSharedPreferences("mascot_chat_interaction", Context.MODE_PRIVATE)
        if (interaction != null && !chat.contains(heroId)) check(chat.edit().putString(heroId, interaction).commit())
    }
}
