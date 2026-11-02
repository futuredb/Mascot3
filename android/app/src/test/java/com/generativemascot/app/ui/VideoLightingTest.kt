package com.generativemascot.app.ui

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import android.graphics.BitmapFactory
import androidx.media3.common.C
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.ClippingMediaPeriod
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleStream
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class VideoLightingTest {
    private val assets=File("src/main/assets").let { if(it.isDirectory)it else File("app/src/main/assets") }
    private val json=Json { ignoreUnknownKeys=true }
    private fun hash(file: File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun source()=File(assets,"bundled-heroes/716cdb43-703b-4d7e-8a79-27f03d92b99c/videos/dancing.mp4")
    private fun manifest(file: File=source())=json.decodeFromString<VideoLightingManifest>(File(assets,"video-lighting/${hash(file)}/manifest.json").readText())

    @Test fun everyBundledActionVideoHasItsOwnVerifiedMovingSurface() {
        val videos=File(assets,"bundled-heroes").walkTopDown().filter { it.extension=="mp4" && it.parentFile.name=="videos" }.toList()
        assertEquals(27,videos.size)
        videos.forEach { video ->
            val m=manifest(video);m.validate();assertEquals(video.length(),m.sourceBytes);assertEquals(hash(video),m.sourceSha256)
            m.pages.forEachIndexed { index,name ->
                val file=File(assets,"video-lighting/${m.sourceSha256}/$name");assertEquals(m.pageSha256[index],hash(file))
                val image=requireNotNull(BitmapFactory.decodeFile(file.absolutePath));assertEquals(m.columns*m.tileSize,image.width)
                val count=minOf(m.pageFrames,m.timestampsUs.size-index*m.pageFrames)
                assertEquals((count+m.columns-1)/m.columns*m.tileSize,image.height)
                for(y in 0 until image.height step 8)for(x in 0 until image.width step 8)assertTrue((image.getPixel(x,y) and 255)>=190)
                image.recycle()
            }
            for(frame in m.timestampsUs.indices){
                assertEquals(frame,m.frameAt(m.timestampsUs[frame]))
                val rect=m.tileRect(frame);assertTrue(rect.all { it>0f && it<1f });assertTrue(rect[0]+rect[2]<1f);assertTrue(rect[1]+rect[3]<1f)
            }
        }
    }

    @Test fun frameSelectionHandlesRoundingSeeksAndLastFrameWithoutWrappingToAnotherPose() {
        val m=manifest()
        for(frame in listOf(0,1,63,64,100,m.timestampsUs.lastIndex)) {
            val time=m.timestampsUs[frame];assertEquals(frame,m.frameAt(time+1))
            if(frame>0)assertEquals(frame,m.frameAt(time-1))
        }
        assertEquals(0,m.frameAt(0));assertNull(m.frameAt(-1));assertNull(m.frameAt(m.timestampsUs.last()+100_001))
    }

    @Test fun sourceClockFollowsDecoderOffsetsRepeatedClipsAndSeeking() {
        val clock=VideoFrameClock();val m=manifest()
        for(offset in listOf(1_000_000L,9_000_000L,17_000_000L))for(frame in listOf(0,12,63,100,1,0)) {
            val source=m.timestampsUs[frame];clock.record(offset+source,source)
            assertEquals(frame,m.frameAt(requireNotNull(clock.sourceTime(offset+source))))
        }
        assertNull(clock.sourceTime(555L))
    }

    @Test fun media3ClippingKeepsOriginalFrameTimeForTheSleepLoop() {
        val m=manifest();val source=m.timestampsUs[50];val clipStart=1_600_000L
        val sample=object:SampleStream {
            override fun isReady()=true
            override fun maybeThrowError()=Unit
            override fun skipData(positionUs: Long)=0
            override fun readData(holder: FormatHolder,buffer: DecoderInputBuffer,flags: Int): Int {
                buffer.timeUs=source
                return C.RESULT_BUFFER_READ
            }
        }
        // Use the actual Media3 clipping wrapper, not an assumed clip-relative clock.
        val child=Proxy.newProxyInstance(MediaPeriod::class.java.classLoader,arrayOf(MediaPeriod::class.java)) { _,method,args ->
            when(method.name) {
                "selectTracks" -> {
                    @Suppress("UNCHECKED_CAST")
                    val streams=args!![2] as Array<SampleStream?>
                    streams[0]=sample
                    args[4]
                }
                "getBufferedPositionUs" -> 4_000_000L
                else -> error("Unexpected media period call: ${method.name}")
            }
        } as MediaPeriod
        val clipped=ClippingMediaPeriod(child,false,clipStart,4_400_000L)
        val streams=arrayOfNulls<SampleStream>(1)
        clipped.selectTracks(arrayOf(null),booleanArrayOf(false),streams,booleanArrayOf(false),clipStart)
        val buffer=DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        assertEquals(C.RESULT_BUFFER_READ,streams[0]!!.readData(FormatHolder(),buffer,0))
        assertEquals(source,buffer.timeUs)
        val rendererOffset=1_000_000L-clipStart
        val effectTime=buffer.timeUs+rendererOffset
        val clock=VideoFrameClock();clock.record(effectTime,effectTime-rendererOffset)
        assertEquals(50,m.frameAt(requireNotNull(clock.sourceTime(effectTime))))
    }

    @Test fun clockStorageIsBoundedAndUnknownFramesCannotBorrowTheLastNormal() {
        val clock=VideoFrameClock();repeat(1000){clock.record(it.toLong(),it.toLong()*2)}
        assertNull(clock.sourceTime(0));assertEquals(1998L,clock.sourceTime(999));assertNull(clock.sourceTime(1001))
    }

    @Test fun assetLoaderAcceptsOriginalContentButNotAReplacementWithTheSameName() = runBlocking {
        val app=RuntimeEnvironment.getApplication();val loaded=loadVideoLighting(app,source().toURI().toString())
        assertNotNull(loaded);assertEquals(manifest().timestampsUs,loaded!!.manifest.timestampsUs)
        val folder=File(app.cacheDir,"lighting-test").apply { mkdirs() }
        val replacement=File(folder,"dancing.mp4").apply { writeBytes(byteArrayOf(1,2,3)) }
        assertNull(loadVideoLighting(app,replacement.toURI().toString()))
        assertNull(loadVideoLighting(app,"https://example.test/dancing.mp4"))
        replacement.delete()
        Unit
    }

    @Test fun malformedOrOversizedSidecarsAreRejected() {
        val m=manifest()
        assertThrows(IllegalArgumentException::class.java){m.copy(timestampsUs=listOf(0,1,1)).validate()}
        assertThrows(IllegalArgumentException::class.java){m.copy(pages=listOf("../../wrong.png")).validate()}
        assertThrows(IllegalArgumentException::class.java){m.copy(tileSize=2048).validate()}
        assertThrows(IllegalArgumentException::class.java){m.copy(timestampsUs=(0L..256L).toList()).validate()}
    }
}
