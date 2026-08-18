package dev.hoshi.thinair

class NativeSim {
    private var ptr: Long = nativeCreate()

    data class Snapshot(
        val spo2: Float,
        val hr: Float,
        val stamina: Float,
        val coreC: Float,
        val moveScale: Float,
        val clarity: Float,
        val dead: Boolean,
        val collapsed: Boolean,
        val hape: Float,
        val hace: Float,
        val calories: Float,
        val water: Float,
        val cause: String,
    )

    private val buf = FloatArray(12)

    fun tick(
        dt: Float,
        alt: Float,
        speed: Float,
        slope: Float,
        climbing: Boolean,
        resting: Boolean,
        o2: Float,
        wind: Float,
        airTemp: Float,
        wet: Float,
        sheltered: Float,
    ) {
        if (ptr == 0L) return
        nativeTick(ptr, dt, alt, speed, slope, climbing, resting, o2, wind, airTemp, wet, sheltered)
    }

    fun snapshot(): Snapshot {
        if (ptr == 0L) {
            return Snapshot(96f, 72f, 0.9f, 36.8f, 1f, 1f, false, false, 0f, 0f, 2800f, 2f, "")
        }
        nativeFill(ptr, buf)
        return Snapshot(
            buf[0], buf[1], buf[2], buf[3], buf[4], buf[5],
            buf[6] > 0.5f, buf[7] > 0.5f, buf[8], buf[9], buf[10], buf[11],
            nativeCause(ptr) ?: "",
        )
    }

    fun destroy() {
        if (ptr != 0L) {
            nativeDestroy(ptr)
            ptr = 0
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(ptr: Long)
    private external fun nativeTick(
        ptr: Long, dt: Float, alt: Float, speed: Float, slope: Float,
        climbing: Boolean, resting: Boolean, o2: Float, wind: Float,
        airTemp: Float, wet: Float, sheltered: Float,
    )
    private external fun nativeFill(ptr: Long, out: FloatArray): Int
    private external fun nativeCause(ptr: Long): String?

    companion object {
        init {
            System.loadLibrary("thinair")
        }
    }
}
