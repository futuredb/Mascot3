package com.generativemascot.app.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class ChatMessage(val user: Boolean, val text: String, val imagePath: String? = null, val capabilities: Boolean = false)

/** Conversations stay on this device and are isolated by saved character ID. */
internal class ChatHistoryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("mascot_chat_history", Context.MODE_PRIVATE)

    fun read(heroId: String): List<ChatMessage> = runCatching {
        val rows = JSONArray(preferences.getString(heroId, "[]"))
        buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val text = row.optString("text")
                if (row.has("user") && (text.isNotBlank() || row.optString("imagePath").isNotBlank() || row.optBoolean("capabilities")))
                    add(ChatMessage(row.optBoolean("user"), text, row.optString("imagePath").takeIf { it.isNotBlank() }, row.optBoolean("capabilities")))
            }
        }
    }.getOrDefault(emptyList())

    fun write(heroId: String, messages: List<ChatMessage>) {
        val rows = JSONArray()
        messages.forEach { rows.put(JSONObject().put("user", it.user).put("text", it.text).put("imagePath", it.imagePath).put("capabilities", it.capabilities)) }
        preferences.edit().putString(heroId, rows.toString()).apply()
    }
}
