package dev.hoshi.thinair

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

object Gl {
    const val VS = """
        uniform mat4 uMVP;
        uniform mat4 uM;
        attribute vec3 aPos;
        attribute vec3 aNrm;
        attribute vec3 aCol;
        varying vec3 vN;
        varying vec3 vC;
        varying vec3 vW;
        void main() {
          vec4 w = uM * vec4(aPos, 1.0);
          vW = w.xyz;
          vN = mat3(uM) * aNrm;
          vC = aCol;
          gl_Position = uMVP * vec4(aPos, 1.0);
        }
    """
    const val FS = """
        precision mediump float;
        uniform vec3 uSun;
        uniform vec3 uFog;
        uniform float uFogD;
        uniform vec3 uEye;
        varying vec3 vN;
        varying vec3 vC;
        varying vec3 vW;
        void main() {
          vec3 n = normalize(vN);
          float ndl = max(0.12, dot(n, normalize(uSun)));
          float hemi = 0.25 + 0.35 * (n.y * 0.5 + 0.5);
          vec3 col = vC * (ndl * 0.85 + hemi);
          float fog = 1.0 - exp(-length(vW - uEye) * uFogD);
          col = mix(col, uFog, clamp(fog, 0.0, 0.72));
          gl_FragColor = vec4(col, 1.0);
        }
    """

    fun compile(type: Int, src: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, src)
        GLES20.glCompileShader(id)
        return id
    }

    fun program(): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, VS))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, FS))
        GLES20.glLinkProgram(p)
        return p
    }

    fun floats(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(a); position(0)
        }

    fun shorts(a: ShortArray): ShortBuffer =
        ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
            put(a); position(0)
        }
}

class Mesh(verts: FloatArray, idx: ShortArray) {
    private val vbo = IntArray(1)
    private val ibo = IntArray(1)
    val count = idx.size
    init {
        GLES20.glGenBuffers(1, vbo, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, Gl.floats(verts), GLES20.GL_STATIC_DRAW)
        GLES20.glGenBuffers(1, ibo, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[0])
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, Gl.shorts(idx), GLES20.GL_STATIC_DRAW)
    }
    fun bind(aPos: Int, aNrm: Int, aCol: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])
        val stride = 9 * 4
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glVertexAttribPointer(aNrm, 3, GLES20.GL_FLOAT, false, stride, 12)
        GLES20.glVertexAttribPointer(aCol, 3, GLES20.GL_FLOAT, false, stride, 24)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aNrm)
        GLES20.glEnableVertexAttribArray(aCol)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[0])
    }
    fun draw() {
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, count, GLES20.GL_UNSIGNED_SHORT, 0)
    }
}

object MeshFactory {
    fun terrain(seg: Int = 96): Mesh {
        val verts = FloatArray((seg + 1) * (seg + 1) * 9)
        var i = 0
        for (z in 0..seg) {
            val tz = z / seg.toFloat()
            val wz = (tz - 0.5f) * WORLD_M
            for (x in 0..seg) {
                val tx = x / seg.toFloat()
                val wx = (tx - 0.5f) * WORLD_M
                val wy = World.heightY(wx, wz)
                val nrmY = World.heightY(wx, wz + 2f) - World.heightY(wx, wz - 2f)
                val nrmX = World.heightY(wx + 2f, wz) - World.heightY(wx - 2f, wz)
                var nx = -nrmX; var ny = 4f; var nz = -nrmY
                val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
                nx /= len; ny /= len; nz /= len
                val steep = World.slopeDeg(wx, wz)
                val c = World.biome(wy / VISUAL_H, steep)
                verts[i++] = wx; verts[i++] = wy; verts[i++] = wz
                verts[i++] = nx; verts[i++] = ny; verts[i++] = nz
                verts[i++] = c[0]; verts[i++] = c[1]; verts[i++] = c[2]
            }
        }
        val idx = ShortArray(seg * seg * 6)
        var k = 0
        val row = seg + 1
        for (z in 0 until seg) for (x in 0 until seg) {
            val a = (z * row + x).toShort()
            val b = (z * row + x + 1).toShort()
            val c = ((z + 1) * row + x).toShort()
            val d = ((z + 1) * row + x + 1).toShort()
            idx[k++] = a; idx[k++] = c; idx[k++] = b
            idx[k++] = b; idx[k++] = c; idx[k++] = d
        }
        return Mesh(verts, idx)
    }

    fun box(r: Float, g: Float, b: Float): Mesh {
        val p = arrayOf(
            floatArrayOf(-0.5f, -0.5f, 0.5f), floatArrayOf(0.5f, -0.5f, 0.5f),
            floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(-0.5f, 0.5f, 0.5f),
            floatArrayOf(-0.5f, -0.5f, -0.5f), floatArrayOf(0.5f, -0.5f, -0.5f),
            floatArrayOf(0.5f, 0.5f, -0.5f), floatArrayOf(-0.5f, 0.5f, -0.5f),
        )
        val faces = arrayOf(
            intArrayOf(0, 1, 2, 3, 0, 0, 1),
            intArrayOf(5, 4, 7, 6, 0, 0, -1),
            intArrayOf(4, 0, 3, 7, -1, 0, 0),
            intArrayOf(1, 5, 6, 2, 1, 0, 0),
            intArrayOf(3, 2, 6, 7, 0, 1, 0),
            intArrayOf(4, 5, 1, 0, 0, -1, 0),
        )
        val verts = FloatArray(6 * 4 * 9)
        val idx = ShortArray(6 * 6)
        var vi = 0; var ii = 0; var base = 0
        for (f in faces) {
            val n = floatArrayOf(f[4].toFloat(), f[5].toFloat(), f[6].toFloat())
            for (q in 0..3) {
                val pt = p[f[q]]
                verts[vi++] = pt[0]; verts[vi++] = pt[1]; verts[vi++] = pt[2]
                verts[vi++] = n[0]; verts[vi++] = n[1]; verts[vi++] = n[2]
                verts[vi++] = r; verts[vi++] = g; verts[vi++] = b
            }
            idx[ii++] = base.toShort(); idx[ii++] = (base + 1).toShort(); idx[ii++] = (base + 2).toShort()
            idx[ii++] = base.toShort(); idx[ii++] = (base + 2).toShort(); idx[ii++] = (base + 3).toShort()
            base += 4
        }
        return Mesh(verts, idx)
    }
}
