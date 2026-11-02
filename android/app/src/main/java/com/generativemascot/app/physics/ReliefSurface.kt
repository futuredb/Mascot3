package com.generativemascot.app.physics

import kotlin.math.sqrt

/** Display relief derived once from the saved depth map. It never changes the
 * sensor response, body translation, lean, timing, or original RGBA artwork. */
class ReliefSurface(pack: PhysicsPack) {
    val depths: Map<String, FloatArray>
    val normals: Map<String, FloatArray>
    init {
        val result = mutableMapOf<String, FloatArray>()
        val body = pack.layers.first { it.id == "body" }
        val center = body.vertices.map { it[2] }.average().toFloat()
        val gain = if (pack.format == "mascot-depth-2.5d") 2.4f else 5f
        val bodyDepth = FloatArray(body.vertices.size) { center + (body.vertices[it][2]-center)*gain }
        // Project steep triangle gradients onto a bounded slope. This preserves
        // broad volume while smoothing local depth discontinuities at features.
        // A slope <= 1.4 leaves margin against folding at the supported ±12°.
        repeat(100) {
            for(t in body.triangles.indices step 3) {
                val a=body.triangles[t];val b=body.triangles[t+1];val c=body.triangles[t+2]
                val va=body.vertices[a];val vb=body.vertices[b];val vc=body.vertices[c]
                val bx=vb[0]-va[0];val by=vb[1]-va[1];val cx=vc[0]-va[0];val cy=vc[1]-va[1]
                val area=bx*cy-by*cx
                val dzb=bodyDepth[b]-bodyDepth[a];val dzc=bodyDepth[c]-bodyDepth[a]
                val gx=(dzb*cy-dzc*by)/area;val gy=(bx*dzc-cx*dzb)/area
                val slope=sqrt(gx*gx+gy*gy)
                if(slope>1.4f) {
                    val mean=(bodyDepth[a]+bodyDepth[b]+bodyDepth[c])/3f
                    val scale=1.4f/slope
                    bodyDepth[a]=mean+(bodyDepth[a]-mean)*scale
                    bodyDepth[b]=mean+(bodyDepth[b]-mean)*scale
                    bodyDepth[c]=mean+(bodyDepth[c]-mean)*scale
                }
            }
        }
        // A final uniform contraction makes the slope bound unconditional,
        // including unusual downloaded meshes that need more relaxation passes.
        var maximumSlope=0f
        for(t in body.triangles.indices step 3) {
            val a=body.triangles[t];val b=body.triangles[t+1];val c=body.triangles[t+2]
            val va=body.vertices[a];val vb=body.vertices[b];val vc=body.vertices[c]
            val bx=vb[0]-va[0];val by=vb[1]-va[1];val cx=vc[0]-va[0];val cy=vc[1]-va[1]
            val area=bx*cy-by*cx
            val gx=((bodyDepth[b]-bodyDepth[a])*cy-(bodyDepth[c]-bodyDepth[a])*by)/area
            val gy=(bx*(bodyDepth[c]-bodyDepth[a])-cx*(bodyDepth[b]-bodyDepth[a]))/area
            maximumSlope=maxOf(maximumSlope,sqrt(gx*gx+gy*gy))
        }
        val span=bodyDepth.max()-bodyDepth.min()
        val correction=minOf(1f,1.4f/maximumSlope.coerceAtLeast(.00001f),.4f/span.coerceAtLeast(.00001f))
        for(i in bodyDepth.indices) bodyDepth[i]=center+(bodyDepth[i]-center)*correction
        // Each triangle projection preserves its mean: amplification adds no
        // overall sideways bias to the character's existing depth center.
        result[body.id]=bodyDepth
        pack.layers.filter { it.id != body.id }.forEach { layer ->
            val parent=pack.layers.first { it.id == layer.parentId }
            val parentDepth=result.getValue(parent.id)
            result[layer.id]=FloatArray(layer.vertices.size) { index ->
                val v=layer.vertices[index]
                val pinned=layer.pins.firstOrNull { it[0]==index }
                if(pinned!=null) v[2]+parentDepth[pinned[1]]-parent.vertices[pinned[1]][2]
                else {
                    var weight=0f;var shift=0f
                    layer.pins.forEach { pin ->
                        val anchor=layer.vertices[pin[0]]
                        val dx=v[0]-anchor[0];val dy=v[1]-anchor[1]
                        val w=1f/(dx*dx+dy*dy+.000001f)
                        weight+=w;shift+=w*(parentDepth[pin[1]]-parent.vertices[pin[1]][2])
                    }
                    v[2]+if(weight>0f) shift/weight else 0f
                }
            }
        }
        depths=result
        normals=pack.layers.associate { layer -> layer.id to normals(layer, result.getValue(layer.id)) }
    }

    companion object {
        /** Area-weighted vertex normals, calculated once from the actual relief. */
        fun normals(layer: PhysicsLayer, depth: FloatArray): FloatArray {
            val result=FloatArray(layer.vertices.size*3)
            for(t in layer.triangles.indices step 3) {
                val a=layer.triangles[t];val b=layer.triangles[t+1];val c=layer.triangles[t+2]
                val va=layer.vertices[a];val vb=layer.vertices[b];val vc=layer.vertices[c]
                val bx=vb[0]-va[0];val by=vb[1]-va[1];val bz=depth[b]-depth[a]
                val cx=vc[0]-va[0];val cy=vc[1]-va[1];val cz=depth[c]-depth[a]
                val nx=by*cz-bz*cy;val ny=bz*cx-bx*cz;val nz=bx*cy-by*cx
                for(i in intArrayOf(a,b,c)) {
                    result[i*3]+=nx;result[i*3+1]+=ny;result[i*3+2]+=nz
                }
            }
            for(i in layer.vertices.indices) {
                val j=i*3
                val length=sqrt(result[j]*result[j]+result[j+1]*result[j+1]+result[j+2]*result[j+2])
                if(length>1e-12f) { result[j]/=length;result[j+1]/=length;result[j+2]/=length }
                else result[j+2]=1f
            }
            return result
        }
    }
}
