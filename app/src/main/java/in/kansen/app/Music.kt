package `in`.kansen.app

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

data class Scale(val label: String, val note: String, val midi: Int, val white: Boolean)
data class Swara(val name: String, val kind: String, val symbol: String)

enum class Instrument(val id: String, val label: Int) {
    HARMONIUM("harmonium", R.string.instrument_harmonium),
    PURE_TONE("pure_tone", R.string.instrument_pure_tone);

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
        val loopCycles = kotlin.math.round(target * 1.5).toInt()
        val loopFrames = kotlin.math.round(sampleRate * loopCycles / target).toInt()
        val actual = sampleRate * loopCycles / loopFrames
        val introFrames = 28665 // 650 ms before the sustain loop
        val count = introFrames + loopFrames
        val output = DoubleArray(count)
        val start = 0
        val stepSeconds = model?.stepSeconds ?: .02
        fun modelTime(t: Double, rate: Double) = if (t <= .03) t else .03 + (t - .03) * rate
        val envelopeBuffer = DoubleArray(count - start)
        fun envelope(values: DoubleArray, rate: Double = 1.0): DoubleArray {
            for (i in envelopeBuffer.indices) {
                val t = modelTime(i / sampleRate, rate)
                val position = (t / stepSeconds).coerceAtMost(values.lastIndex.toDouble())
                val left = position.toInt()
                val fraction = position - left
                val measured = values[left] * (1 - fraction) + values[(left + 1).coerceAtMost(values.lastIndex)] * fraction
                envelopeBuffer[i] = measured
            }
            return envelopeBuffer
        }
        val modes = if (instrument == Instrument.PURE_TONE) {
            listOf(SpectralMode(1.0, 0.0, doubleArrayOf(1.0)))
        } else requireNotNull(model) { "Missing instrument model: $instrument" }.modes
        for (mode in modes) {
            val hz = actual * mode.ratio
            if (hz >= 20000) continue
            val rate = 1.0
            val levels = envelope(mode.amplitudes, rate)
            val step = 2 * PI * hz / sampleRate
            var stepSin = sin(step)
            var stepCos = kotlin.math.cos(step)
            var oscillatorSin = sin(mode.phase)
            var oscillatorCos = kotlin.math.cos(mode.phase)
            var nextPhaseFrame = 0
            for (i in levels.indices) {
                if (mode.phases.isNotEmpty() && i >= nextPhaseFrame) {
                    val position = (modelTime(i / sampleRate, rate) / stepSeconds).coerceAtMost(mode.phases.lastIndex.toDouble())
                    val frame = position.toInt()
                    val fraction = position - frame
                    val phase0 = mode.phases[frame]
                    val phase1 = mode.phases[(frame + 1).coerceAtMost(mode.phases.lastIndex)]
                    val phase = step * i + phase0 * (1 - fraction) + phase1 * fraction
                    oscillatorSin = sin(phase)
                    oscillatorCos = kotlin.math.cos(phase)
                    val currentRate = if (i / sampleRate < .03) 1.0 else rate
                    val evolvingStep = step + (phase1 - phase0) * currentRate / (stepSeconds * sampleRate)
                    stepSin = sin(evolvingStep)
                    stepCos = kotlin.math.cos(evolvingStep)
                    val nextTime = (frame + 1) * stepSeconds
                    nextPhaseFrame = if (frame >= mode.phases.lastIndex) Int.MAX_VALUE else
                        kotlin.math.ceil((if (nextTime <= .03) nextTime else .03 + (nextTime - .03) / rate) * sampleRate).toInt()
                }
                output[start + i] += levels[i] * oscillatorSin
                val nextSin = oscillatorSin * stepCos + oscillatorCos * stepSin
                oscillatorCos = oscillatorCos * stepCos - oscillatorSin * stepSin
                oscillatorSin = nextSin
            }
        }
        // Fresh band-limited stochastic excitation, shaped by measured residual energy.
        // The repeating noise bed is circular; it contains no copied waveform samples.
        val random = kotlin.random.Random(midi * 31 + when (instrument) {
            Instrument.HARMONIUM -> 0
            Instrument.PURE_TONE -> 3
        })
        fun noiseBand(low: Double, high: Double): DoubleArray {
            val white = DoubleArray(loopFrames) { random.nextDouble(-1.0, 1.0) }
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
            val attack = (t / .015).coerceIn(0.0, 1.0)
            output[i] *= attack * attack * (3 - 2 * attack)
        }
        run {
            // Preserve measured timbre motion rather than flattening the sustain.
            // The two sections are an integer number of pitch periods apart;
            // overlap the end with the pre-loop lead-in to join both value and slope.
            val overlap = 11025
            for (i in 0 until overlap) {
                val fraction = i.toDouble() / overlap
                val mix = fraction * fraction * (3 - 2 * fraction)
                val end = count - overlap + i
                val beginning = introFrames - overlap + i
                output[end] = output[end] * (1 - mix) + output[beginning] * mix
            }
        }
        val rendered = output
        val peak = rendered.maxOf { kotlin.math.abs(it) }.coerceAtLeast(.001)
        val peakGain = .96 / peak
        val gain = if (instrument == Instrument.HARMONIUM) {
            // Peak-only normalization made the nearly sinusoidal upper reeds
            // much louder than the complex lower reeds. Balance sustained energy.
            var energy = 0.0
            for (i in introFrames until rendered.size) energy += rendered[i] * rendered[i]
            val rms = kotlin.math.sqrt(energy / (rendered.size - introFrames)).coerceAtLeast(.00001)
            minOf(peakGain, .24 / rms)
        } else peakGain
        return InstrumentTone(ShortArray(rendered.size) { pcmSample(rendered[it] * gain) },
            introFrames)
    }
}
