package com.generativemascot.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import com.caverock.androidsvg.SVG

internal fun overlayMenuIcon(context: Context, asset: String, side: Int, tintWhite: Boolean = false): BitmapDrawable {
    val picture = SVG.getFromAsset(context.assets, asset).renderToPicture()
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).apply {
        scale(side.toFloat() / picture.width.coerceAtLeast(1), side.toFloat() / picture.height.coerceAtLeast(1))
        drawPicture(picture)
    }
    return BitmapDrawable(context.resources, bitmap).apply {
        if (tintWhite) setTint(Color.WHITE)
        setBounds(0, 0, side, side)
    }
}
