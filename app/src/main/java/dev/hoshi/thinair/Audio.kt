package dev.hoshi.thinair

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool

class Audio(private val ctx: Context) {
    private var music: MediaPlayer? = null
    private var current: String? = null
    private val pool: SoundPool
    private val sfx = HashMap<String, Int>()

    init {
        pool = SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        for (n in listOf("step", "chop", "chop2", "click", "eat", "pickup", "dead", "start")) {
            try {
                ctx.assets.openFd("audio/sfx/$n.ogg").use { sfx[n] = pool.load(it, 1) }
            } catch (_: Exception) { }
        }
    }

    fun playSfx(name: String, vol: Float = 0.7f) {
        val id = sfx[name] ?: return
        pool.play(id, vol, vol, 1, 0, 1f)
    }

    fun music(path: String, vol: Float = 0.42f) {
        if (current == path && music?.isPlaying == true) return
        stopMusic()
        current = path
        try {
            val mp = MediaPlayer()
            ctx.assets.openFd(path).use { fd: AssetFileDescriptor ->
                mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            mp.isLooping = true
            mp.setVolume(vol, vol)
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
            music = mp
        } catch (_: Exception) {
            current = null
        }
    }

    fun stopMusic() {
        try { music?.stop() } catch (_: Exception) {}
        music?.release()
        music = null
        current = null
    }

    fun release() {
        stopMusic()
        pool.release()
    }
}
