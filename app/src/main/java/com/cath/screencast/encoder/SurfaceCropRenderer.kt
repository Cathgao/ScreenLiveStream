package com.cath.screencast.encoder

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.cath.screencast.log.AppLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger

class SurfaceCropRenderer(
    private val codecInputSurface: Surface,
    private val width: Int,
    private val height: Int
) : SurfaceTexture.OnFrameAvailableListener {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var textureId: Int = 0
    var surfaceTexture: SurfaceTexture? = null
        private set
    var inputSurface: Surface? = null
        private set

    @Volatile
    private var frameAvailable = false
    private val transformMatrix = FloatArray(16)

    private var listenerThread: HandlerThread? = null

    private val framesReceivedCount = AtomicInteger(0)
    private var framesDrawnCount = 0
    private var lastLogTime = System.currentTimeMillis()

    private var program: Int = 0
    private var aPositionHandle: Int = 0
    private var aTexCoordHandle: Int = 0
    private var uSTMatrixHandle: Int = 0

    private val vertexBuffer: FloatBuffer
    private val texBuffer: FloatBuffer

    // Full screen quad vertices
    private val quadVertices = floatArrayOf(
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    )

    // Standard texture coords
    private val quadTexCoords = floatArrayOf(
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f
    )

    init {
        AppLogger.i(TAG, "Initializing SurfaceCropRenderer ($width x $height)")

        vertexBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quadVertices)
        vertexBuffer.position(0)

        texBuffer = ByteBuffer.allocateDirect(quadTexCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quadTexCoords)
        texBuffer.position(0)

        initEgl()
        initGlProgram()

        // Detach EGLContext from constructor thread so drawFrame() thread can claim it
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        AppLogger.i(TAG, "EGL Context detached from main thread successfully.")
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("eglGetDisplay failed: ${EGL14.eglGetError()}")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed: ${EGL14.eglGetError()}")
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL10_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        val config = configs[0] ?: throw RuntimeException("eglChooseConfig failed")

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )

        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext failed: ${EGL14.eglGetError()}")
        }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, codecInputSurface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreateWindowSurface failed: ${EGL14.eglGetError()}")
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed during init: ${EGL14.eglGetError()}")
        }

        AppLogger.i(TAG, "EGL initialized successfully on creation thread.")
    }

    private fun initGlProgram() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val ht = HandlerThread("GLFrameListenerThread").apply { start() }
        listenerThread = ht

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture?.setDefaultBufferSize(width, height)
        surfaceTexture?.setOnFrameAvailableListener(this, Handler(ht.looper))
        inputSurface = Surface(surfaceTexture)

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")

        AppLogger.i(TAG, "GL Program compiled and linked successfully.")
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        val count = framesReceivedCount.incrementAndGet()
        synchronized(this) {
            frameAvailable = true
        }
        if (count == 1 || count % 300 == 0) {
            AppLogger.i(TAG, "SurfaceTexture onFrameAvailable received frame #$count from VirtualDisplay")
        }
    }

    fun drawFrame() {
        synchronized(this) {
            if (!frameAvailable) return
            frameAvailable = false
        }

        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE && eglContext != EGL14.EGL_NO_CONTEXT) {
            val madeCurrent = EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            if (!madeCurrent) {
                val err = EGL14.eglGetError()
                AppLogger.e(TAG, "eglMakeCurrent failed in drawFrame: error code $err")
                return
            }
        }

        val st = surfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(transformMatrix)

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPositionHandle)

        texBuffer.position(0)
        GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, texBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordHandle)

        GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, transformMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        val ptsNs = if (st.timestamp > 0) st.timestamp else System.nanoTime()
        EGLExt_EGLElement.setPresentationTime(eglDisplay, eglSurface, ptsNs)

        val swapped = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        if (!swapped) {
            val err = EGL14.eglGetError()
            AppLogger.e(TAG, "eglSwapBuffers failed in drawFrame: error code $err")
        }

        framesDrawnCount++
        val now = System.currentTimeMillis()
        if (framesDrawnCount == 1 || framesDrawnCount % 120 == 0 || now - lastLogTime > 4000) {
            lastLogTime = now
            AppLogger.i(TAG, "GL Renderer Stats: Received=${framesReceivedCount.get()} frames, Drawn=$framesDrawnCount frames")
        }
    }

    fun release() {
        AppLogger.i(TAG, "Releasing SurfaceCropRenderer (Total Received=${framesReceivedCount.get()}, Drawn=$framesDrawnCount)")
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        inputSurface?.release()
        surfaceTexture?.release()
        listenerThread?.quitSafely()
        listenerThread = null

        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val info = GLES20.glGetShaderInfoLog(shader)
            AppLogger.e(TAG, "Could not compile shader $type: $info")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private const val TAG = "SurfaceCropRenderer"
        private const val EGL10_RECORDABLE_ANDROID = 0x3142

        private const val VERTEX_SHADER_CODE = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uSTMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uSTMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER_CODE = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;

            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }

    private object EGLExt_EGLElement {
        fun setPresentationTime(display: EGLDisplay, surface: EGLSurface, nsecs: Long) {
            android.opengl.EGLExt.eglPresentationTimeANDROID(display, surface, nsecs)
        }
    }
}
