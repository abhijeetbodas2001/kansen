package `in`.kansen.app

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

data class Scale(val label: String, val note: String, val midi: Int, val white: Boolean)
data class Swara(val name: String, val kind: String, val symbol: String)

enum class Instrument(val id: String, val label: Int, val percussive: Boolean = false) {
    HARMONIUM("harmonium", R.string.instrument_harmonium),
    FLUTE("flute", R.string.instrument_flute),
    SITAR("sitar", R.string.instrument_sitar, true),
    PURE_TONE("pure_tone", R.string.instrument_pure_tone),
    TABLA("tabla", R.string.instrument_tabla, true);

    companion object {
        fun fromId(id: String?): Instrument = entries.firstOrNull { it.id == id } ?: HARMONIUM
    }
}

data class InstrumentTone(val pcm: ShortArray, val loopStart: Int)

object Music {
    private const val amplitude = 1.0

    private fun pcmSample(value: Double): Short =
        (value * amplitude * Short.MAX_VALUE).toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    val scales = listOf(
        Scale("White 1", "C", 60, true), Scale("Black 1", "C♯", 61, false),
        Scale("White 2", "D", 62, true), Scale("Black 2", "D♯", 63, false),
        Scale("White 3", "E", 64, true), Scale("White 4", "F", 65, true),
        Scale("Black 3", "F♯", 66, false), Scale("White 5", "G", 67, true),
        Scale("Black 4", "G♯", 68, false), Scale("White 6", "A", 69, true),
        Scale("Black 5", "A♯", 70, false), Scale("White 7", "B", 71, true),
    )
    val swaras = listOf(
        Swara("Sa", "Achal", "S"), Swara("Re", "Komal", "r"),
        Swara("Re", "Shuddha", "R"), Swara("Ga", "Komal", "g"),
        Swara("Ga", "Shuddha", "G"), Swara("Ma", "Shuddha", "m"),
        Swara("Ma", "Tivra", "M"), Swara("Pa", "Achal", "P"),
        Swara("Dha", "Komal", "d"), Swara("Dha", "Shuddha", "D"),
        Swara("Ni", "Komal", "n"), Swara("Ni", "Shuddha", "N"),
        Swara("Sa", "Upper", "Ṡ"),
    )
    private val noteNames = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    fun frequency(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)
    fun pitchLabel(midi: Int): String = "${noteNames[midi % 12]}${midi / 12 - 1}"

