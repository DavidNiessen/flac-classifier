package dev.niessen.flacclassifier

import java.io.File
import java.util.Properties

data class ClassifierConfig(
    // SpectralAnalyzer
    val cutoffThresholdDb: Float,
    val slopeBrickWallDb: Float,
    val slopeGradualDb: Float,
    val above22kRatioThreshold: Float,
    // BitDepthAnalyzer
    val maxSampleCount: Int,
    val zeroPaddingThreshold: Double,
    val lsbEntropyThreshold: Double,
    // ClassificationEngine
    val effectiveDepthThreshold: Int,
    val fullBandwidthCutoffHz: Double,
    val mp3CutoffRanges: List<ClosedRange<Double>>,
    val mp3BitrateLabels: List<String>,
    val aacCutoffRanges: List<ClosedRange<Double>>,
    val aacBitrateLabels: List<String>
) {
    companion object {
        val loaded: ClassifierConfig by lazy { load() }

        fun load(): ClassifierConfig {
            val props = Properties()
            // Always load bundled defaults from classpath first
            ClassifierConfig::class.java.getResourceAsStream("/classifier.properties")
                ?.use { props.load(it) }
                ?: error("Bundled classifier.properties not found on classpath")
            // Overlay with user config if present
            val userFiles = listOf(
                File(System.getProperty("user.home"), ".config/flac-classifier/config.properties"),
                File("flac-classifier.properties")
            )
            userFiles.firstOrNull { it.exists() }?.inputStream()?.use { props.load(it) }
            return fromProperties(props)
        }

        private fun fromProperties(props: Properties): ClassifierConfig {
            fun required(key: String) = props.getProperty(key)
                ?: error("Missing required property '$key' in classifier.properties")

            val rangeRegex = Regex("""(\d+(?:\.\d+)?)-(\d+(?:\.\d+)?)""")
            fun parseRanges(key: String): List<ClosedRange<Double>> {
                val raw = required(key)
                return raw.split(";").mapNotNull { token ->
                    rangeRegex.find(token.trim())?.let { m ->
                        val lo = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
                        val hi = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
                        lo..hi
                    }
                }.also { check(it.isNotEmpty()) { "Property '$key' produced no valid ranges: '$raw'" } }
            }

            fun parseLabels(key: String): List<String> {
                val raw = required(key)
                return raw.split(";").map { it.trim() }
                    .also { check(it.isNotEmpty()) { "Property '$key' produced no labels: '$raw'" } }
            }

            return ClassifierConfig(
                cutoffThresholdDb = required("spectral.cutoffThresholdDb").toFloat(),
                slopeBrickWallDb = required("spectral.slopeBrickWallDb").toFloat(),
                slopeGradualDb = required("spectral.slopeGradualDb").toFloat(),
                above22kRatioThreshold = required("spectral.above22kRatioThreshold").toFloat(),
                maxSampleCount = required("bitdepth.maxSampleCount").toInt(),
                zeroPaddingThreshold = required("bitdepth.zeroPaddingThreshold").toDouble(),
                lsbEntropyThreshold = required("bitdepth.lsbEntropyThreshold").toDouble(),
                effectiveDepthThreshold = required("classification.effectiveDepthThreshold").toInt(),
                fullBandwidthCutoffHz = required("classification.fullBandwidthCutoffHz").toDouble(),
                mp3CutoffRanges = parseRanges("classification.mp3CutoffRanges"),
                mp3BitrateLabels = parseLabels("classification.mp3BitrateLabels"),
                aacCutoffRanges = parseRanges("classification.aacCutoffRanges"),
                aacBitrateLabels = parseLabels("classification.aacBitrateLabels")
            )
        }
    }
}
