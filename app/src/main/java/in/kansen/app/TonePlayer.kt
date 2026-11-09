package `in`.kansen.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred

class TonePlayer(context: Context, private val onError: () -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val samples = mutableMapOf<Int, InstrumentTone>()
    private val assets = context.assets
    private val models by lazy { InstrumentModels(assets) }
    private val preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    var instrument: Instrument = Instrument.fromId(preferences.getString("instrument", null))
        private set
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change -> if (change < 0) stop() }
        .build()
    private var track: AudioTrack? = null
    private var generation = 0
    private var fadeIn: Runnable? = null
    private var pending: Triple<Int, Boolean, (Boolean) -> Unit>? = null
    @Volatile private var priorityMidi = -1
    @Volatile private var closed = false
    @Volatile private var bankGeneration = 0

    init { preload(instrument) }

    fun selectInstrument(selection: Instrument) {
        if (closed || selection == instrument) return
        stop()
        instrument = selection
        preferences.edit().putString("instrument", selection.id).apply()
        preload(selection)
    }

    private fun preload(selection: Instrument) {
        val bank = ++bankGeneration
        samples.clear()
        worker.execute {
            // Prioritize the middle register used most often on startup.
            val remaining = ((60..83) + (48..59) + (84..95)).toMutableList()
            while (remaining.isNotEmpty()) {
                if (closed || bank != bankGeneration) break
                val requested = priorityMidi
                val midi = if (remaining.remove(requested)) requested else remaining.removeAt(0)
                try {
                    val tone = Music.synthesize(midi, selection, models.forNote(selection, midi))
                    main.post {
                        if (!closed && bank == bankGeneration) {
                            samples[midi] = tone
                            startPending()
                        }
                    }
                } catch (error: Exception) {
                    Log.e("TonePlayer", "Could not synthesize MIDI note $midi", error)
                    main.post {
                        if (!closed && bank == bankGeneration && pending?.first == midi) {
                            stop()
                            onError()
                        }
                    }
                }
            }
        }
    }

    suspend fun playUntilStarted(midi: Int): Boolean {
        val started = CompletableDeferred<Boolean>()
        play(midi, sustain = true) { started.complete(it) }
        return try { started.await() } finally {
            if (!started.isCompleted) stop()
        }
    }

    fun play(midi: Int, sustain: Boolean = false, onStarted: (Boolean) -> Unit = {}) {
        if (closed) { onStarted(false); return }
        stop()
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            onError()
            onStarted(false)
            return
        }
        priorityMidi = midi
        pending = Triple(midi, sustain, onStarted)
        startPending()
    }

    private fun startPending() {
        val (midi, sustain, onStarted) = pending ?: return
        val tone = samples[midi] ?: return
        pending = null
        try {
            val output = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(44100)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(tone.pcm.size * 2)
                .build()
            track = output
            check(output.state != AudioTrack.STATE_UNINITIALIZED)
            check(output.write(tone.pcm, 0, tone.pcm.size) == tone.pcm.size)
            // Percussion: hits at 0.5/1.5/2.5s, then loop the overlapping decays.
            // Other instruments: play the attack once, then loop only the sustain.
            check(output.setLoopPoints(tone.loopStart, tone.pcm.size, -1) == AudioTrack.SUCCESS)
            val request = generation
            output.setVolume(if (instrument.percussive) 1f else 0f)
            output.play()
            if (!instrument.percussive) {
                val startedAt = SystemClock.elapsedRealtime()
                val ramp = object : Runnable {
                    override fun run() {
                        if (closed || request != generation || track !== output) return
                        val volume = ((SystemClock.elapsedRealtime() - startedAt) / 1000f).coerceIn(0f, 1f)
                        output.setVolume(volume)
                        if (volume < 1f) main.postDelayed(this, 10) else fadeIn = null
                    }
                }
                fadeIn = ramp
                main.postDelayed(ramp, 10)
            }
            if (!sustain) main.postDelayed({ if (request == generation) stop() }, 3000)
            onStarted(true)
        } catch (error: Exception) {
            Log.e("TonePlayer", "Could not play MIDI note $midi", error)
            onStarted(false)
            stop()
            onError()
        }
    }

    fun stop() {
        fadeIn?.let { main.removeCallbacks(it) }
        fadeIn = null
        generation++
        pending?.third?.invoke(false)
        pending = null
        priorityMidi = -1
        track?.let { output ->
            if (output.playState == AudioTrack.PLAYSTATE_PLAYING) output.stop()
            output.release()
        }
        track = null
        manager.abandonAudioFocusRequest(focus)
    }

    override fun close() {
        stop()
        closed = true
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        samples.clear()
    }
}
