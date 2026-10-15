package com.generativemascot.app.physics

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import java.io.File
import kotlinx.serialization.json.Json

class PhysicsSolverTest {
    private fun pack(): PhysicsPack {
        val vertices = (0..8).flatMap { y -> (0..2).map { x -> listOf(.4f + x * .02f, .3f + y * .025f, 0f, x / 2f, y / 8f, if (y == 0) 0f else y / 8f) } }
        val triangles = mutableListOf<Int>()
        val edges = mutableListOf<List<Float>>()
        val bends = mutableListOf<List<Float>>()
        fun edge(a: Int, b: Int) { edges += listOf(a.toFloat(), b.toFloat(), hypot(vertices[a][0]-vertices[b][0], vertices[a][1]-vertices[b][1])) }
        for (y in 0..7) for (x in 0..1) {
            val a=y*3+x; val b=a+1; val c=a+4; val d=a+3
            triangles.addAll(listOf(a,b,c,a,c,d)); edge(a,b); edge(b,c); edge(c,d); edge(d,a); edge(a,c)
            if (y<7) bends += listOf(a.toFloat(), (a+6).toFloat(), .05f)
        }
        val base = PhysicsLayer("body",material="rigid",order=0,texture="color",depth="depth",damping=12f,stiffness=150f,maxDisplacement=.004f,
            vertices=vertices.map { it.toMutableList().apply { this[5]=0f } },triangles=triangles,edges=edges)
        val ribbon = PhysicsLayer("ribbon","body","cloth",1,"color","depth",7f,50f,.02f,vertices,triangles,edges,bends,listOf(listOf(0,0),listOf(1,1),listOf(2,2)))
        val plate = base.copy(id="plate",parentId="ribbon",order=2,pins=listOf(listOf(24,24)),maxDisplacement=.02f)
        return PhysicsPack("mascot-physics-2.5d",1,"0".repeat(64),"0".repeat(64),width=128,height=128,maxTiltDegrees=12f,
            layers=listOf(base,ribbon,plate),assets=mapOf("color" to PhysicsAsset("color.png","0".repeat(64),100),"depth" to PhysicsAsset("depth.png","0".repeat(64),100)),qa=PhysicsQa(true,0f))
    }
    private fun snapshot(solver: PhysicsSolver) = listOf(solver.angleX,solver.angleY,solver.bodyX,solver.bodyY) + solver.states.flatMap { it.x.toList() + it.y.toList() }

    @Test fun approvedPackShowsSecondaryMovementAtModerateTilt() {
        val root=File("src/main/assets/bundled-heroes/${PhysicsStore.PILOT_ID}/physics")
        val pack=Json { ignoreUnknownKeys=true }.decodeFromString<PhysicsPack>(File(root,"${File(root,"active").readText().trim()}/manifest.json").readText())
        val solver=PhysicsSolver(pack)
        repeat(360) { solver.advance(1.0/120,.15f,0f,gravityX=.15f) }
        solver.states.filter { it.layer.material!="rigid" }.forEach { s ->
            val pixels=s.x.indices.maxOf { i -> hypot(s.x[i]-s.layer.vertices[i][0],s.y[i]-s.layer.vertices[i][1]) }*pack.width
            println("${s.layer.id}: $pixels pixels at moderate tilt")
            assertTrue("${s.layer.id} moves only $pixels pixels",pixels>=2f)
        }
        repeat(1200) { frame ->
            val sign=if(frame%60<30) 1f else -1f
            solver.advance(1.0/120,sign*.2f,-sign*.2f,sign*12f,-sign*12f,sign,-sign)
            solver.states.forEach { s ->
                for(t in s.layer.triangles.indices step 3) {
                    val a=s.layer.triangles[t];val b=s.layer.triangles[t+1];val c=s.layer.triangles[t+2]
                    assertTrue("Fold in ${s.layer.id}",(s.x[b]-s.x[a])*(s.y[c]-s.y[a])-(s.y[b]-s.y[a])*(s.x[c]-s.x[a])>0f)
                }
                s.layer.pins.forEach { pin ->
                    if(s.layer.parentId=="body") {
                        assertEquals(s.layer.vertices[pin[0]][0],s.x[pin[0]],1e-6f)
                        assertEquals(s.layer.vertices[pin[0]][1],s.y[pin[0]],1e-6f)
                    }
                }
            }
        }
        solver.neutral=true
        repeat(600) { solver.advance(1.0/120,0f,0f) }
        assertTrue(solver.settled)
    }

