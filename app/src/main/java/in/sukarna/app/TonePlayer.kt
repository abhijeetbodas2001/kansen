package `in`.sukarna.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

class TonePlayer(context: Context, private val onError: () -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attributes).build()
    private val samples = mutableMapOf<Int, Int>()
    private val ready = mutableSetOf<Int>()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change -> if (change < 0) stop() }
        .build()
    private var stream = 0
    private var generation = 0
    private var fadeIn: Runnable? = null
    private var pending: Pair<Int, Boolean>? = null
    @Volatile private var closed = false
    private val cache = File(context.cacheDir, "swara-audio-v6").apply { mkdirs() }

    init {
        pool.setOnLoadCompleteListener { _, sample, status ->
            if (!closed) {
                if (status == 0) ready.add(sample)
                else Log.e("TonePlayer", "Could not preload sample $sample: $status")
                startPending()
            }
        }
        // Decode every pitch ahead of time; key presses only start a native stream.
        worker.execute {
            for (midi in 60..83) {
                if (closed) break
                try {
                    val file = File(cache, "$midi.wav")
                    if (!file.exists()) {
                        val pcm = Music.sustainedTone(midi)
                        val dataSize = pcm.size * 2
                        val wav = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
                        wav.put("RIFF".toByteArray()).putInt(36 + dataSize)
                        wav.put("WAVEfmt ".toByteArray()).putInt(16)
                        wav.putShort(1).putShort(1).putInt(44100).putInt(88200)
                        wav.putShort(2).putShort(16)
                        wav.put("data".toByteArray()).putInt(dataSize)
                        pcm.forEach { wav.putShort(it) }
                        file.writeBytes(wav.array())
                    }
                    main.post {
                        if (!closed) {
                            val sample = pool.load(file.absolutePath, 1)
                            samples[midi] = sample
                            startPending()
                        }
                    }
                } catch (error: Exception) {
                    Log.e("TonePlayer", "Could not preload MIDI note $midi", error)
                }
            }
        }
    }

    fun play(midi: Int, sustain: Boolean = false) {
        if (closed) return
        cancelFadeIn()
        generation++
        if (stream != 0) pool.stop(stream)
        stream = 0
        pending = null
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            onError()
            return
        }
        pending = midi to sustain
        startPending()
    }

    private fun startPending() {
        val (midi, sustain) = pending ?: return
        val sample = samples[midi] ?: return
        if (sample !in ready) return
        pending = null
        stream = pool.play(sample, 0f, 0f, 1, -1, 1f)
        if (stream == 0) { stop(); onError(); return }
        val request = generation
        val activeStream = stream
        val startedAt = SystemClock.elapsedRealtime()
        // Ramp the stream once, leaving the repeating PCM buffer unchanged.
        val ramp = object : Runnable {
            override fun run() {
                if (closed || request != generation || stream != activeStream) return
                val volume = ((SystemClock.elapsedRealtime() - startedAt) / 1000f).coerceIn(0f, 1f)
                pool.setVolume(activeStream, volume, volume)
                if (volume < 1f) main.postDelayed(this, 10)
                else fadeIn = null
            }
        }
        fadeIn = ramp
        main.postDelayed(ramp, 10)
        if (!sustain) main.postDelayed({ if (request == generation) stop() }, 3000)
    }

    private fun cancelFadeIn() {
        fadeIn?.let { main.removeCallbacks(it) }
        fadeIn = null
    }

    fun stop() {
        cancelFadeIn()
        generation++
        pending = null
        if (!closed && stream != 0) pool.stop(stream)
        stream = 0
        manager.abandonAudioFocusRequest(focus)
    }

    override fun close() {
        stop()
        closed = true
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        pool.release()
    }
}
