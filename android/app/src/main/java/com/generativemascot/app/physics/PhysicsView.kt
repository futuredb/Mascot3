package com.generativemascot.app.physics

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** GL objects are owned exclusively by the render thread, including teardown. */
class PhysicsView(context: Context, private val installed: InstalledPhysics) : TextureView(context), TextureView.SurfaceTextureListener {
    private val motion = PhoneMotion(context)
    private class SurfaceSession(val texture: SurfaceTexture) {
        val cancelled = AtomicBoolean(false)
        @Volatile var dispose: (() -> Unit)? = null
    }
    @Volatile private var session: SurfaceSession? = null
    @Volatile var active = false
    @Volatile var neutral = false
    @Volatile var settleToken = 0
    @Volatile private var viewportWidth = 1
    @Volatile private var viewportHeight = 1
    var onReady: () -> Unit = {}
    var onSettled: () -> Unit = {}
    var onFailure: (String) -> Unit = {}
    val sensorsAvailable get() = motion.available
    val calibrated get() = motion.calibrated
    fun recalibrate() { motion.recalibrate() }
    init { isOpaque = false; surfaceTextureListener = this }
    fun resume() {
        active = motion.start()
        if (!active) post { onFailure("Не удалось включить датчики движения") }
    }
    fun pause() { active = false; motion.stop() }
    override fun onDetachedFromWindow() { pause(); super.onDetachedFromWindow() }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { viewportWidth = width; viewportHeight = height }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        viewportWidth = width; viewportHeight = height
        val owner = SurfaceSession(texture)
        session = owner
        val thread = HandlerThread("MascotPhysicsGL").apply { start() }
        val handler = Handler(thread.looper)
        handler.post {
            var display = EGL14.EGL_NO_DISPLAY
            var eglContext = EGL14.EGL_NO_CONTEXT
            var eglSurface = EGL14.EGL_NO_SURFACE
            val surface = Surface(texture)
            val resources = mutableListOf<GlLayer>()
            var program = 0
            var stopped = false
            val choreographer = Choreographer.getInstance()
            var callback: Choreographer.FrameCallback? = null
            fun releaseGraphics(contextLost: Boolean = false) {
                if (!contextLost) {
                    resources.forEach(GlLayer::dispose)
                    if (program != 0) GLES30.glDeleteProgram(program)
                }
                resources.clear(); program = 0
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
                    EGL14.eglTerminate(display)
                }
                display = EGL14.EGL_NO_DISPLAY; eglContext = EGL14.EGL_NO_CONTEXT; eglSurface = EGL14.EGL_NO_SURFACE
            }
            fun dispose() {
                if (stopped) return
                stopped = true
                callback?.let(choreographer::removeFrameCallback)
                releaseGraphics()
                surface.release(); texture.release(); thread.quitSafely()
            }
            owner.dispose = { handler.post { dispose() } }
            if (owner.cancelled.get()) { dispose(); return@post }
            try {
                val solver = PhysicsSolver(installed.pack)
                var angle = -1; var fit = -1; var breath = -1; var bodyOffset = -1; var lean = -1
                fun initializeGraphics() {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
                val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
                val attributes = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE)
                check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
                eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
                check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext))
                program = makeProgram(); GLES30.glUseProgram(program)
                GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                GLES30.glDisable(GLES30.GL_DEPTH_TEST)
                solver.states.sortedBy { it.layer.order }.forEach { state -> resources += GlLayer(state, File(installed.directory, installed.pack.assets.getValue(state.layer.texture).file)) }
                angle = GLES30.glGetUniformLocation(program, "angle")
                fit = GLES30.glGetUniformLocation(program, "fit")
                val aspect = GLES30.glGetUniformLocation(program, "aspect")
                breath = GLES30.glGetUniformLocation(program, "breath")
                bodyOffset = GLES30.glGetUniformLocation(program, "bodyOffset")
                lean = GLES30.glGetUniformLocation(program, "lean")
                GLES30.glUniform1f(aspect, installed.pack.height.toFloat() / installed.pack.width)
                }
                initializeGraphics()
                var previous = 0L; var wasActive = false; var ready = false; var settledSent = false; var lastSettleToken = -1
                var frameCount = 0; var computeNanos = 0L; var contextRecoveries = 0
                val metrics = FrameMetrics(context, motion)
                callback = Choreographer.FrameCallback { time ->
                    if (owner.cancelled.get()) dispose()
                    if (!stopped) try {
                        if (!active) { previous = 0; wasActive = false; metrics.previous = 0 }
                        else {
                            val started = System.nanoTime()
                            if (!wasActive) { solver.reset(); previous = time; wasActive = true; settledSent = false }
                            if (lastSettleToken != settleToken) { lastSettleToken = settleToken; settledSent = false }
                            val sample = motion.sample; solver.neutral = neutral
                            solver.advance((time - previous).coerceAtLeast(0) / 1e9, sample.tiltX, sample.tiltY, sample.accelerationX, sample.accelerationY, sample.gravityX, sample.gravityY); previous = time
                            val w = viewportWidth.coerceAtLeast(1); val h = viewportHeight.coerceAtLeast(1)
                            val pixels = minOf(w.toFloat() / installed.pack.width, h.toFloat() / installed.pack.height) * installed.pack.width
                            GLES30.glViewport(0, 0, w, h); GLES30.glClearColor(0f, 0f, 0f, 0f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                            GLES30.glUniform2f(angle, solver.angleX, solver.angleY)
                            GLES30.glUniform2f(fit, 2 * pixels / w, 2 * pixels / h)
                            GLES30.glUniform1f(breath, solver.breathingOffset)
                            GLES30.glUniform2f(bodyOffset, solver.bodyX, solver.bodyY)
                            GLES30.glUniform2f(lean, solver.leanAngle, solver.supportY)
                            resources.forEach(GlLayer::draw)
                            if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                                if (EGL14.eglGetError() == EGL14.EGL_CONTEXT_LOST) throw ContextLost()
                                error("egl_swap_failed")
                            }
                            if (!ready) { ready = true; post { if (session === owner && !owner.cancelled.get()) onReady() } }
                            if (!neutral) settledSent = false
                            if (neutral && solver.settled && !settledSent) { settledSent = true; val token = settleToken; post { if (session === owner && !owner.cancelled.get() && token == settleToken) onSettled() } }
                            val elapsed = System.nanoTime() - started
                            computeNanos += elapsed; frameCount++
                            metrics.add(time, elapsed, sample, solver)
                            if (frameCount == 1) check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "physics_gl_error" }
                            if (frameCount == 600) { android.util.Log.i("MascotPhysics", "600 frames mean render ms=${computeNanos / 600e6}"); frameCount = 0; computeNanos = 0; contextRecoveries = 0 }
                        }
                        // Hidden views do not render or read sensors. Polling here only allows resumption.
                        callback?.let { if (active) choreographer.postFrameCallback(it) else choreographer.postFrameCallbackDelayed(it, 100) }
                    } catch (error: Exception) {
                        // EGL loss invalidates GPU objects, but the installed pack and window survive.
                        val recovered = error is ContextLost && contextRecoveries++ < 2 && !owner.cancelled.get() && runCatching {
                            releaseGraphics(contextLost = true); initializeGraphics()
                            solver.reset(); previous = 0; wasActive = false; ready = false; settledSent = false
                            metrics.previous = 0
                        }.isSuccess
                        if (recovered) callback?.let(choreographer::postFrameCallback)
                        else { post { if (session === owner && !owner.cancelled.get()) onFailure("Не удалось отрисовать физику") }; dispose() }
                    }
                }
                choreographer.postFrameCallback(requireNotNull(callback))
            } catch (error: Exception) { post { if (session === owner && !owner.cancelled.get()) onFailure("Устройство не поддерживает этот режим физики") }; dispose() }
        }
    }

    private class ContextLost : RuntimeException()
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        pause()
        val owner = session?.takeIf { it.texture === surface } ?: return true
        owner.cancelled.set(true); owner.dispose?.invoke(); session = null
        return false // The render thread releases this texture after EGL teardown.
    }

    private class GlLayer(val state: PhysicsSolver.LayerState, file: File) {
        val texture: Int
        private val buffers = IntArray(2)
        private val vertices: FloatBuffer = ByteBuffer.allocateDirect(state.x.size * 5 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val indices: ShortBuffer = ByteBuffer.allocateDirect(state.layer.triangles.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { state.layer.triangles.forEach { put(it.toShort()) }; position(0) }
        init {
            val ids = IntArray(1); GLES30.glGenTextures(1, ids, 0); texture = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            val bitmap = requireNotNull(BitmapFactory.decodeFile(file.absolutePath)); GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0); bitmap.recycle()
            GLES30.glGenBuffers(2, buffers, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.capacity() * 4, null, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.capacity() * 2, indices, GLES30.GL_STATIC_DRAW)
        }
        fun draw() {
            vertices.position(0)
            state.x.indices.forEach { i -> val v = state.layer.vertices[i]; vertices.put(state.x[i]); vertices.put(state.y[i]); vertices.put(v[2]); vertices.put(v[3]); vertices.put(v[4]) }
            vertices.position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0]); GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, vertices.capacity() * 4, vertices)
            GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0)
            GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 12)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture); GLES30.glDrawElements(GLES30.GL_TRIANGLES, state.layer.triangles.size, GLES30.GL_UNSIGNED_SHORT, 0)
        }
        fun dispose() { GLES30.glDeleteTextures(1, intArrayOf(texture), 0); GLES30.glDeleteBuffers(2, buffers, 0) }
    }
    /** Bounded local diagnostics; timings, memory and motion aggregates, never images or keys. */
    private class FrameMetrics(context: Context, private val motion: PhoneMotion) {
        val samples = LongArray(600); var count = 0; var previous = 0L; var interval = 0L; var intervals = 0
        val peaks = FloatArray(8); var neutralFrames = 0
        val file = runCatching {
            val folder = File(context.getExternalFilesDir(null) ?: context.filesDir, "physics-metrics").apply { mkdirs() }
            folder.listFiles()?.filter { it.extension == "csv" }?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
            File(folder,"frames-${System.currentTimeMillis()}.csv").apply { writeText("time_ms,frames,fps,render_mean_ms,render_p95_ms,java_heap_bytes,native_heap_bytes,tilt_x_peak,tilt_y_peak,gravity_x_peak,gravity_y_peak,angle_x_peak,angle_y_peak,body_x_peak,body_y_peak,neutral_frames,rotation_events,gravity_events,acceleration_events,calibrated,tilt_x,tilt_y,body_x,body_y\n") }
        }.getOrNull()
        fun add(time: Long, elapsed: Long, input: PhoneSample, solver: PhysicsSolver) {
            fun peak(i: Int, value: Float) { peaks[i] = maxOf(peaks[i], kotlin.math.abs(value)) }
            peak(0,input.tiltX); peak(1,input.tiltY); peak(2,input.gravityX); peak(3,input.gravityY)
            peak(4,solver.angleX); peak(5,solver.angleY); peak(6,solver.bodyX); peak(7,solver.bodyY)
            if (solver.neutral) neutralFrames++
            if (previous > 0 && time > previous) { interval += time-previous; intervals++ }
            previous=time; samples[count++]=elapsed
            if (count < samples.size) return
            samples.sort()
            val runtime=Runtime.getRuntime()
            val logFile=file
            if (logFile != null && logFile.length() < 256 * 1024) runCatching { logFile.appendText("${System.currentTimeMillis()},$count,${if(interval>0) intervals*1e9/interval else 0.0},${samples.average()/1e6},${samples[569]/1e6},${runtime.totalMemory()-runtime.freeMemory()},${android.os.Debug.getNativeHeapAllocatedSize()},${peaks.joinToString(",")},$neutralFrames,${motion.rotationEvents},${motion.gravityEvents},${motion.accelerationEvents},${motion.calibrated},${input.tiltX},${input.tiltY},${solver.bodyX},${solver.bodyY}\n") }
            count=0; interval=0; intervals=0
            peaks.fill(0f); neutralFrames=0
        }
    }
    private fun makeProgram(): Int {
        fun shader(type: Int, source: String): Int {
            val id = GLES30.glCreateShader(type); GLES30.glShaderSource(id, source); GLES30.glCompileShader(id)
            val result = IntArray(1); GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, result, 0); check(result[0] != 0) { "physics_shader_failed" }; return id
        }
        val vertex = shader(GLES30.GL_VERTEX_SHADER, """#version 300 es
            layout(location=0) in vec3 position;
            layout(location=1) in vec2 texcoord;
            uniform vec2 angle; uniform vec2 fit; uniform vec2 bodyOffset; uniform vec2 lean; uniform float aspect; uniform float breath;
            out vec2 uv;
            void main() {
                vec3 p = position - vec3(0.5, aspect * 0.5, 0.0);
                float x = p.x*cos(angle.x) + p.z*sin(angle.x);
                float z = -p.x*sin(angle.x) + p.z*cos(angle.x);
                float y = p.y*cos(angle.y) - z*sin(angle.y) + breath;
                float rx = x*cos(lean.x) - (y-lean.y)*sin(lean.x);
                float ry = x*sin(lean.x) + (y-lean.y)*cos(lean.x) + lean.y;
                gl_Position = vec4((rx + bodyOffset.x)*fit.x, -(ry + bodyOffset.y)*fit.y, 0.0, 1.0); uv=texcoord;
            }
        """.trimIndent())
        val fragment = shader(GLES30.GL_FRAGMENT_SHADER, """#version 300 es
            precision mediump float; uniform sampler2D image; in vec2 uv; out vec4 color;
            void main() { color=texture(image,uv); }
        """.trimIndent())
        val program = GLES30.glCreateProgram(); GLES30.glAttachShader(program, vertex); GLES30.glAttachShader(program, fragment); GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vertex); GLES30.glDeleteShader(fragment)
        val result = IntArray(1); GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, result, 0); check(result[0] != 0); return program
    }
}
