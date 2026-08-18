package dev.hoshi.thinair

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent

class GameSurfaceView(ctx: Context, val sim: Sim) : GLSurfaceView(ctx) {
    var stickX = 0f
    var stickY = 0f
    var climb = false
    var run = false
    private var lookId = -1
    private var lastX = 0f
    private var lastY = 0f

    val gameRenderer = GameRenderer(sim)

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(gameRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // look is handled here only if overlay doesn't consume — overlay takes left stick / buttons
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                if (e.getX(i) > width * 0.42f && lookId == -1) {
                    lookId = e.getPointerId(i)
                    lastX = e.getX(i); lastY = e.getY(i)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    if (e.getPointerId(i) == lookId) {
                        sim.look(e.getX(i) - lastX, e.getY(i) - lastY)
                        lastX = e.getX(i); lastY = e.getY(i)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == lookId) lookId = -1
            }
        }
        return true
    }
}
