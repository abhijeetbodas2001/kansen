package `in`.sukarna.app

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

data class Scale(val label: String, val note: String, val midi: Int, val white: Boolean)
data class Swara(val name: String, val kind: String, val symbol: String)

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

    // An integral number of periods makes the repeating buffer seamless.
    fun sustainedTone(midi: Int): ShortArray {
        val cycles = 256
        val count = kotlin.math.round(44100.0 * cycles / frequency(midi)).toInt()
        return ShortArray(count) { i ->
            val phase = 2 * PI * cycles * i / count
            val wave = (sin(phase) + 0.25 * sin(phase * 2) + 0.1 * sin(phase * 3)) / 1.35
            pcmSample(wave)
        }
    }

    // Harmonium-style equal temperament, not raga-specific shruti intonation.
    fun tone(midi: Int, sampleRate: Int = 44100): ShortArray {
        val count = (sampleRate * 1.15).toInt()
        val frequency = frequency(midi)
        return ShortArray(count) { i ->
            val t = i.toDouble() / sampleRate
            val attack = (t / 0.025).coerceAtMost(1.0)
            val release = ((count - 1 - i).toDouble() / (sampleRate * 0.18)).coerceAtMost(1.0)
            val phase = 2 * PI * frequency * t
            val wave = (sin(phase) + 0.25 * sin(phase * 2) + 0.1 * sin(phase * 3)) / 1.35
            pcmSample(wave * attack * release)
        }
    }
}
