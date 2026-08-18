package dev.hoshi.thinair

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var sim: Sim
    private lateinit var gl: GameSurfaceView
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystem()
        sim = Sim()
        val root = layoutInflater.inflate(R.layout.activity_main, null) as FrameLayout
        gl = GameSurfaceView(this, sim)
        root.addView(
            gl, 0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.isClickable = true
        root.setOnTouchListener { _, e -> gl.onTouchEvent(e) }
        setContentView(root)

        val stick = findViewById<JoystickView>(R.id.stick)
        val climb = findViewById<Button>(R.id.btnClimb)
        val run = findViewById<Button>(R.id.btnRun)
        val alt = findViewById<TextView>(R.id.txtAlt)
        val stam = findViewById<TextView>(R.id.txtStam)
        val spo = findViewById<TextView>(R.id.txtSpo)
        val prompt = findViewById<TextView>(R.id.txtPrompt)
        val renderer = gl.gameRenderer

        climb.setOnTouchListener { _, e ->
            renderer.climb = e.action != android.view.MotionEvent.ACTION_UP &&
                e.action != android.view.MotionEvent.ACTION_CANCEL
            true
        }
        run.setOnTouchListener { _, e ->
            renderer.run = e.action != android.view.MotionEvent.ACTION_UP &&
                e.action != android.view.MotionEvent.ACTION_CANCEL
            true
        }
        findViewById<Button>(R.id.btnTent).setOnClickListener { sim.tent() }
        findViewById<Button>(R.id.btnEat).setOnClickListener { sim.eat() }
        findViewById<Button>(R.id.btnO2).setOnClickListener { sim.oxygen() }

        renderer.hud = { s ->
            ui.post {
                renderer.stickX = stick.nx
                renderer.stickY = stick.ny
                val sn = s.snap
                alt.text = "${s.alt.toInt()} m"
                stam.text = "STAMINA  ${(sn.stamina * 100).toInt()}%"
                spo.text = "SpO₂ ${sn.spo2.toInt()}   O₂ ${s.o2Bottles}${if (s.o2On) " ●" else ""}"
                prompt.text = s.prompt
                if (s.ended != null) {
                    prompt.text = s.prompt
                    prompt.setBackgroundColor(0x99000000.toInt())
                }
            }
        }
    }

    private fun hideSystem() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onPause() {
        super.onPause()
        if (this::gl.isInitialized) gl.onPause()
    }

    override fun onResume() {
        super.onResume()
        hideSystem()
        if (this::gl.isInitialized) gl.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::sim.isInitialized) sim.destroy()
    }
}
