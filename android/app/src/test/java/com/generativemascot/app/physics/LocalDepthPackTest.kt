package com.generativemascot.app.physics

import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LocalDepthPackTest {
    private val ids = listOf("9f226b89-df35-4d3a-9b36-96c28ea1bb62", "a83e9d30-1270-40e6-902c-b44774e84c17")
    private fun directory(id: String): File {
        val root = File("src/main/assets/bundled-heroes/$id/physics")
        return File(root, File(root,"active").readText().trim())
    }
    private fun pack(id: String) = Json { ignoreUnknownKeys=true }.decodeFromString<PhysicsPack>(File(directory(id),"manifest.json").readText())

    @Test fun depthPacksPreserveTheOriginalArtworkAndHaveRealDepthVariation() {
        ids.forEach { id ->
            val pack = pack(id); pack.validate()
            val folder = directory(id)
            assertEquals("mascot-depth-2.5d", pack.format)
            assertEquals(folder.name, PhysicsStore.hash(File(folder,"manifest.json").readBytes()))
            val original = File("src/main/assets/bundled-heroes/$id/base.png").readBytes()
            assertArrayEquals(original,File(folder,"body_color.png").readBytes())
            assertEquals(PhysicsStore.hash(original),pack.sourceSha256)
            pack.assets.values.forEach { asset ->
                val file = File(folder,asset.file)
                assertEquals(asset.size,file.length())
                assertEquals(asset.sha256,PhysicsStore.hash(file.readBytes()))
                val header = ByteBuffer.wrap(file.readBytes())
                assertEquals(0x89504E47.toInt(), header.getInt(0))
                assertEquals(pack.width,header.getInt(16)); assertEquals(pack.height,header.getInt(20))
            }
            val depths = pack.layers.single().vertices.map { it[2] }
            assertTrue("Relief should not flatten into a card", depths.max() - depths.min() > .10f)
        }
    }

    @Test fun reliefTrianglesNeverFoldAcrossTheSupportedTwoAxisTiltRange() {
        ids.forEach { id ->
            val layer = pack(id).layers.single()
            for (dx in -12..12 step 3) for (dy in -12..12 step 3) {
                val ax = Math.toRadians(dx.toDouble()); val ay = Math.toRadians(dy.toDouble())
                val projected = layer.vertices.map { v ->
                    val x = (v[0]-.5)*cos(ax)+v[2]*sin(ax)
                    val z = -(v[0]-.5)*sin(ax)+v[2]*cos(ax)
                    x to ((v[1]-.5)*cos(ay)-z*sin(ay))
                }
                for (i in layer.triangles.indices step 3) {
                    val indices = layer.triangles.subList(i,i+3)
                    val (a,b,c) = indices.map { projected[it] }
                    val area = (b.first-a.first)*(c.second-a.second)-(b.second-a.second)*(c.first-a.first)
                    val (va,vb,vc) = indices.map { layer.vertices[it] }
                    val rest = (vb[0]-va[0])*(vc[1]-va[1])-(vb[1]-va[1])*(vc[0]-va[0])
                    assertTrue("Fold or excessive compression in $id at $dx/$dy",area/rest > .39)
                }
            }
        }
    }

    @Test fun depthFormatDoesNotAllowSoftLayersOrBypassTheOldPhysicsContract() {
        val pack = pack(ids.first())
        assertThrows(IllegalArgumentException::class.java) { pack.copy(format="mascot-physics-2.5d").validate() }
        assertThrows(IllegalArgumentException::class.java) { pack.copy(layers=pack.layers.map { it.copy(material="cloth") }).validate() }
        assertThrows(IllegalArgumentException::class.java) { pack.copy(qa=PhysicsQa(false,0f)).validate() }
    }

    @Test fun floorIsAnIndependentReferenceAndExportSceneTracesForCodexPreview() {
        val baseline = HeroSpace.floor(PhonePose())
        assertEquals(baseline,HeroSpace.floor(PhonePose(wobble=8f,bounce=1f)))
        assertNotEquals(baseline,HeroSpace.floor(PhonePose(x=1f)))
        val output = System.getenv("MASCOT3_PREVIEW_OUTPUT")?.let(::File) ?: return
        val softHeroes = mutableMapOf<String,String>()
        val heroes = (ids + PhysicsStore.PILOT_ID).associateWith { id ->
            val softScenarios = mutableMapOf<String,String>()
            listOf("left","right","forward","shake","neutral","level").associateWith { scenario ->
                val solver = PhysicsSolver(pack(id))
                val deformed = mutableListOf<String>()
                val movement = PhoneInteractionMotion()
                val orientation = RelativeOrientation()
                fun quaternion(roll: Float, heading: Float): FloatArray {
                    val a=Math.PI.toFloat()/4;val b=roll/2;val c=heading/2
                    val w=cos(a)*cos(b);val x=sin(a)*cos(b);val y=-sin(a)*sin(b);val z=cos(a)*sin(b)
                    return floatArrayOf(cos(c)*w-sin(c)*z,cos(c)*x-sin(c)*y,cos(c)*y+sin(c)*x,cos(c)*z+sin(c)*w)
                }
                repeat(40) { orientation.update(quaternion(-.12f,0f),it*20_000_000L) }
                (0..240).map { frame ->
                    val upright = orientation.update(quaternion(if(frame<60) -.12f else 0f, frame*.01f),1_000_000_000L+frame*1_000_000_000L/60)
                    val sample = if(scenario=="level") PhoneSample(tiltX=upright.first,tiltY=upright.second) else PhoneSample(
                        tiltX = if (frame < 120) when(scenario) { "left" -> -.06f; "right" -> .06f; else -> 0f } else 0f,
                        tiltY = if (frame < 120 && scenario == "forward") .06f else 0f,
                        accelerationX = if(scenario=="shake") when(frame) { 6 -> 6f; 18 -> -6f; else -> 0f } else 0f,
                        accelerationTimestamp = 1_000_000_000L + frame * 1_000_000_000L/60,
                    )
                    movement.observe(sample)
                    val pose = movement.advance(1f/60,sample)
                    solver.advance(1.0/60,sample.tiltX,sample.tiltY,sample.accelerationX)
                    deformed += solver.states.filter { it.layer.material!="rigid" }.joinToString(",","{","}") { state ->
                        "\"${state.layer.id}\":"+state.x.indices.flatMap { listOf(state.x[it],state.y[it]) }.joinToString(",","[","]")
                    }
                    val visual = pose.artwork(true)
                    val values = listOf(solver.angleX,solver.angleY,solver.bodyX,solver.bodyY,solver.leanAngle,solver.supportY,solver.breathingOffset,
                        pose.x,pose.y,pose.wobble,pose.bounce,visual.translationX,visual.translationY,visual.rotationX,visual.rotationY,visual.rotationZ,visual.scaleX,visual.scaleY)
                    assertTrue(values.all { it.isFinite() })
                    values.joinToString(",","[","]")
                }.joinToString(",","[","]").also { softScenarios[scenario]=deformed.joinToString(",","[","]") }
            }.entries.joinToString(",","{","}") { "\"${it.key}\":${it.value}" }.also {
                softHeroes[id]=softScenarios.entries.joinToString(",","{","}") { "\"${it.key}\":${it.value}" }
            }
        }
        File(output,"soft-traces.json").writeText(softHeroes.entries.joinToString(",","{","}") { "\"${it.key}\":${it.value}" })
        File(output,"scene-traces.json").writeText(heroes.entries.joinToString(",","{","}") { "\"${it.key}\":${it.value}" })
    }
}
