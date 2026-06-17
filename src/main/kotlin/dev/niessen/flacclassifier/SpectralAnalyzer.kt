package dev.niessen.flacclassifier

import dev.niessen.flacclassifier.model.RolloffShape
import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.*

data class SpectralReport(
    val cutoffHz: Double?,
    val rolloffShape: RolloffShape,
    val hasContentAbove22kHz: Boolean?
)

object SpectralAnalyzer {
    private const val FFT_SIZE = 4096
    private const val HOP_SIZE = FFT_SIZE / 2
    private const val SLOPE_WINDOW_BINS = 10

    private val hannWindow: FloatArray = FloatArray(FFT_SIZE) { i ->
        0.5f * (1f - cos(2f * PI.toFloat() * i / (FFT_SIZE - 1)))
    }

    fun analyze(samples: FloatArray, sampleRate: Int, config: ClassifierConfig = ClassifierConfig.loaded): SpectralReport {
        val numBins = FFT_SIZE / 2 + 1
        val accPower = FloatArray(numBins)
        var frameCount = 0
        val frame = FloatArray(FFT_SIZE)
        val fft = FloatFFT_1D(FFT_SIZE.toLong())

        var pos = 0
        while (pos + FFT_SIZE <= samples.size) {
            for (i in 0 until FFT_SIZE) {
                frame[i] = samples[pos + i] * hannWindow[i]
            }
            fft.realForward(frame)
            for (k in 0 until numBins) {
                accPower[k] += powerAtBin(frame, k)
            }
            frameCount++
            pos += HOP_SIZE
        }

        if (frameCount == 0) {
            return SpectralReport(null, RolloffShape.UNKNOWN, null)
        }

        val avgPower = FloatArray(numBins) { accPower[it] / frameCount }
        val peakPower = avgPower.max()
        if (peakPower <= 0f) {
            return SpectralReport(null, RolloffShape.UNKNOWN, null)
        }

        val powerDb = FloatArray(numBins) { k ->
            10f * log10(max(avgPower[k], 1e-12f)) - 10f * log10(peakPower)
        }
        val smoothed = rollingMedian(powerDb, radius = 5)

        val cutoffBin = findCutoffBin(smoothed, config.cutoffThresholdDb)
        val cutoffHz = if (cutoffBin >= 0) binToHz(cutoffBin, sampleRate) else null
        val rolloffShape = if (cutoffBin > SLOPE_WINDOW_BINS && cutoffBin < numBins - SLOPE_WINDOW_BINS) {
            classifyRolloff(powerDb, cutoffBin, numBins, config)
        } else RolloffShape.UNKNOWN

        val hasContentAbove22kHz = if (sampleRate > 44100) {
            checkAbove22kHz(avgPower, sampleRate, numBins, config.above22kRatioThreshold)
        } else null

        return SpectralReport(cutoffHz, rolloffShape, hasContentAbove22kHz)
    }

    private fun powerAtBin(fftData: FloatArray, k: Int): Float = when (k) {
        0 -> fftData[0] * fftData[0]
        FFT_SIZE / 2 -> fftData[1] * fftData[1]
        else -> fftData[2 * k] * fftData[2 * k] + fftData[2 * k + 1] * fftData[2 * k + 1]
    }

    private fun binToHz(bin: Int, sampleRate: Int): Double =
        bin.toDouble() * sampleRate / FFT_SIZE

    private fun hzToBin(hz: Double, sampleRate: Int): Int =
        (hz * FFT_SIZE / sampleRate).roundToInt()

    private fun findCutoffBin(smoothedDb: FloatArray, cutoffThresholdDb: Float): Int {
        for (k in smoothedDb.indices.reversed()) {
            if (smoothedDb[k] > cutoffThresholdDb) return k
        }
        return -1
    }

    private fun classifyRolloff(powerDb: FloatArray, cutoffBin: Int, numBins: Int, config: ClassifierConfig): RolloffShape {
        val belowStart = maxOf(0, cutoffBin - SLOPE_WINDOW_BINS)
        val aboveEnd = minOf(numBins - 1, cutoffBin + SLOPE_WINDOW_BINS)

        val belowDb = powerDb.slice(belowStart until cutoffBin).average().toFloat()
        val aboveDb = powerDb.slice((cutoffBin + 1)..aboveEnd).average().toFloat()
        val slope = aboveDb - belowDb

        return when {
            slope < config.slopeBrickWallDb -> RolloffShape.BRICK_WALL
            slope < config.slopeGradualDb -> RolloffShape.GRADUAL
            else -> RolloffShape.PSYCHOACOUSTIC
        }
    }

    private fun checkAbove22kHz(avgPower: FloatArray, sampleRate: Int, numBins: Int, above22kRatioThreshold: Float): Boolean {
        val bin22k = hzToBin(22050.0, sampleRate).coerceIn(0, numBins - 1)
        if (bin22k >= numBins - 1) return false

        val peakPower = avgPower.max()
        if (peakPower <= 0f) return false

        val abovePower = avgPower.slice(bin22k until numBins).average().toFloat()
        // Compare above-22kHz band against the overall peak: genuine HF content sits within ~30 dB of the peak
        return (abovePower / peakPower) > above22kRatioThreshold
    }

    fun analyzeMultiChannel(channels: List<FloatArray>, sampleRate: Int, config: ClassifierConfig = ClassifierConfig.loaded): SpectralReport {
        val reports = channels.map { analyze(it, sampleRate, config) }
        // Take the most conservative channel (lowest cutoff)
        return reports.minByOrNull { it.cutoffHz ?: Double.MAX_VALUE } ?: reports.first()
    }

    private fun rollingMedian(data: FloatArray, radius: Int): FloatArray {
        val result = FloatArray(data.size)
        val windowSize = 2 * radius + 1
        val window = FloatArray(windowSize)
        for (i in data.indices) {
            var count = 0
            for (j in (i - radius)..(i + radius)) {
                if (j in data.indices) window[count++] = data[j]
            }
            val sub = window.copyOf(count)
            sub.sort()
            result[i] = sub[count / 2]
        }
        return result
    }
}
