package com.generativemascot.app.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import com.generativemascot.app.physics.InstalledPhysics
import com.generativemascot.app.physics.PhoneSample
import com.generativemascot.app.physics.PhysicsSolver
import com.generativemascot.app.physics.ReliefSurface
import com.generativemascot.app.physics.TiltLighting
import java.io.Closeable
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Rasterises the existing approved 2.5D mesh for RemoteViews, which cannot host a TextureView. */
internal class WidgetDepthRenderer(private val installed: InstalledPhysics) : Closeable {
    private val solver = PhysicsSolver(installed.pack)
    private val relief = ReliefSurface(installed.pack)
    private val lighting = TiltLighting()
    private data class Layer(val state: PhysicsSolver.LayerState, val bitmap: Bitmap,
        val paint: Paint, val uv: FloatArray, val indices: ShortArray, val vertices: FloatArray,
        val colors: IntArray)
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
                layers += Layer(state, bitmap, paint, uv, state.layer.triangles.map(Int::toShort).toShortArray(),
                    FloatArray(uv.size), IntArray(state.x.size))
            }
        } catch (error: Throwable) { close(); throw error }
    }

    @Synchronized fun render(size: Int, seconds: Double, sample: PhoneSample, lightingEnabled: Boolean = true): Bitmap {
        require(size in 64..512)
        var remaining = seconds.coerceIn(0.0, .25)
        while (remaining > 0.0) {
            val step = minOf(remaining, .05)
            solver.advance(step, sample.tiltX, sample.tiltY, sample.accelerationX, sample.accelerationY,
                sample.gravityX, sample.gravityY)
            lighting.advance(step.toFloat(), sample)
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
            val shade = WidgetReliefLight(solver.angleX, solver.angleY, lean, lighting.tilt.x, lighting.tilt.y)
            layers.forEach { layer ->
                val depth = relief.depths.getValue(layer.state.layer.id)
                val normals = relief.normals.getValue(layer.state.layer.id)
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
                    layer.colors[i] = if (lightingEnabled) shade.color(normals[i * 3], normals[i * 3 + 1], normals[i * 3 + 2]) else Color.WHITE
                }
                canvas.drawVertices(Canvas.VertexMode.TRIANGLES, layer.vertices.size, layer.vertices, 0,
                    layer.uv, 0, layer.colors, 0, layer.indices, 0, layer.indices.size, layer.paint)
            }
            return output
        } catch (error: Throwable) { output.recycle(); throw error }
    }

    @Synchronized override fun close() { layers.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }; layers.clear() }
}

/** The widget uses Canvas rather than the app's GL shader. Vertex modulation
 * applies the same bounded, depth-derived diffuse shadow without changing alpha. */
internal class WidgetReliefLight(angleX: Float, angleY: Float, lean: Float, tiltX: Float, tiltY: Float) {
    private val neutral = tiltX == 0f && tiltY == 0f
    private val cx = cos(angleX); private val sx = sin(angleX)
    private val cy = cos(angleY); private val sy = sin(angleY)
    private val cl = cos(lean); private val sl = sin(lean)
    private val originalLight = normalized(-.25f, -.35f, 1.2f)
    private val light: FloatArray
    private val flatChange: Float

    init {
        var travelX = -tiltX * 4.2f; var travelY = tiltY * 4.2f
        val travel = sqrt(travelX * travelX + travelY * travelY)
        if (travel > .7f) { travelX *= .7f / travel; travelY *= .7f / travel }
        light = normalized(-.25f + travelX, -.35f + travelY, 1.2f)
        flatChange = rotatedDot(0f, 0f, 1f) - originalLight[2]
    }

    private fun normalized(x: Float, y: Float, z: Float): FloatArray {
        val length = sqrt(x * x + y * y + z * z).coerceAtLeast(.0001f)
        return floatArrayOf(x / length, y / length, z / length)
    }
    private fun rotatedDot(x: Float, y: Float, z: Float): Float {
        val yawX = x * cx + z * sx; val yawZ = -x * sx + z * cx
        val pitchY = y * cy - yawZ * sy; val pitchZ = y * sy + yawZ * cy
        val rx = (yawX * cl - pitchY * sl) * .9f
        val ry = (yawX * sl + pitchY * cl) * .9f
        val length = sqrt(rx * rx + ry * ry + pitchZ * pitchZ).coerceAtLeast(.0001f)
        return (rx * light[0] + ry * light[1] + pitchZ * light[2]) / length
    }
    fun color(nx: Float, ny: Float, nz: Float): Int {
        if (neutral) return Color.WHITE
        val rx = nx * .9f; val ry = ny * .9f
        val length = sqrt(rx * rx + ry * ry + nz * nz).coerceAtLeast(.0001f)
        val original = (rx * originalLight[0] + ry * originalLight[1] + nz * originalLight[2]) / length
        var delta = 1.1f * (rotatedDot(nx, ny, nz) - original - flatChange)
        delta /= sqrt(1f + (delta / .22f) * (delta / .22f))
        // Canvas vertex colors can attenuate a texture but cannot brighten it past
        // white; preserve its original highlights while applying directional shade.
        val value = ((1f + delta.coerceAtMost(0f)) * 255f).toInt().coerceIn(0, 255)
        return Color.rgb(value, value, value)
    }
}
