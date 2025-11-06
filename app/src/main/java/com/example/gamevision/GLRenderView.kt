package com.example.gamevision

import android.content.Context
import android.media.Image
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GLRenderView(context: Context) : GLSurfaceView(context) {
    private val renderer: SimpleRenderer

    init {
        setEGLContextClientVersion(2)
        renderer = SimpleRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun queueFrame(img: Image) {
        renderer.queueImage(img)
        requestRender()
    }

    private class SimpleRenderer : Renderer {
        private val verts = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f
        )
        private val vb: FloatBuffer = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(verts); position(0) }

        private var program = 0
        private var texId = IntArray(1)
        @Volatile private var latestImage: Image? = null

        fun queueImage(img: Image) {
            val prev = latestImage
            latestImage = img
            prev?.close()
        }

        override fun onSurfaceCreated(gl: GL10?, cfg: EGLConfig?) {
            val vs = """
            attribute vec2 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() {
                vTex = aTex;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
            """.trimIndent()

            val fs = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D uTex;
            uniform float uBoost;
            const vec3 greenCenter = vec3(0.3, 0.8, 0.2);
            const float radius = 0.25;
            void main() {
                vec4 c = texture2D(uTex, vTex);
                float d = distance(c.rgb, greenCenter);
                float mask = smoothstep(radius, 0.0, d);
                float boost = uBoost * mask;
                vec3 boosted = c.rgb;
                boosted.g = clamp(boosted.g + boost, 0.0, 1.0);
                boosted = mix(c.rgb, boosted, mask);
                gl_FragColor = vec4(boosted, c.a);
            }
            """.trimIndent()

            val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vs)
            val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fs)
            program = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vShader)
                GLES20.glAttachShader(it, fShader)
                GLES20.glLinkProgram(it)
            }

            GLES20.glGenTextures(1, texId, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            val img = latestImage ?: run {
                drawQuad()
                return
            }

            try {
                val plane = img.planes[0]
                val buffer = plane.buffer
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val width = img.width
                val height = img.height

                if (pixelStride == 4 && rowStride == width * 4) {
                    buffer.position(0)
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId[0])
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
                    bmp.recycle()
                } else {
                    val packed = ByteArray(width * height * 4)
                    var p = 0
                    buffer.position(0)
                    val row = ByteArray(rowStride)
                    for (y in 0 until height) {
                        buffer.position(y * rowStride)
                        buffer.get(row, 0, rowStride)
                        var x = 0
                        while (x < width) {
                            val base = x * pixelStride
                            packed[p++] = row[base]
                            packed[p++] = row[base + 1]
                            packed[p++] = row[base + 2]
                            packed[p++] = row[base + 3]
                            x++
                        }
                    }
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId[0])
                    val bb = ByteBuffer.allocateDirect(packed.size).order(java.nio.ByteOrder.nativeOrder())
                    bb.put(packed).position(0)
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
                }
            } catch (e: Exception) {
                // ignore errors in prototype
            } finally {
                img.close()
                latestImage = null
            }

            drawQuad()
        }

        private fun drawQuad() {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            val posLoc = GLES20.glGetAttribLocation(program, "aPos")
            val texLoc = GLES20.glGetAttribLocation(program, "aTex")
            val uTex = GLES20.glGetUniformLocation(program, "uTex")
            val uBoost = GLES20.glGetUniformLocation(program, "uBoost")

            vb.position(0)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, vb)
            vb.position(2)
            GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, vb)
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glEnableVertexAttribArray(texLoc)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId[0])
            GLES20.glUniform1i(uTex, 0)
            GLES20.glUniform1f(uBoost, 0.5f)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            GLES20.glDisableVertexAttribArray(posLoc)
            GLES20.glDisableVertexAttribArray(texLoc)
        }

        private fun loadShader(type: Int, src: String): Int {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            return sh
        }
    }
}

