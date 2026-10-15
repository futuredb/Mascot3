package com.generativemascot.app.physics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class PhysicsAsset(val file: String, val sha256: String, val size: Long = 0, val url: String = "")
@Serializable data class PhysicsQa(val approved: Boolean = false, @SerialName("neutral_error") val neutralError: Float = 1f)
@Serializable data class PhysicsLayer(
    val id: String, @SerialName("parent_id") val parentId: String? = null,
    val material: String, val order: Int, val texture: String, val depth: String,
    val damping: Float, val stiffness: Float,
    @SerialName("max_displacement") val maxDisplacement: Float,
    val vertices: List<List<Float>>, val triangles: List<Int>, val edges: List<List<Float>>,
    val bends: List<List<Float>> = emptyList(),
    val pins: List<List<Int>> = emptyList(),
)
@Serializable data class PhysicsPack(
    val format: String, val version: Int,
    @SerialName("source_sha256") val sourceSha256: String,
    @SerialName("anchor_sha256") val anchorSha256: String,
    @SerialName("video_anchor_sha256") val videoAnchorSha256: String = "",
    val width: Int, val height: Int,
    @SerialName("max_tilt_degrees") val maxTiltDegrees: Float,
    val layers: List<PhysicsLayer>, val assets: Map<String, PhysicsAsset>, val qa: PhysicsQa,
    @SerialName("max_body_offset") val maxBodyOffset: Float = .00524f,
    @SerialName("max_lean_degrees") val maxLeanDegrees: Float = 0f,
) {
    fun validate() {
        require(format == "mascot-physics-2.5d" && version == 1) { "Неподдерживаемый физический пакет" }
        require(qa.approved && qa.neutralError.isFinite() && qa.neutralError in 0f.. .025f) { "Пакет не прошёл проверку качества" }
        require(width in 64..2048 && height in 64..2048 && maxTiltDegrees == 12f)
        require(maxBodyOffset.isFinite() && maxBodyOffset in 0f.. .05f)
        require(maxLeanDegrees.isFinite() && maxLeanDegrees in 0f..12f)
        val hash = Regex("[a-f0-9]{64}")
        require(hash.matches(sourceSha256) && hash.matches(anchorSha256))
        require(layers.size in 2..17 && layers.sumOf { it.vertices.size } <= 12000)
        require(layers.any { it.material != "rigid" })
        val known = mutableMapOf<String, PhysicsLayer>()
        layers.forEach { l ->
            require(l.id.matches(Regex("[a-z][a-z0-9_-]{0,63}")) && l.id !in known)
            require(if (l.id == "body") l.parentId == null else l.parentId in known)
            require(l.material in setOf("rigid", "hair", "cord", "cloth"))
            require(l.order in 0..32)
            require(l.vertices.size in 3..12000 && l.triangles.isNotEmpty() && l.triangles.size % 3 == 0)
            require(l.triangles.all { it in l.vertices.indices })
            require(l.vertices.all { v -> v.size == 6 && v.all(Float::isFinite) && v[0] in 0f..1f && v[1] in 0f..(height.toFloat()/width + .001f) && v[2] in -0.1f..0.1f && v[3] in 0f..1f && v[4] in 0f..1f && v[5] in 0f..1f })
            for (i in l.triangles.indices step 3) {
                val a = l.vertices[l.triangles[i]]; val b = l.vertices[l.triangles[i + 1]]; val c = l.vertices[l.triangles[i + 2]]
                require((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]) > 1e-9f) { "Повреждена геометрия физического пакета" }
            }
            require(l.vertices.any { it[5] == 0f } && (l.material == "rigid" || l.vertices.any { it[5] > .1f }))
            require((l.edges + l.bends).all { e -> e.size == 3 && e.all(Float::isFinite) && e[0] == e[0].toInt().toFloat() && e[1] == e[1].toInt().toFloat() && e[0].toInt() in l.vertices.indices && e[1].toInt() in l.vertices.indices && e[0] != e[1] && e[2] > 0 && e[2] < 2f })
            require(l.damping.isFinite() && l.damping in 1f..20f && l.stiffness.isFinite() && l.stiffness in 1f..200f && l.maxDisplacement.isFinite() && l.maxDisplacement in 0.001f..0.06f)
            val parent = known[l.parentId]
            require(l.pins.all { it.size == 2 && it[0] in l.vertices.indices && parent != null && it[1] in parent.vertices.indices && l.vertices[it[0]][5] == 0f })
            if (parent != null) {
                require(l.pins.isNotEmpty())
                if (l.material != "rigid") require(l.vertices.indices.filter { l.vertices[it][5] == 0f }.all { i -> l.pins.any { it[0] == i } })
            }
            require(l.texture in assets && l.depth in assets)
            known[l.id] = l
        }
        assets.values.forEach { require(it.file.matches(Regex("[a-z][a-z0-9_-]*\\.png")) && hash.matches(it.sha256) && it.size in 1..(30L * 1024 * 1024)) }
    }
}