    // Resynthesis from measured modal envelopes, not replay of reference recordings.
    // Sustained tones use harmonic oscillators to keep the training pitch stable.
    fun synthesize(midi: Int, instrument: Instrument, model: InstrumentModel? = null): InstrumentTone {
        val sampleRate = 44100.0
        val target = frequency(midi)
        val percussive = instrument.percussive
        val loopCycles = kotlin.math.round(target * 1.5).toInt()
        val loopFrames = if (percussive) 44100 else kotlin.math.round(sampleRate * loopCycles / target).toInt()
        val actual = if (percussive) target else sampleRate * loopCycles / loopFrames
        val introFrames = if (percussive) 0 else loopFrames
        val count = if (percussive) 88200 else introFrames + loopFrames
        val output = DoubleArray(count)
        val start = if (percussive) introFrames else 0
        val stepSeconds = model?.stepSeconds ?: .02
        val envelopeBuffer = DoubleArray(count - start)
        fun envelope(values: DoubleArray): DoubleArray {
            val sustainStart = (1.5 / stepSeconds).toInt().coerceAtMost(values.lastIndex)
            val sustainEnd = (2.4 / stepSeconds).toInt().coerceAtMost(values.lastIndex)
            val sustain = (sustainStart..sustainEnd).map { values[it] }.average()
            for (i in envelopeBuffer.indices) {
                val t = i / sampleRate
                val position = (t / stepSeconds).coerceAtMost(values.lastIndex.toDouble())
                val left = position.toInt()
                val fraction = position - left
                val measured = values[left] * (1 - fraction) + values[(left + 1).coerceAtMost(values.lastIndex)] * fraction
                envelopeBuffer[i] = if (percussive) measured else {
                    val blend = ((t - 1.0) / .35).coerceIn(0.0, 1.0)
                    val smooth = blend * blend * (3 - 2 * blend)
                    measured * (1 - smooth) + sustain * smooth
                }
            }
            return envelopeBuffer
        }
        val modes = if (instrument == Instrument.PURE_TONE) {
            listOf(SpectralMode(1.0, 0.0, doubleArrayOf(1.0)))
        } else requireNotNull(model) { "Missing instrument model: $instrument" }.modes
        for (mode in modes) {
            val hz = actual * mode.ratio
            if (hz >= 20000) continue
            val levels = envelope(mode.amplitudes)
            val step = 2 * PI * hz / sampleRate
            var stepSin = sin(step)
            var stepCos = kotlin.math.cos(step)
            var oscillatorSin = sin(mode.phase)
            var oscillatorCos = kotlin.math.cos(mode.phase)
            var nextPhaseFrame = 0
            for (i in levels.indices) {
                if (mode.phases.isNotEmpty() && i >= nextPhaseFrame) {
                    val position = (i / sampleRate / stepSeconds).coerceAtMost(mode.phases.lastIndex.toDouble())
                    val frame = position.toInt()
                    val fraction = position - frame
                    val phase0 = mode.phases[frame]
                    val phase1 = mode.phases[(frame + 1).coerceAtMost(mode.phases.lastIndex)]
                    val phase = step * i + phase0 * (1 - fraction) + phase1 * fraction
                    oscillatorSin = sin(phase)
                    oscillatorCos = kotlin.math.cos(phase)
                    val evolvingStep = step + (phase1 - phase0) / (stepSeconds * sampleRate)
                    stepSin = sin(evolvingStep)
                    stepCos = kotlin.math.cos(evolvingStep)
                    nextPhaseFrame = kotlin.math.ceil((frame + 1) * stepSeconds * sampleRate).toInt()
                }
                output[start + i] += levels[i] * oscillatorSin
                val nextSin = oscillatorSin * stepCos + oscillatorCos * stepSin
                oscillatorCos = oscillatorCos * stepCos - oscillatorSin * stepSin
                oscillatorSin = nextSin
            }
        }
        // Fresh band-limited stochastic excitation, shaped by measured residual energy.
        // The repeating noise bed is circular; it contains no copied waveform samples.
        val random = kotlin.random.Random(midi * 31 + instrument.ordinal)
        val white = DoubleArray(loopFrames) { random.nextDouble(-1.0, 1.0) }
        fun noiseBand(low: Double, high: Double): DoubleArray {
            // A constant-Q biquad follows each measured residual band without
            // the broad overlap of the former low-pass difference filters.
            val center = kotlin.math.sqrt(low * high)
            val q = center / (high - low)
            val omega = 2 * PI * center / sampleRate
            val alpha = sin(omega) / (2 * q)
            val a0 = 1 + alpha
            val b0 = alpha / a0
            val a1 = -2 * kotlin.math.cos(omega) / a0
            val a2 = (1 - alpha) / a0
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            val result = DoubleArray(loopFrames)
            repeat(2) { pass ->
                for (i in white.indices) {
                    val y = b0 * white[i] - b0 * x2 - a1 * y1 - a2 * y2
                    x2 = x1; x1 = white[i]; y2 = y1; y1 = y
                    if (pass == 1) result[i] = y
                }
            }
            return result
        }
        if (model != null) {
            model.noise.forEachIndexed { band, amplitudes ->
                val noise = noiseBand(model.noiseEdges[band], model.noiseEdges[band + 1])
                val rms = kotlin.math.sqrt(noise.sumOf { it * it } / loopFrames).coerceAtLeast(.00001)
                val levels = envelope(amplitudes)
                for (i in levels.indices) output[start + i] += levels[i] * noise[i % loopFrames] / rms
            }
        }
        for (i in start until count) {
            val t = (i - start) / sampleRate
            val attack = (t / (if (percussive) .002 else .015)).coerceIn(0.0, 1.0)
            val release = if (percussive) ((2.0 - t) / .08).coerceIn(0.0, 1.0) else 1.0
            output[i] *= attack * attack * (3 - 2 * attack) * release * release * (3 - 2 * release)
        }
        val rendered = if (percussive) {
            // Let the previous stroke ring underneath the next, rather than cut
            // every decay at one second. Loop the established two-stroke overlap.
            DoubleArray(154350).also { sequence ->
                for (hit in 0..2) {
                    val offset = 22050 + hit * 44100
                    for (i in output.indices) {
                        if (offset + i >= sequence.size) break
                        sequence[offset + i] += output[i]
                    }
                }
            }
        } else output
        val peak = rendered.maxOf { kotlin.math.abs(it) }.coerceAtLeast(.001)
        return InstrumentTone(ShortArray(rendered.size) { pcmSample(rendered[it] / peak * .96) },
            if (percussive) 110250 else introFrames)
    }
}
