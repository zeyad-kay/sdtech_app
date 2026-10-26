package com.example.sdtech_android_native.data

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val VERTEX_SHADER = """
    attribute vec4 aPosition;
    attribute vec4 aTextureCoord;
    uniform mat4 uTexMatrix;
    varying vec2 vTextureCoord;
    void main() {
        gl_Position = aPosition;
        vTextureCoord = (uTexMatrix * aTextureCoord).xy;
    }
"""

private const val FRAGMENT_SHADER = """
    #extension GL_OES_EGL_image_external : require
    precision mediump float;
    varying vec2 vTextureCoord;
    uniform samplerExternalOES sTexture;
    void main() {
        gl_FragColor = texture2D(sTexture, vTextureCoord);
    }
"""

// A full-screen quad as a triangle strip, with homogeneous (vec4) coordinates
// to match the mat4 transform SurfaceTexture.getTransformMatrix() returns.
private val QUAD_POSITIONS = floatArrayOf(
    -1f, -1f, 0f, 1f,
    1f, -1f, 0f, 1f,
    -1f, 1f, 0f, 1f,
    1f, 1f, 0f, 1f,
)
private val QUAD_TEX_COORDS = floatArrayOf(
    0f, 0f, 0f, 1f,
    1f, 0f, 0f, 1f,
    0f, 1f, 0f, 1f,
    1f, 1f, 0f, 1f,
)

/**
 * Renders live camera frames, via OpenGL, from one camera-facing
 * [SurfaceTexture] to two independent output surfaces — a preview surface and
 * a [android.media.MediaCodec] encoder's input surface — applying
 * [SurfaceTexture.getTransformMatrix] explicitly for both.
 *
 * This exists because only a `TextureView` applies that transform
 * automatically, as part of its own internal rendering when it owns the
 * `SurfaceTexture` directly. Any other raw [Surface] consumer — like a
 * MediaCodec encoder's input surface — receives the camera's buffers exactly
 * as the sensor/HAL wrote them, with no orientation/mirror correction
 * applied. [VideoRecordingEngine] used to feed the preview surface and the
 * encoder surface from the same Camera2 capture request directly, so the
 * preview looked correct (TextureView's own correction) while the encoded —
 * and therefore streamed/saved — video came out flipped. Routing the camera
 * through this renderer instead means both outputs get the same explicit
 * correction, so they match.
 *
 * All methods must be called from the same single thread throughout this
 * instance's lifetime (the caller owns that thread — see
 * [VideoRecordingEngine]'s dedicated GL thread) since an EGL context can only
 * be current on one thread at a time.
 */
class CameraGlRenderer(
    private val previewSurface: Surface,
    private val encoderSurface: Surface,
    private val width: Int,
    private val height: Int,
) {
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var previewEglSurface = EGL14.EGL_NO_SURFACE
    private var encoderEglSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var cameraTextureId = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var texMatrixHandle = 0

    private val positionBuffer = toFloatBuffer(QUAD_POSITIONS)
    private val texCoordBuffer = toFloatBuffer(QUAD_TEX_COORDS)
    private val transformMatrix = FloatArray(16)

    /** The camera's sole capture target — writes land here, never directly on a surface the app shows or encodes. */
    val cameraSurfaceTexture: SurfaceTexture
    val cameraInputSurface: Surface

    init {
        initEgl()
        previewEglSurface = createWindowSurface(previewSurface)
        encoderEglSurface = createWindowSurface(encoderSurface)
        makeCurrent(previewEglSurface)
        cameraTextureId = createOesTexture()
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")

        cameraSurfaceTexture = SurfaceTexture(cameraTextureId).apply {
            setDefaultBufferSize(width, height)
        }
        cameraInputSurface = Surface(cameraSurfaceTexture)
    }

    /** Call on [SurfaceTexture.OnFrameAvailableListener] for [cameraSurfaceTexture]: consumes the new frame and redraws it to both output surfaces. */
    fun drawFrame() {
        makeCurrent(previewEglSurface)
        cameraSurfaceTexture.updateTexImage()
        cameraSurfaceTexture.getTransformMatrix(transformMatrix)
        val timestampNs = cameraSurfaceTexture.timestamp

        renderTo(previewEglSurface, timestampNs)
        renderTo(encoderEglSurface, timestampNs)
    }

    fun release() {
        cameraSurfaceTexture.release()
        cameraInputSurface.release()
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (previewEglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, previewEglSurface)
            if (encoderEglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        previewEglSurface = EGL14.EGL_NO_SURFACE
        encoderEglSurface = EGL14.EGL_NO_SURFACE
        previewSurface.release()
    }

    private fun renderTo(eglSurface: EGLSurface, timestampNs: Long) {
        makeCurrent(eglSurface)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)

        positionBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 4, GLES20.GL_FLOAT, false, 0, positionBuffer)

        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 4, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, transformMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            throw RuntimeException("eglChooseConfig failed")
        }
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")
    }

    private fun createWindowSurface(surface: Surface): EGLSurface {
        val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")
        return eglSurface
    }

    private fun makeCurrent(eglSurface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    private fun createOesTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textureId
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Program link failed: $log")
        }
        return program
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }

    private fun toFloatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data)
            position(0)
        }
}
