package `in`.kansen.app

import android.content.res.AssetManager
import java.io.BufferedInputStream
import java.io.DataInputStream
import kotlin.math.abs

data class SpectralMode(
    val ratio: Double, val phase: Double, val amplitudes: DoubleArray,
    val phases: DoubleArray = doubleArrayOf(), val pitchTracking: Double = 1.0,
)
data class InstrumentModel(
    val instrument: String,
    val midi: Int,
    val stepSeconds: Double,
    val modes: List<SpectralMode>,
    val noise: List<DoubleArray>,
    val noiseEdges: DoubleArray,
    val referenceHz: Double = Music.frequency(midi),
)

class InstrumentModels(assets: AssetManager) {
    private fun DataInputStream.doubles(): DoubleArray {
        val count = readInt()
        require(count in 0..20000) { "Invalid envelope length" }
        return DoubleArray(count) { readFloat().toDouble() }
    }
    private val models: List<InstrumentModel>

    init {
        // Stream compact numeric parameters directly into arrays. A JSON tree
        // temporarily multiplied the memory needed by the larger register bank.
        models = DataInputStream(BufferedInputStream(assets.open("synthesis/instruments.bin"))).use { input ->
            check(input.readInt() == 0x4b534e34) { "Invalid instrument bank" }
            val count = input.readInt()
            require(count in 1..128)
            List(count) {
                val instrument = input.readUTF()
                val midi = input.readInt()
                val referenceHz = input.readDouble()
                val stepSeconds = input.readDouble()
                val modeCount = input.readInt()
                require(modeCount in 1..256)
                val modes = List(modeCount) {
                    val ratio = input.readDouble()
                    val phase = input.readDouble()
                    val tracking = input.readDouble()
                    SpectralMode(ratio, phase, input.doubles(), input.doubles(), tracking)
                }
                val bandCount = input.readInt()
                require(bandCount in 1..128)
                val noise = List(bandCount) { input.doubles() }
                val edges = input.doubles()
                check(edges.size == bandCount + 1)
                InstrumentModel(instrument, midi, stepSeconds, modes, noise, edges, referenceHz)
            }
        }
    }

    fun forNote(instrument: Instrument, midi: Int): InstrumentModel? {
        if (instrument == Instrument.PURE_TONE) return null
        val matching = models.filter { it.instrument == instrument.id }.sortedBy { it.midi }
        val lower = matching.lastOrNull { it.midi <= midi } ?: matching.first()
        val upper = matching.firstOrNull { it.midi >= midi } ?: matching.last()
        if (lower === upper) return lower
        val blend = (midi - lower.midi).toDouble() / (upper.midi - lower.midi)
        fun levels(a: DoubleArray?, b: DoubleArray?): DoubleArray {
            val size = maxOf(a?.size ?: 0, b?.size ?: 0)
            return DoubleArray(size) { i ->
                val x = a?.getOrElse(i) { a.last() } ?: 0.0
                val y = b?.getOrElse(i) { b.last() } ?: 0.0
                kotlin.math.sqrt((1 - blend) * x * x + blend * y * y)
            }
        }
        val lowModes = lower.modes.associateBy { it.ratio }
        val highModes = upper.modes.associateBy { it.ratio }
        val modes = (lowModes.keys + highModes.keys).sorted().map { ratio ->
            val lo = lowModes[ratio]
            val hi = highModes[ratio]
            val lowPhase = lo?.phase ?: hi!!.phase
            val highPhase = hi?.phase ?: lowPhase
            val phase = kotlin.math.atan2((1 - blend) * kotlin.math.sin(lowPhase) + blend * kotlin.math.sin(highPhase),
                (1 - blend) * kotlin.math.cos(lowPhase) + blend * kotlin.math.cos(highPhase))
            val phaseCount = maxOf(lo?.phases?.size ?: 0, hi?.phases?.size ?: 0)
            val phases = DoubleArray(phaseCount)
            val lowTrack = lo?.phases?.takeIf { it.isNotEmpty() } ?: hi?.phases
            val highTrack = hi?.phases?.takeIf { it.isNotEmpty() } ?: lowTrack
            val lowStart = lowTrack?.firstOrNull() ?: lowPhase
            val highStart = highTrack?.firstOrNull() ?: highPhase
            val start = kotlin.math.atan2((1 - blend) * kotlin.math.sin(lowStart) + blend * kotlin.math.sin(highStart),
                (1 - blend) * kotlin.math.cos(lowStart) + blend * kotlin.math.cos(highStart))
            for (i in phases.indices) {
                // Blend unwrapped motion, avoiding phase flips when two profiles
                // cross opposite angles during a sustained note.
                val a = lowTrack?.getOrNull(i) ?: lowStart
                val b = highTrack?.getOrNull(i) ?: highStart
                phases[i] = start + (1 - blend) * (a - lowStart) + blend * (b - highStart)
            }
            SpectralMode(ratio, phase, levels(lo?.amplitudes, hi?.amplitudes), phases)
        }
        return InstrumentModel(instrument.id, midi, lower.stepSeconds, modes,
            lower.noise.indices.map { levels(lower.noise[it], upper.noise[it]) }, lower.noiseEdges)
    }
}
