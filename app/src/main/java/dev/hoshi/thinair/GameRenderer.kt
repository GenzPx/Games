package dev.hoshi.thinair

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class GameRenderer(val sim: Sim) : GLSurfaceView.Renderer {
    private var prog = 0
    private var uMVP = 0; private var uM = 0; private var uSun = 0
    private var uFog = 0; private var uFogD = 0; private var uEye = 0
    private var aPos = 0; private var aNrm = 0; private var aCol = 0
    private lateinit var terrain: Mesh
    private lateinit var box: Mesh
    private lateinit var climber: Mesh
    private lateinit var iceBox: Mesh
    private lateinit var restBox: Mesh
    private val mvp = FloatArray(16)
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val tmp = FloatArray(16)
    private var aspect = 1.6f
    private var lastNs = 0L
    var stickX = 0f
    var stickY = 0f
    var climb = false
    var run = false
    var hud: ((Sim) -> Unit)? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.45f, 0.58f, 0.72f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        prog = Gl.program()
        uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        uM = GLES20.glGetUniformLocation(prog, "uM")
        uSun = GLES20.glGetUniformLocation(prog, "uSun")
        uFog = GLES20.glGetUniformLocation(prog, "uFog")
        uFogD = GLES20.glGetUniformLocation(prog, "uFogD")
        uEye = GLES20.glGetUniformLocation(prog, "uEye")
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aNrm = GLES20.glGetAttribLocation(prog, "aNrm")
        aCol = GLES20.glGetAttribLocation(prog, "aCol")
        terrain = MeshFactory.terrain(88)
        box = MeshFactory.box(0.42f, 0.38f, 0.34f)
        iceBox = MeshFactory.box(0.62f, 0.82f, 0.90f)
        restBox = MeshFactory.box(0.79f, 0.64f, 0.16f)
        climber = MeshFactory.box(0.78f, 0.12f, 0.12f)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        aspect = w / h.toFloat().coerceAtLeast(0.1f)
        Matrix.perspectiveM(proj, 0, 62f, aspect, 0.2f, 2800f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        if (lastNs == 0L) lastNs = now
        val dt = ((now - lastNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastNs = now
        sim.step(dt, stickX, stickY, climb, run)
        hud?.invoke(sim)

        val hour = sim.hour
        val sunA = ((hour - 6f) / 12f) * Math.PI.toFloat()
        val day = sin(sunA).coerceIn(0f, 1f)
        val night = if (hour < 6f || hour > 18.5f) 1f else (1f - day * 2f).coerceIn(0f, 1f)
        if (night > 0.6f) GLES20.glClearColor(0.04f, 0.06f, 0.10f, 1f)
        else GLES20.glClearColor(0.53f, 0.66f, 0.80f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val lookY = sim.y + 1.35f
        val dist = 5.6f
        val eyeX = sim.x + sin(sim.yaw) * -dist * cos(sim.pitch)
        val eyeY = sim.y + 2.1f + sin(sim.pitch) * dist
        val eyeZ = sim.z + cos(sim.yaw) * -dist * cos(sim.pitch)
        val ground = World.heightY(eyeX, eyeZ) + 0.6f
        val camY = if (eyeY < ground) ground else eyeY
        Matrix.setLookAtM(view, 0, eyeX, camY, eyeZ, sim.x, lookY, sim.z, 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        GLES20.glUseProgram(prog)
        GLES20.glUniform3f(uSun, 0.35f, 0.75f + day * 0.2f, 0.25f)
        if (night > 0.6f) GLES20.glUniform3f(uFog, 0.05f, 0.07f, 0.11f)
        else GLES20.glUniform3f(uFog, 0.54f, 0.64f, 0.72f)
        GLES20.glUniform1f(uFogD, 0.00055f + sim.weather * 0.001f)
        GLES20.glUniform3f(uEye, eyeX, camY, eyeZ)

        identity()
        draw(terrain)

        for (h in sim.holds) {
            identity()
            Matrix.translateM(model, 0, h.x, h.y, h.z)
            Matrix.scaleM(model, 0, h.w, h.thick, h.d)
            val m = when {
                h.rest -> restBox
                h.ice -> iceBox
                else -> box
            }
            draw(m)
        }

        identity()
        Matrix.translateM(model, 0, sim.x, sim.y + 0.9f, sim.z)
        Matrix.rotateM(model, 0, Math.toDegrees(sim.yaw.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.scaleM(model, 0, 0.55f, 1.7f, 0.45f)
        draw(climber)
        identity()
        Matrix.translateM(model, 0, sim.x, sim.y + 1.75f, sim.z)
        Matrix.scaleM(model, 0, 0.38f, 0.38f, 0.38f)
        draw(climber)
    }

    private fun identity() {
        Matrix.setIdentityM(model, 0)
    }

    private fun draw(mesh: Mesh) {
        Matrix.multiplyMM(tmp, 0, mvp, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, tmp, 0)
        GLES20.glUniformMatrix4fv(uM, 1, false, model, 0)
        mesh.bind(aPos, aNrm, aCol)
        mesh.draw()
    }
}
