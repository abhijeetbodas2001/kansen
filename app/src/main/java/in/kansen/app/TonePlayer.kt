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
    private var trackVolume = 0f
    private val releasing = mutableMapOf<AudioTrack, Runnable>()
    private var generation = 0
    private var pending: Triple<Int, Boolean, (Boolean) -> Unit>? = null
    @Volatile private var priorityMidi = -1
    @Volatile private var closed = false
    @Volatile private var bankGeneration = 0

    init {
        // Migrate an unavailable saved instrument to the default.
        preferences.edit().putString("instrument", instrument.id).apply()
        preload(instrument)
    }

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
                    val model = if (selection == Instrument.PURE_TONE) null else models.forNote(selection, midi)
                    val tone = Music.synthesize(midi, selection, model)
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

    fun playForTraining(midi: Int, onHeard: () -> Unit) {
        play(midi, sustain = true) { started ->
            if (started) {
                val request = generation
                main.postDelayed({
                    // Stops, replacement notes and navigation invalidate this hold.
                    if (request == generation && track?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        onHeard()
                    }
                }, Music.FADE_IN_MS)
            }
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
            // Play the attack once, then loop only the sustain.
            check(output.setLoopPoints(tone.loopStart, tone.pcm.size, -1) == AudioTrack.SUCCESS)
            val request = generation
            // The PCM contains the sample-accurate one-second fade-in.
            output.setVolume(1f)
            trackVolume = 1f
            output.play()
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
        generation++
        pending?.third?.invoke(false)
        pending = null
        priorityMidi = -1
        val output = track
        val volume = trackVolume
        track = null
        trackVolume = 0f
        if (output != null && output.playState == AudioTrack.PLAYSTATE_PLAYING && volume > 0f) {
            // A hard stop cuts the waveform at an arbitrary value and clicks.
            // Retire this track separately so a new key can start immediately.
            val startedAt = SystemClock.elapsedRealtime()
            val release = object : Runnable {
                override fun run() {
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    val remaining = (1f - elapsed / 20f).coerceIn(0f, 1f)
                    output.setVolume(volume * remaining * remaining)
                    if (elapsed < 24) {
                        main.postDelayed(this, 4)
                    } else {
                        output.stop()
                        output.release()
                        releasing.remove(output)
                        if (track == null && pending == null && releasing.isEmpty())
                            manager.abandonAudioFocusRequest(focus)
                    }
                }
            }
            releasing[output] = release
            main.post(release)
        } else {
            output?.release()
            if (releasing.isEmpty()) manager.abandonAudioFocusRequest(focus)
        }
    }

    override fun close() {
        stop()
        releasing.forEach { (output, release) ->
            main.removeCallbacks(release)
            output.release()
        }
        releasing.clear()
        manager.abandonAudioFocusRequest(focus)
        closed = true
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        samples.clear()
    }
}
