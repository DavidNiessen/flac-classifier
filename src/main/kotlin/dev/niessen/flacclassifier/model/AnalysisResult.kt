package dev.niessen.flacclassifier.model

data class AnalysisResult(
    val filePath: String,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val channels: Int,
    val durationSeconds: Double,
    val spectralCutoffHz: Double?,
    val rolloffShape: RolloffShape,
    val hasContentAbove22kHz: Boolean?,
    val effectiveBitDepth: Int,
    val lsbEntropyBits: Double,
    val classification: Classification,
    val confidenceNotes: List<String>
)

enum class RolloffShape { BRICK_WALL, GRADUAL, PSYCHOACOUSTIC, NONE, UNKNOWN }
