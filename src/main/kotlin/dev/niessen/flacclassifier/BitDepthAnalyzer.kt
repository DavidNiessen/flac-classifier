package dev.niessen.flacclassifier

import kotlin.math.log2
import kotlin.math.max

data class BitDepthReport(
    val declaredBitDepth: Int,
    val effectiveBitDepth: Int,
    val lsbEntropyBits: Double,
    val isZeroPadded: Boolean
)

object BitDepthAnalyzer {
    private const val SILENCE_THRESHOLD = 0.001f  // ~-60 dBFS

    fun analyze(
        samplesInt: IntArray,
        samplesFloat: FloatArray,
        declaredBitDepth: Int,
        config: ClassifierConfig = ClassifierConfig.loaded
    ): BitDepthReport {
        val stride = max(1, samplesInt.size / config.maxSampleCount)
        val subset = mutableListOf<Int>()
        var i = 0
        while (i < samplesInt.size && subset.size < config.maxSampleCount) {
            // Skip silent frames
            if (kotlin.math.abs(samplesFloat[i]) > SILENCE_THRESHOLD) {
                subset.add(samplesInt[i])
            }
            i += stride
        }

        if (subset.isEmpty()) {
            return BitDepthReport(declaredBitDepth, declaredBitDepth, 0.0, false)
        }

        // Zero-padding fast path (only meaningful for 24-bit declared)
        if (declaredBitDepth == 24) {
            val nonZeroLowByte = subset.count { (it and 0xFF) != 0 }
            val zeroPaddingRatio = 1.0 - nonZeroLowByte.toDouble() / subset.size
            if (zeroPaddingRatio > config.zeroPaddingThreshold) {
                return BitDepthReport(declaredBitDepth, 16, 0.0, true)
            }
        }

        // Shannon entropy of the low 8 bits
        val freq = IntArray(256)
        for (s in subset) freq[s and 0xFF]++
        val total = subset.size.toDouble()
        val entropy = freq.sumOf { count ->
            if (count == 0) 0.0
            else {
                val p = count / total
                -p * log2(p)
            }
        }

        // Effective bit depth: find lowest bit position with meaningful variance
        val lowestActiveBit = (0 until declaredBitDepth).firstOrNull { b ->
            val setCount = subset.count { s -> (s ushr b) and 1 == 1 }
            val ratio = setCount.toDouble() / subset.size
            ratio > 0.005 && ratio < 0.995
        } ?: 0
        val effectiveBitDepth = declaredBitDepth - lowestActiveBit

        return BitDepthReport(
            declaredBitDepth = declaredBitDepth,
            effectiveBitDepth = effectiveBitDepth,
            lsbEntropyBits = entropy,
            isZeroPadded = false
        )
    }
}
