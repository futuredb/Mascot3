package com.generativemascot.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import kotlin.math.sqrt
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
internal fun usesUntaggedLegacyMatrix(format: Format): Boolean =
    format.sampleMimeType?.startsWith("video/")==true &&
        (format.colorInfo==null || format.colorInfo!!.colorSpace==Format.NO_VALUE) &&
        !ColorInfo.isTransferHdr(format.colorInfo)

/** The same soft matte for CPU thumbnails and the pre-Android-13 GPU path. */
internal fun keyedPixel(color: Int, keyColor: Int): Int {
    fun smooth(a: Float, b: Float, x: Float): Float {
        val t=((x-a)/(b-a)).coerceIn(0f,1f);return t*t*(3f-2f*t)
    }
    val r=Color.red(color)/255f;val g=Color.green(color)/255f;val b=Color.blue(color)/255f
    val greenKey=Color.green(keyColor)>=90 && Color.green(keyColor)>=Color.red(keyColor)+45 && Color.green(keyColor)>=Color.blue(keyColor)+35
    val matte=if(greenKey) 1f-smooth(.07f,.22f,g-maxOf(r,b)) else {
        val dr=r-Color.red(keyColor)/255f;val dg=g-Color.green(keyColor)/255f;val db=b-Color.blue(keyColor)/255f
        smooth(.1f,.42f,sqrt(dr*dr+dg*dg+db*db))
    }
    val alpha=(Color.alpha(color)*matte).roundToInt()
    if(alpha==0)return Color.TRANSPARENT
    val edge=if(greenKey) 1f-smooth(.70f,.98f,matte) else 0f
    val cleanG=g+(minOf(g,maxOf(r,b)+.045f)-g)*edge
    return Color.argb(alpha,Color.red(color),(cleanG*255f).roundToInt().coerceIn(0,255),Color.blue(color))
}

internal fun keyBitmap(source: Bitmap, keyColor: Int): Bitmap {
    val pixels=IntArray(source.width*source.height)
    source.getPixels(pixels,0,source.width,0,0,source.width,source.height)
    for(i in pixels.indices)pixels[i]=keyedPixel(pixels[i],keyColor)
    return Bitmap.createBitmap(pixels,source.width,source.height,Bitmap.Config.ARGB_8888)
}

/** Media3 1.4.1's default SDR working space retains the SDR transfer function.
 * Output premultiplied RGBA for Android's transparent TextureView compositor. */