    @Test fun inPlaneRollMovesTheBodyAndNeutralStopsIt() {
        val solver=PhysicsSolver(pack().copy(maxBodyOffset=.035f))
        // Upright phone rolled sideways: quaternion X/Y can remain zero, while
        // gravity projected onto the display changes noticeably.
        repeat(240) { solver.advance(1.0/120,0f,0f,gravityX=.5f) }
        assertTrue(solver.angleX>.15f)
        assertTrue(solver.bodyX>.03f)
        assertEquals(0f,solver.bodyY,1e-6f)
        solver.neutral=true
        repeat(600) { solver.advance(1.0/120,0f,0f,gravityX=.5f) }
        assertTrue(solver.settled)
        assertEquals(0f,solver.bodyX,.00001f)
    }

    @Test fun sameRecordedInputsAt30_60_120Fps() {
        val records = listOf(floatArrayOf(0f,0f,0f,0f,0f,0f),floatArrayOf(.18f,-.12f,0f,0f,.2f,-.1f),floatArrayOf(-.1f,.12f,9f,-8f,-.3f,.4f),floatArrayOf(0f,0f,0f,0f,0f,0f))
        fun run(fps: Int): List<Float> {
            val solver=PhysicsSolver(pack())
            records.forEach { r -> repeat(fps) { solver.advance(1.0/fps,r[0],r[1],r[2],r[3],r[4],r[5]) } }
            return snapshot(solver)
        }
        val reference=run(120)
        for (fps in listOf(30,60)) reference.zip(run(fps)).forEach { (a,b) -> assertEquals(a,b,1e-6f) }
    }

    @Test fun extremeShakeStaysFinitePinnedAndRigid() {
        val solver=PhysicsSolver(pack()); solver.pack.validate()
        repeat(2400) { i ->
            val sign=if (i%30<15) 1f else -1f
            solver.advance(1.0/120,sign*100f,-sign*100f,sign*1000f,sign*1000f,sign*100f,-sign*100f)
            assertTrue(abs(solver.angleX) <= Math.toRadians(12.0)+1e-6)
            solver.states.forEach { s -> s.x.indices.forEach { j -> assertTrue(s.x[j].isFinite() && s.y[j].isFinite()) } }
            val ribbon=solver.states[1]
            for (j in 0..2) { assertEquals(ribbon.layer.vertices[j][0],ribbon.x[j],0f); assertEquals(ribbon.layer.vertices[j][1],ribbon.y[j],0f) }
            ribbon.x.indices.forEach { j -> assertTrue(hypot(ribbon.x[j]-ribbon.layer.vertices[j][0],ribbon.y[j]-ribbon.layer.vertices[j][1]) <= .02001f) }
            val plate=solver.states[2]
            assertEquals(plate.layer.vertices[0][0]-plate.layer.vertices[26][0],plate.x[0]-plate.x[26],1e-6f)
            assertEquals(plate.layer.vertices[0][1]-plate.layer.vertices[26][1],plate.y[0]-plate.y[26],1e-6f)
        }
        solver.neutral=true
        repeat(600) { solver.advance(1.0/120,1f,1f,12f,12f) }
        assertTrue(solver.settled)
    }

    @Test fun pauseResetDiscardsElapsedTimeVelocityAndInvalidSensorData() {
        val solver=PhysicsSolver(pack())
        repeat(120) { solver.advance(1.0/120,.2f,.2f,10f,10f) }
        solver.advance(120.0,.2f,.2f)
        assertEquals(snapshot(PhysicsSolver(pack())),snapshot(solver))
        solver.advance(Double.NaN,Float.NaN,Float.NaN)
        solver.advance(1.0/120,Float.NaN,Float.POSITIVE_INFINITY,Float.NaN)
        assertTrue(snapshot(solver).all(Float::isFinite))
        assertTrue(solver.settled)
    }

    @Test fun corruptPackageCannotReachRenderer() {
        val pack=pack()
        assertThrows(IllegalArgumentException::class.java) { pack.copy(qa=PhysicsQa(false,0f)).validate() }
        assertThrows(IllegalArgumentException::class.java) { pack.copy(layers=pack.layers.map { if(it.id=="ribbon") it.copy(pins=emptyList()) else it }).validate() }
        assertThrows(IllegalArgumentException::class.java) { pack.copy(maxTiltDegrees=90f).validate() }
        assertThrows(IllegalArgumentException::class.java) { pack.copy(layers=pack.layers.map { if(it.id=="ribbon") it.copy(triangles=listOf(0,0,1)) else it }).validate() }
        assertThrows(IllegalArgumentException::class.java) { pack.copy(qa=PhysicsQa(true,-1f)).validate() }
    }
}
