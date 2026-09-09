
package com.skteam.subtitleburner

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface

class OutputSurface(sharedContext: EGLContext, width: Int, height: Int) : SurfaceTexture.OnFrameAvailableListener {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null

    var surfaceTexture: SurfaceTexture? = null
        private set

    var textureId: Int = 0
        private set

    private val frameSync = Object()
    @Volatile private var frameAvailable = false

    init {
        setupEGL(sharedContext, width, height)
    }

    private fun setupEGL(sharedContext: EGLContext, width: Int, height: Int) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay === EGL14.EGL_NO_DISPLAY)
            throw RuntimeException("unable to get EGL14 display")
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, sharedContext, contextAttribs, 0)

        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )

        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs, 0)

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        textureId = TextureRenderer.createTextureObject()
        val st = SurfaceTexture(textureId)
        st.setOnFrameAvailableListener(this)
        st.setDefaultBufferSize(width, height)
        surfaceTexture = st
    }

    fun awaitNewImage(): Boolean {
        synchronized(frameSync) {
            while (!frameAvailable) {
                try {
                    frameSync.wait(2500)
                } catch (e: InterruptedException) {
                    return false
                }
            }
            frameAvailable = false
        }
        surfaceTexture?.updateTexImage()
        return true
    }

    fun getTimestamp(): Long {
        return surfaceTexture?.timestamp ?: 0
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        synchronized(frameSync) {
            frameAvailable = true
            frameSync.notifyAll()
        }
    }

    fun release() {
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext !== EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }
}
