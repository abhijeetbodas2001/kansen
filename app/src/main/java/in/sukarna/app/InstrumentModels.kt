package `in`.sukarna.app

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

data class SpectralMode(val ratio: Double, val phase: Double, val amplitudes: DoubleArray, val phases: DoubleArray = doubleArrayOf())
data class InstrumentModel(
    val instrument: String,
    val midi: Int,
    val stepSeconds: Double,
    val modes: List<SpectralMode>,
    val noise: List<DoubleArray>,
    val noiseEdges: DoubleArray,
)

class InstrumentModels(assets: AssetManager) {
    private fun JSONArray.doubles() = DoubleArray(length()) { getDouble(it) }
    private val models: List<InstrumentModel>

    init {
        val array = JSONObject(assets.open("synthesis/instruments.json").bufferedReader().use { it.readText() })
            .getJSONArray("models")
        models = List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val modes = item.getJSONArray("modes")
            val noise = item.getJSONArray("noise")
            InstrumentModel(item.getString("instrument"), item.getInt("midi"), item.getDouble("stepSeconds"),
                List(modes.length()) { modeIndex ->
                    val mode = modes.getJSONObject(modeIndex)
                    SpectralMode(mode.getDouble("ratio"), mode.getDouble("phase"), mode.getJSONArray("amplitudes").doubles(),
                        mode.optJSONArray("phases")?.doubles() ?: doubleArrayOf())
                }, List(noise.length()) { noise.getJSONArray(it).doubles() }, item.getJSONArray("noiseEdges").doubles())
        }
    }

    fun forNote(instrument: Instrument, midi: Int): InstrumentModel? {
        if (instrument == Instrument.PURE_TONE) return null
        val matching = models.filter { it.instrument == instrument.id }.sortedBy { it.midi }
        val lower = matching.lastOrNull { it.midi <= midi } ?: matching.first()
        val upper = matching.firstOrNull { it.midi >= midi } ?: matching.last()
        if (lower === upper || instrument.percussive) return matching.minBy { abs(it.midi - midi) }
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
            SpectralMode(ratio, phase, levels(lo?.amplitudes, hi?.amplitudes))
        }
        return InstrumentModel(instrument.id, midi, lower.stepSeconds, modes,
            lower.noise.indices.map { levels(lower.noise[it], upper.noise[it]) }, lower.noiseEdges)
    }
}