@OptIn(UnstableApi::class)
internal class VideoBackgroundKey(private val keyColor: Int, private val lighting: VideoLightingBinding? = null, private val clock: VideoFrameClock? = null, private val tuning: GreenMatteTuning = GreenMatteTuning()): GlEffect {
    @Volatile var correctUntaggedBt601 = false
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if(useHdr)throw VideoFrameProcessingException("The mascot chroma key requires SDR video")
        return KeyProgram(keyColor, lighting, clock, tuning) { correctUntaggedBt601 }
    }
    private class KeyProgram(key: Int, private val lighting: VideoLightingBinding?, private val clock: VideoFrameClock?, tuning: GreenMatteTuning, private val correctColor: () -> Boolean): BaseGlShaderProgram(false,1) {
        private var lightingFailed = false
        // Upload once before playback. Swapping atlas pages during a decoded
        // frame used to copy up to 4 MiB on the rendering thread.
        // At most four 1024-square pages per clip, plus a flat fallback texture.
        private val normalTextures=IntArray(1+(lighting?.pack?.pages?.size ?: 0)).also { textures ->
            GLES20.glGenTextures(textures.size,textures,0)
            textures.forEachIndexed { index,texture ->
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
                if(index==0) {
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,1,1,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,
                        ByteBuffer.allocateDirect(4).put(byteArrayOf(128.toByte(),128.toByte(),255.toByte(),255.toByte())).apply { position(0) })
                } else if(!lightingFailed) {
                    try { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,requireNotNull(lighting).pack.pages[index-1],0) }
                    catch(_: Exception) { lightingFailed=true }
                    lightingFailed=GLES20.glGetError()!=GLES20.GL_NO_ERROR || lightingFailed
                }
            }
        }
        private val program=GlProgram(VERTEX,FRAGMENT).apply {
            setBufferAttribute("position",floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f),2)
            setFloatsUniform("keyColor",floatArrayOf(Color.red(key)/255f,Color.green(key)/255f,Color.blue(key)/255f))
            setFloatUniform("greenKey",if(Color.green(key)>=90 && Color.green(key)>=Color.red(key)+45 && Color.green(key)>=Color.blue(key)+35)1f else 0f)
            setFloatUniform("greenLow", tuning.low)
            setFloatUniform("greenHigh", tuning.high)
            setFloatUniform("greenAdaptive", if (tuning.adaptive) 1f else 0f)
        }
        override fun configure(inputWidth: Int,inputHeight: Int)=Size(inputWidth,inputHeight)
        override fun drawFrame(inputTexId: Int,presentationTimeUs: Long) {
            try {
                program.use();program.setSamplerTexIdUniform("image",inputTexId,0)
                program.setFloatUniform("correctBt601",if(correctColor())1f else 0f)
                val pack=lighting?.pack
                val sourceTime=clock?.sourceTime(presentationTimeUs)
                val frame=sourceTime?.let { pack?.manifest?.frameAt(it) }
                val enabled=pack!=null && frame!=null && !lightingFailed
                var normalTexture=normalTextures[0]
                if(enabled && pack!=null && frame!=null) {
                    val page=frame/pack.manifest.pageFrames
                    normalTexture=normalTextures[page+1]
                    program.setFloatsUniform("normalRect",pack.manifest.tileRect(frame))
                } else program.setFloatsUniform("normalRect",floatArrayOf(0f,0f,1f,1f))
                val tilt=lighting?.input?.tilt
                program.setFloatsUniform("lightTilt",floatArrayOf(tilt?.x ?: 0f,tilt?.y ?: 0f))
                program.setFloatUniform("normalActive",if(enabled)1f else 0f)
                program.setSamplerTexIdUniform("normalMap",normalTexture,1)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
            }catch(error:Exception){throw VideoFrameProcessingException(error,presentationTimeUs)}
        }
        override fun release() { try { program.delete();GLES20.glDeleteTextures(normalTextures.size,normalTextures,0) } finally { super.release() } }
    }
    companion object {
        private const val VERTEX="""
            attribute vec2 position; varying vec2 uv;
            void main(){gl_Position=vec4(position,0.0,1.0);uv=(position+1.0)*0.5;}
        """
        private const val FRAGMENT="""
            precision highp float; uniform sampler2D image; uniform vec3 keyColor;
            uniform float greenKey; uniform float correctBt601; uniform float greenLow; uniform float greenHigh; uniform float greenAdaptive; varying vec2 uv;
            uniform sampler2D normalMap; uniform vec4 normalRect;
            uniform vec2 lightTilt; uniform float normalActive;
            float surfaceLighting(vec3 rgb) {
                if(normalActive<0.5 || all(equal(lightTilt,vec2(0.0))))return 1.0;
                // Video textures use bottom-left GL coordinates; PNG atlas rows
                // use top-left image coordinates, as does the normal's Y axis.
                vec2 normalUv=normalRect.xy+vec2(uv.x,1.0-uv.y)*normalRect.zw;
                vec3 encoded=texture2D(normalMap,normalUv).rgb;
                vec3 normal=normalize(((encoded*255.0-128.0)/127.0)*vec3(0.9,0.9,1.0));
                vec3 baseLight=vec3(-0.25,-0.35,1.2);
                vec3 originalLight=normalize(baseLight);
                vec2 travel=vec2(-lightTilt.x,lightTilt.y)*4.2;
                travel*=min(1.0,0.7/max(length(travel),0.0001));
                vec3 light=normalize(baseLight+vec3(travel,0.0));
                // Broad diffuse fill, with a smooth shoulder rather than hard
                // bright/dark clipping. Keep the artwork's existing highlights.
                float delta=1.1*(dot(normal,light-originalLight)-(light.z-originalLight.z));
                delta=delta/sqrt(1.0+(delta/0.22)*(delta/0.22));
                float highlight=smoothstep(0.55,1.0,max(max(rgb.r,rgb.g),rgb.b));
                delta*=1.0-highlight*smoothstep(0.0,0.025,delta);
                return 1.0+delta;
            }
            vec3 restore601(vec3 rgb) {
                float y=dot(rgb,vec3(0.2126,0.7152,0.0722));
                float u=(rgb.b-y)/1.8556;float v=(rgb.r-y)/1.5748;
                return clamp(vec3(y+1.402*v,y-0.344136*u-0.714136*v,y+1.772*u),0.0,1.0);
            }
            void main(){
                vec4 source=texture2D(image,uv);
                vec3 rgb=mix(source.rgb,restore601(source.rgb),correctBt601);
                vec3 key=mix(keyColor,restore601(keyColor),correctBt601);
                float generic=smoothstep(0.10,0.42,distance(rgb,key));
                float green=1.0-smoothstep(0.07,0.22,rgb.g-max(rgb.r,rgb.b));
                float greenHue=1.0-smoothstep(0.65,0.72,rgb.b/max(rgb.g,0.001));
                float adaptiveGreen=1.0-smoothstep(greenLow,greenHigh,rgb.g-max(rgb.r,rgb.b));
                green=mix(green,adaptiveGreen,greenAdaptive*greenHue);
                float matte=mix(generic,green,greenKey);float alpha=source.a*matte;
                float edge=greenKey*(1.0-smoothstep(0.70,0.98,matte));
                rgb.g=mix(rgb.g,min(rgb.g,max(rgb.r,rgb.b)+0.045),edge);
                gl_FragColor=vec4(clamp(rgb*surfaceLighting(rgb),0.0,1.0)*alpha,alpha);
            }
        """
    }
}
