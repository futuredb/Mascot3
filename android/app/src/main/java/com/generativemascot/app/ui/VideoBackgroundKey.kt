package com.generativemascot.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import kotlin.math.sqrt
import kotlin.math.roundToInt

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
internal class VideoBackgroundKey(private val keyColor: Int): GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if(useHdr)throw VideoFrameProcessingException("The mascot chroma key requires SDR video")
        return KeyProgram(keyColor)
    }
    private class KeyProgram(key: Int): BaseGlShaderProgram(false,1) {
        private val program=GlProgram(VERTEX,FRAGMENT).apply {
            setBufferAttribute("position",floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f),2)
            setFloatsUniform("keyColor",floatArrayOf(Color.red(key)/255f,Color.green(key)/255f,Color.blue(key)/255f))
            setFloatUniform("greenKey",if(Color.green(key)>=90 && Color.green(key)>=Color.red(key)+45 && Color.green(key)>=Color.blue(key)+35)1f else 0f)
        }
        override fun configure(inputWidth: Int,inputHeight: Int)=Size(inputWidth,inputHeight)
        override fun drawFrame(inputTexId: Int,presentationTimeUs: Long) {
            try {
                program.use();program.setSamplerTexIdUniform("image",inputTexId,0)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
            }catch(error:Exception){throw VideoFrameProcessingException(error,presentationTimeUs)}
        }
        override fun release() { try { program.delete() } finally { super.release() } }
    }
    companion object {
        private const val VERTEX="""
            attribute vec2 position; varying vec2 uv;
            void main(){gl_Position=vec4(position,0.0,1.0);uv=(position+1.0)*0.5;}
        """
        private const val FRAGMENT="""
            precision highp float; uniform sampler2D image; uniform vec3 keyColor;
            uniform float greenKey; varying vec2 uv;
            void main(){
                vec4 source=texture2D(image,uv);
                vec3 rgb=source.rgb;
                float generic=smoothstep(0.10,0.42,distance(rgb,keyColor));
                float green=1.0-smoothstep(0.07,0.22,rgb.g-max(rgb.r,rgb.b));
                float matte=mix(generic,green,greenKey);float alpha=source.a*matte;
                float edge=greenKey*(1.0-smoothstep(0.70,0.98,matte));
                rgb.g=mix(rgb.g,min(rgb.g,max(rgb.r,rgb.b)+0.045),edge);
                gl_FragColor=vec4(rgb*alpha,alpha);
            }
        """
    }
}
