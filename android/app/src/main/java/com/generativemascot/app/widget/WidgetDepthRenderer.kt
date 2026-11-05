package com.generativemascot.app.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import com.generativemascot.app.physics.InstalledPhysics
import com.generativemascot.app.physics.PhoneSample
import com.generativemascot.app.physics.PhysicsSolver
import com.generativemascot.app.physics.ReliefSurface
import java.io.Closeable
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** Rasterises the existing approved 2.5D mesh for RemoteViews, which cannot host a TextureView. */
internal class WidgetDepthRenderer(private val installed: InstalledPhysics) : Closeable {
    private val solver = PhysicsSolver(installed.pack)
    private val relief = ReliefSurface(installed.pack)
    private data class Layer(val state: PhysicsSolver.LayerState, val bitmap: Bitmap,
        val paint: Paint, val uv: FloatArray, val indices: ShortArray, val vertices: FloatArray)
    private val layers = mutableListOf<Layer>()

    init {
        try {
            solver.states.sortedBy { it.layer.order }.forEach { state ->
                val file = File(installed.directory, installed.pack.assets.getValue(state.layer.texture).file)
                val options = BitmapFactory.Options().apply { inSampleSize = 2; inScaled = false }
                val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path, options))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                }
                val uv = FloatArray(state.x.size * 2)
                state.layer.vertices.forEachIndexed { i, vertex ->
                    uv[i * 2] = vertex[3] * bitmap.width
                    uv[i * 2 + 1] = vertex[4] * bitmap.height
                }
                layers += Layer(state, bitmap, paint, uv, state.layer.triangles.map(Int::toShort).toShortArray(), FloatArray(uv.size))
            }
        } catch (error: Throwable) { close(); throw error }
    }

    @Synchronized fun render(size: Int, seconds: Double, sample: PhoneSample): Bitmap {
        require(size in 64..512)
        var remaining = seconds.coerceIn(0.0, .25)
        while (remaining > 0.0) {
            val step = minOf(remaining, .05)
            solver.advance(step, sample.tiltX, sample.tiltY, sample.accelerationX, sample.accelerationY,
                sample.gravityX, sample.gravityY)
            remaining -= step
        }
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            val pack = installed.pack
            val aspect = pack.height.toFloat() / pack.width
            val fittedWidth = minOf(size.toFloat(), size / aspect)
            val scale = fittedWidth * installed.framing.scale
            val centerX = size * .5f + installed.framing.offsetX * fittedWidth
            val centerY = size * .5f + installed.framing.offsetY * fittedWidth
            val cx = cos(solver.angleX); val sx = sin(solver.angleX)
            val cy = cos(solver.angleY); val sy = sin(solver.angleY)
            val lean = solver.leanAngle
            val cl = cos(lean); val sl = sin(lean)
            layers.forEach { layer ->
                val depth = relief.depths.getValue(layer.state.layer.id)
                layer.state.x.indices.forEach { i ->
                    val x = layer.state.x[i] - .5f
                    val y = layer.state.y[i] - aspect * .5f
                    val yawX = x * cx + depth[i] * sx
                    val yawZ = -x * sx + depth[i] * cx
                    val pitchY = y * cy - yawZ * sy + solver.breathingOffset
                    val rotatedX = yawX * cl - (pitchY - solver.supportY) * sl
                    val rotatedY = yawX * sl + (pitchY - solver.supportY) * cl + solver.supportY
                    layer.vertices[i * 2] = centerX + (rotatedX + solver.bodyX) * scale
                    layer.vertices[i * 2 + 1] = centerY + (rotatedY + solver.bodyY) * scale
                }
                canvas.drawVertices(Canvas.VertexMode.TRIANGLES, layer.vertices.size, layer.vertices, 0,
                    layer.uv, 0, null, 0, layer.indices, 0, layer.indices.size, layer.paint)
            }
            return output
        } catch (error: Throwable) { output.recycle(); throw error }
    }

    @Synchronized override fun close() { layers.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }; layers.clear() }
}