/** Platform independent fixed-step solver; positions and gravity use image-space coordinates. */
class PhysicsSolver(val pack: PhysicsPack) {
    class LayerState(val layer: PhysicsLayer) {
        val x = FloatArray(layer.vertices.size) { layer.vertices[it][0] }
        val y = FloatArray(layer.vertices.size) { layer.vertices[it][1] }
        val previousX = x.copyOf(); val previousY = y.copyOf()
    }
    val states = pack.layers.map(::LayerState)
    private val byId = states.associateBy { it.layer.id }
    private var accumulator = 0.0
    private var clock = 0.0
    var angleX = 0f; private set
    var angleY = 0f; private set
    var bodyX = 0f; private set
    var bodyY = 0f; private set
    val leanAngle get() = angleX * pack.maxLeanDegrees / pack.maxTiltDegrees
    val supportY = pack.layers.first { it.id == "body" }.vertices.maxOf { it[1] } - pack.height.toFloat()/pack.width/2f
    private var velocityX = 0f
    private var velocityY = 0f
    private var bodyVelocityX = 0f
    private var bodyVelocityY = 0f
    var neutral = false
    val settled: Boolean get() = kotlin.math.abs(angleX) + kotlin.math.abs(angleY) < .0008f &&
        kotlin.math.abs(velocityX) + kotlin.math.abs(velocityY) < .005f &&
        kotlin.math.abs(bodyX) + kotlin.math.abs(bodyY) < .0004f &&
        kotlin.math.abs(bodyVelocityX) + kotlin.math.abs(bodyVelocityY) < .002f && states.all { s -> s.x.indices.all { i -> kotlin.math.abs(s.x[i] - s.layer.vertices[i][0]) + kotlin.math.abs(s.y[i] - s.layer.vertices[i][1]) < .0008f } }
    fun reset() {
        accumulator = 0.0; clock = 0.0; angleX = 0f; angleY = 0f; velocityX = 0f; velocityY = 0f
        bodyX = 0f; bodyY = 0f; bodyVelocityX = 0f; bodyVelocityY = 0f
        states.forEach { s -> s.x.indices.forEach { i -> s.x[i] = s.layer.vertices[i][0]; s.y[i] = s.layer.vertices[i][1]; s.previousX[i] = s.x[i]; s.previousY[i] = s.y[i] } }
    }
    fun advance(seconds: Double, tiltX: Float, tiltY: Float, accelerationX: Float = 0f, accelerationY: Float = 0f, gravityX: Float = 0f, gravityY: Float = 0f) {
        if (!seconds.isFinite() || seconds < 0) return
        if (seconds > .25) { reset(); return }
        accumulator += seconds.coerceAtMost(.05)
        val dt = 1.0 / 120
        var steps = 0
        while (accumulator + 1e-10 >= dt && steps++ < 6) {
            step(dt.toFloat(), tiltX.takeIf(Float::isFinite) ?: 0f, tiltY.takeIf(Float::isFinite) ?: 0f, accelerationX.takeIf(Float::isFinite) ?: 0f, accelerationY.takeIf(Float::isFinite) ?: 0f, gravityX.takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f, gravityY.takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f)
            accumulator -= dt
        }
    }
    private fun step(dt: Float, tiltX: Float, tiltY: Float, ax: Float, ay: Float, gx: Float, gy: Float) {
        clock += dt
        val limit = Math.toRadians(pack.maxTiltDegrees.toDouble()).toFloat()
        // Rolling an upright phone is mainly rotation around screen Z. Its
        // projected gravity changes even when quaternion X/Y stay near zero.
        fun response(input: Float): Float {
            // A small phone tilt should be visible while the render remains
            // bounded to the QA-approved poses. Ignore sub-degree sensor noise.
            val deadZone = .004f
            val magnitude = (kotlin.math.abs(input)-deadZone).coerceAtLeast(0f)
            return (kotlin.math.sign(input)*magnitude*3f).coerceIn(-limit,limit)
        }
        val targetX = if (neutral) 0f else response(tiltX + gx * .7f)
        val targetY = if (neutral) 0f else response(tiltY + gy * .7f)
        bodyVelocityX += (65f * (targetX / limit * pack.maxBodyOffset - bodyX) - 13f * bodyVelocityX - if(neutral) 0f else ax.coerceIn(-12f,12f)*.025f) * dt
        bodyVelocityY += (65f * (targetY / limit * pack.maxBodyOffset - bodyY) - 13f * bodyVelocityY - if(neutral) 0f else ay.coerceIn(-12f,12f)*.025f) * dt
        bodyX = (bodyX + bodyVelocityX * dt).coerceIn(-pack.maxBodyOffset,pack.maxBodyOffset)
        bodyY = (bodyY + bodyVelocityY * dt).coerceIn(-pack.maxBodyOffset,pack.maxBodyOffset)
        velocityX += (110f * (targetX - angleX) - 20f * velocityX - if(neutral) 0f else ax.coerceIn(-12f,12f)*.18f) * dt
        velocityY += (110f * (targetY - angleY) - 20f * velocityY - if(neutral) 0f else ay.coerceIn(-12f,12f)*.18f) * dt
        angleX = (angleX + velocityX * dt).coerceIn(-limit, limit)
        angleY = (angleY + velocityY * dt).coerceIn(-limit, limit)
        states.forEach { s ->
            val l = s.layer
            if (l.material == "rigid") {
                val parent = byId[l.parentId]
                val pin = l.pins.firstOrNull()
                val dx = if (parent != null && pin != null) parent.x[pin[1]] - parent.layer.vertices[pin[1]][0] else 0f
                val dy = if (parent != null && pin != null) parent.y[pin[1]] - parent.layer.vertices[pin[1]][1] else 0f
                for (i in s.x.indices) {
                    s.x[i] = l.vertices[i][0] + dx; s.y[i] = l.vertices[i][1] + dy
                    s.previousX[i] = s.x[i]; s.previousY[i] = s.y[i]
                }
                return@forEach
            }
            val parentState = byId[l.parentId]
            val rootPin = l.pins.firstOrNull()
            val parentX = if (parentState != null && rootPin != null) parentState.x[rootPin[1]] - parentState.layer.vertices[rootPin[1]][0] else 0f
            val parentY = if (parentState != null && rootPin != null) parentState.y[rootPin[1]] - parentState.layer.vertices[rootPin[1]][1] else 0f
            val damping = kotlin.math.exp(-l.damping * dt)
            for (i in s.x.indices) {
                val rest = l.vertices[i]; val mobility = rest[5]
                if (mobility == 0f) continue
                val x = s.x[i]; val y = s.y[i]
                // Forces use the part's permitted excursion, not canvas-sized
                // constants that made short cords effectively immobile.
                val fx = if (neutral) 0f else (l.stiffness * l.maxDisplacement * (angleX / limit * 1.2f + gx * .5f) - ax.coerceIn(-12f,12f) * l.maxDisplacement * 2f) * mobility
                val fy = if (neutral) 0f else (l.stiffness * l.maxDisplacement * (angleY / limit * .7f + gy * .5f) - ay.coerceIn(-12f,12f) * l.maxDisplacement * 2f) * mobility
                s.x[i] += (x - s.previousX[i]) * damping + (fx + l.stiffness * (rest[0] + parentX - x)) * dt * dt
                s.y[i] += (y - s.previousY[i]) * damping + (fy + l.stiffness * (rest[1] + parentY - y)) * dt * dt
                s.previousX[i] = x; s.previousY[i] = y
            }
            repeat(4) {
                l.pins.forEach { pin ->
                    val parent = byId.getValue(l.parentId!!); val i = pin[0]; val j = pin[1]
                    s.x[i] = l.vertices[i][0] + parent.x[j] - parent.layer.vertices[j][0]
                    s.y[i] = l.vertices[i][1] + parent.y[j] - parent.layer.vertices[j][1]
                    s.previousX[i] = s.x[i]; s.previousY[i] = s.y[i]
                }
                l.edges.forEach { e ->
                    val a = e[0].toInt(); val b = e[1].toInt(); val ma = l.vertices[a][5]; val mb = l.vertices[b][5]
                    if (ma + mb > 0f) {
                        val dx = s.x[b] - s.x[a]; val dy = s.y[b] - s.y[a]; val length = kotlin.math.sqrt(dx * dx + dy * dy)
                        if (length > 1e-7f) {
                            // A fully triangulated 2D sheet with exact distance
                            // constraints is rigid. Permit bounded material strain
                            // instead of projecting every edge back to rest each step.
                            val strain = if (l.material == "cloth") .08f else .05f
                            val excess = length - length.coerceIn(e[2]*(1f-strain),e[2]*(1f+strain))
                            val correction = excess / length * .8f / (ma + mb)
                            s.x[a] += dx * correction * ma; s.y[a] += dy * correction * ma
                            s.x[b] -= dx * correction * mb; s.y[b] -= dy * correction * mb
                        }
                    }
                }
                l.bends.forEach { e ->
                    val a = e[0].toInt(); val b = e[1].toInt(); val ma = l.vertices[a][5]; val mb = l.vertices[b][5]
                    val dx = s.x[b] - s.x[a]; val dy = s.y[b] - s.y[a]; val length = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (ma + mb > 0f && length > 1e-7f) {
                        val excess = length - length.coerceIn(e[2]*.88f,e[2]*1.12f)
                        val correction = excess / length * .15f / (ma + mb)
                        s.x[a] += dx * correction * ma; s.y[a] += dy * correction * ma
                        s.x[b] -= dx * correction * mb; s.y[b] -= dy * correction * mb
                    }
                }
                // Preserve triangle orientation; collapsed/flipped cloth exposes texture holes.
                for (t in l.triangles.indices step 3) {
                    val a=l.triangles[t]; val b=l.triangles[t+1]; val c=l.triangles[t+2]
                    val va=l.vertices[a]; val vb=l.vertices[b]; val vc=l.vertices[c]
                    val restArea=(vb[0]-va[0])*(vc[1]-va[1])-(vb[1]-va[1])*(vc[0]-va[0])
                    val area=(s.x[b]-s.x[a])*(s.y[c]-s.y[a])-(s.y[b]-s.y[a])*(s.x[c]-s.x[a])
                    if (area < restArea*.2f) {
                        val ax=s.y[b]-s.y[c]; val ay=s.x[c]-s.x[b]
                        val bx=s.y[c]-s.y[a]; val by=s.x[a]-s.x[c]
                        val cx=s.y[a]-s.y[b]; val cy=s.x[b]-s.x[a]
                        val denom=va[5]*(ax*ax+ay*ay)+vb[5]*(bx*bx+by*by)+vc[5]*(cx*cx+cy*cy)
                        if (denom>1e-12f) {
                            val k=(restArea*.2f-area)/denom
                            s.x[a]+=k*va[5]*ax; s.y[a]+=k*va[5]*ay
                            s.x[b]+=k*vb[5]*bx; s.y[b]+=k*vb[5]*by
                            s.x[c]+=k*vc[5]*cx; s.y[c]+=k*vc[5]*cy
                        }
                    }
                }
            }
            for (i in s.x.indices) {
                val rest = l.vertices[i]
                if (rest[5] == 0f) continue
                val dx = s.x[i] - rest[0] - parentX; val dy = s.y[i] - rest[1] - parentY
                val length = kotlin.math.sqrt(dx * dx + dy * dy)
                if (length > l.maxDisplacement) { s.x[i] = rest[0] + parentX + dx / length * l.maxDisplacement; s.y[i] = rest[1] + parentY + dy / length * l.maxDisplacement }
            }
        }
    }
    val breathingOffset: Float get() = if (neutral) 0f else (kotlin.math.sin(clock * 2 * Math.PI / 3.8) * .0008).toFloat()
}
