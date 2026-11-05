package com.generativemascot.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID

/** Copy the selected image into private storage so chat history survives URI expiry. */
internal fun saveChatImage(context: Context, uri: Uri): String {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth in 1..30000 && bounds.outHeight in 1..30000) { "Не удалось прочитать изображение." }
    var sample = 1
    while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 2048) sample *= 2
    val bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: error("Не удалось прочитать изображение.")
    val dir = File(context.filesDir, "chat-images").apply { mkdirs() }
    val file = File(dir, "${UUID.randomUUID()}.jpg")
    try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) } }
    catch (e: Exception) { file.delete(); throw e }
    finally { bitmap.recycle() }
    return file.absolutePath
}

internal fun isCalendarConnectionRequest(text: String): Boolean {
    val value = text.lowercase()
    if (Regex("(?:^|\\s)(не|без)\\s+(хочу\\s+|нужно\\s+|надо\\s+)?(подключ|привяз|синхрониз)").containsMatchIn(value)) return false
    return listOf("календар", "calendar").any(value::contains) &&
        listOf("подключ", "соедин", "привяз", "доступ", "настро", "синхрониз", "connect", "link", "sync").any(value::contains)
}
