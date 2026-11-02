package com.generativemascot.app.physics

import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReliefSurfaceTest {
    private val ids=listOf("9f226b89-df35-4d3a-9b36-96c28ea1bb62","a83e9d30-1270-40e6-902c-b44774e84c17",PhysicsStore.PILOT_ID)
    private fun pack(id:String):PhysicsPack {
        val root=File("src/main/assets/bundled-heroes/$id/physics")
        return Json { ignoreUnknownKeys=true }.decodeFromString(File(root,"${File(root,"active").readText().trim()}/manifest.json").readText())
    }
    private fun projected(x:Float,y:Float,z:Float,ax:Double,ay:Double):Pair<Double,Double> {
        val px=(x-.5)*cos(ax)+z*sin(ax)
        val pz=-(x-.5)*sin(ax)+z*cos(ax)
        return px to ((y-.5)*cos(ay)-pz*sin(ay))
    }
    private fun area(a:Pair<Double,Double>,b:Pair<Double,Double>,c:Pair<Double,Double>)=
        (b.first-a.first)*(c.second-a.second)-(b.second-a.second)*(c.first-a.first)

    @Test fun surfaceNormalsDescribeTheReliefAndStayFinite() {
        val exported=ids.associateWith { id ->
            val pack=pack(id);val relief=ReliefSurface(pack)
            pack.layers.forEach { layer ->
                val normal=relief.normals.getValue(layer.id)
                for(i in layer.vertices.indices) {
                    val n=normal.slice(i*3..i*3+2)
                    assertTrue(n.all(Float::isFinite));assertTrue(n[2]>0f)
                    assertEquals(1f,n.sumOf { (it*it).toDouble() }.toFloat(),.00001f)
                }
            }
            assertTrue(relief.normals.getValue("body").filterIndexed { i,_ -> i%3==2 }.any { it<.9f })
            relief.normals.entries.joinToString(",","{","}") { "\"${it.key}\":"+it.value.joinToString(",","[","]") }
        }
        System.getenv("MASCOT3_PREVIEW_OUTPUT")?.let { File(it,"relief-normals.json").writeText(exported.entries.joinToString(",","{","}") { "\"${it.key}\":${it.value}" }) }
    }

    @Test fun greaterDepthKeepsItsCenterNeutralPoseAndOriginalAssets() {
        val output=System.getenv("MASCOT3_PREVIEW_OUTPUT")?.let(::File)
        val exported=ids.associateWith { id ->
            val pack=pack(id);val surface=ReliefSurface(pack)
            val body=pack.layers.first { it.id=="body" };val old=body.vertices.map { it[2] };val depth=surface.depths.getValue("body")
            val ratio=(depth.max()-depth.min())/(old.max()-old.min())
            assertTrue("Clearly larger relief for $id: $ratio",ratio>if(id==PhysicsStore.PILOT_ID) 3f else 1.65f)
            assertEquals(old.average(),depth.average(),.00001)
            assertTrue(depth.max()-depth.min()<=.40001f)
            body.vertices.forEachIndexed { i,v ->
                assertEquals(projected(v[0],v[1],v[2],0.0,0.0),projected(v[0],v[1],depth[i],0.0,0.0))
            }
            pack.layers.filter { it.id!="body" }.forEach { layer ->
                val parent=pack.layers.first { it.id==layer.parentId }
                layer.pins.forEach { pin ->
                    assertEquals(layer.vertices[pin[0]][2]-parent.vertices[pin[1]][2],
                        surface.depths.getValue(layer.id)[pin[0]]-surface.depths.getValue(parent.id)[pin[1]],.000001f)
                }
            }
            println("$id relief range ratio: $ratio")
            surface.depths.entries.joinToString(",","{","}") { "\"${it.key}\":"+it.value.joinToString(",","[","]") }
        }
        output?.let { File(it,"relief-surface.json").writeText(exported.entries.joinToString(",","{","}") { "\"${it.key}\":${it.value}" }) }
    }

    @Test fun enhancedReliefDoesNotFoldAtAnySupportedTwoAxisTilt() {
        ids.forEach { id ->
            val pack=pack(id);val surface=ReliefSurface(pack)
            for(dx in -12..12 step 3) for(dy in -12..12 step 3) pack.layers.forEach { layer ->
                val depth=surface.depths.getValue(layer.id)
                val p=layer.vertices.mapIndexed { i,v -> projected(v[0],v[1],depth[i],Math.toRadians(dx.toDouble()),Math.toRadians(dy.toDouble())) }
                for(t in layer.triangles.indices step 3) {
                    val a=layer.triangles[t];val b=layer.triangles[t+1];val c=layer.triangles[t+2]
                    val va=layer.vertices[a];val vb=layer.vertices[b];val vc=layer.vertices[c]
                    val rest=(vb[0]-va[0])*(vc[1]-va[1])-(vb[1]-va[1])*(vc[0]-va[0])
                    assertTrue("$id/${layer.id} at $dx/$dy",area(p[a],p[b],p[c])/rest>.25)
                }
            }
        }
    }

    @Test fun movingCordsAndClothKeepTheirSurfaceDuringStrongMotion() {
        val pack=pack(PhysicsStore.PILOT_ID);val surface=ReliefSurface(pack);val solver=PhysicsSolver(pack)
        repeat(720) { frame ->
            val sign=if(frame%60<30) 1f else -1f
            solver.advance(1.0/120,sign*.2f,-sign*.2f,sign*12f,-sign*12f)
            solver.states.forEach { state ->
                val depth=surface.depths.getValue(state.layer.id)
                val p=state.x.indices.map { i->projected(state.x[i],state.y[i],depth[i],solver.angleX.toDouble(),solver.angleY.toDouble()) }
                for(t in state.layer.triangles.indices step 3) {
                    val tri=state.layer.triangles
                    assertTrue("Projected fold in ${state.layer.id} at $frame",area(p[tri[t]],p[tri[t+1]],p[tri[t+2]])>0)
                }
            }
        }
    }
}
