package com.generativemascot.app.physics

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.generativemascot.app.data.AssetRefDto
import com.generativemascot.app.data.MascotApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.URI
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhysicsStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun png(color: Int): ByteArray = ByteArrayOutputStream().use { out ->
        Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply { eraseColor(color); compress(Bitmap.CompressFormat.PNG,100,out); recycle() }; out.toByteArray()
    }
    @Test fun interruptedOrRejectedDownloadNeverReplacesTheWorkingPackage() = runBlocking {
        val id="physics-test-${UUID.randomUUID()}"; val store=PhysicsStore(context)
        val base=png(Color.RED); val second=png(Color.BLUE)
        val hero=File(context.filesDir,"hero/$id").apply { mkdirs() }
        File(hero,"base.png").writeBytes(base)
        val prefix="/v1/physics/jobs/${"a".repeat(32)}/assets/"
        val files=mutableMapOf<String,ByteArray>()
        var failFile: String?=null
        val api=Proxy.newProxyInstance(MascotApi::class.java.classLoader,arrayOf(MascotApi::class.java)) { _,method,args ->
            require(method.name=="fetchBytes")
            val name=URI(args[0] as String).path
            if(name==failFile) object : ResponseBody() {
                override fun contentType(): okhttp3.MediaType? = null
                override fun contentLength() = 100L
                override fun source() = object : Source {
                    override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("interrupted")
                    override fun timeout() = Timeout.NONE
                    override fun close() = Unit
                }.buffer()
            } else files.getValue(name).toResponseBody()
        } as MascotApi
        fun publish(color:ByteArray, approved:Boolean=true):AssetRefDto {
            val texture=PhysicsAsset("color.png",PhysicsStore.hash(color),color.size.toLong(),prefix+"color.png")
            val depth=PhysicsAsset("depth.png",PhysicsStore.hash(base),base.size.toLong(),prefix+"depth.png")
            val vertices=listOf(listOf(.2f,.2f,0f,0f,0f,0f),listOf(.7f,.2f,0f,1f,0f,0f),listOf(.2f,.7f,0f,0f,1f,0f))
            val body=PhysicsLayer("body",material="rigid",order=0,texture="color",depth="depth",damping=12f,stiffness=120f,maxDisplacement=.004f,vertices=vertices,triangles=listOf(0,1,2),edges=listOf(listOf(0f,1f,.5f),listOf(0f,2f,.5f)))
            val cloth=body.copy(id="cloth",parentId="body",material="cloth",order=1,vertices=vertices.mapIndexed { i,v->v.toMutableList().apply { this[5]=if(i==0) 0f else 1f } },pins=listOf(listOf(0,0)))
            val pack=PhysicsPack("mascot-physics-2.5d",1,PhysicsStore.hash(base),PhysicsStore.hash(base),width=64,height=64,maxTiltDegrees=12f,layers=listOf(body,cloth),assets=mapOf("color" to texture,"depth" to depth),qa=PhysicsQa(approved,0f))
            val manifest=Json.encodeToString(pack).toByteArray()
            files[prefix+"manifest.json"]=manifest; files[prefix+"color.png"]=color; files[prefix+"depth.png"]=base
            return AssetRefDto(prefix+"manifest.json",PhysicsStore.hash(manifest))
        }
        try {
            val first=publish(base); store.install(api,id,first)
            assertNotNull(store.load(id)); assertEquals(first.sha256,store.revision(id))
            val next=publish(second); failFile=prefix+"depth.png"
            try { store.install(api,id,next); fail("download must fail") } catch (_: IOException) { }
            assertEquals(first.sha256,store.revision(id)); assertNotNull(store.load(id))
            failFile=null; store.install(api,id,next)
            assertEquals(next.sha256,store.revision(id))
            val rejected=publish(base,false)
            try { store.install(api,id,rejected); fail("QA must reject") } catch (_: IllegalArgumentException) { }
            assertEquals(next.sha256,store.revision(id)); assertArrayEquals(base,File(hero,"base.png").readBytes())
            File(hero,"physics/${next.sha256}/color.png").writeBytes(byteArrayOf(0))
            try { store.load(id); fail("tampering must be detected") } catch (_: IllegalArgumentException) { }
        } finally { hero.deleteRecursively() }
    }
}
