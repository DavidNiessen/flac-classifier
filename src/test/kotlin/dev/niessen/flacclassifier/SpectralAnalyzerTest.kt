package dev.niessen.flacclassifier

import dev.niessen.flacclassifier.model.RolloffShape
import org.junit.jupiter.api.Test
import kotlin.math.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpectralAnalyzerTest {

    private val sampleRate = 44100
    private val durationSeconds = 5.0
    private val numSamples = (sampleRate * durationSeconds).toInt()

    /** Generates a multi-frequency signal containing energy from 20 Hz up to maxFreqHz. */
    private fun sineSweep(maxFreqHz: Int): FloatArray {
        val samples = FloatArray(numSamples)
        val numFreqs = 50
        for (f in 0 until numFreqs) {
            val freq = 20.0 + (maxFreqHz - 20.0) * f / (numFreqs - 1)
            for (i in samples.indices) {
                samples[i] += (sin(2.0 * PI * freq * i / sampleRate) / numFreqs).toFloat()
            }
        }
        return samples
    }

    /** Applies a simple FIR low-pass brick-wall filter using windowed-sinc. */
    private fun lowPassFilter(samples: FloatArray, cutoffHz: Double): FloatArray {
        val fc = cutoffHz / sampleRate
        val order = 512
        val half = order / 2
        val h = DoubleArray(order + 1) { i ->
            val n = i - half
            val sinc = if (n == 0) 2.0 * fc else sin(2.0 * PI * fc * n) / (PI * n)
            val window = 0.42 - 0.5 * cos(2.0 * PI * i / order) + 0.08 * cos(4.0 * PI * i / order)
            sinc * window
        }
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            var acc = 0.0
            for (k in 0..order) {
                val j = i - k + half
                if (j in samples.indices) acc += h[k] * samples[j]
            }
            out[i] = acc.toFloat()
        }
        return out
    }

    @Test
    fun `full spectrum signal detects cutoff near Nyquist and psychoacoustic shape`() {
        val samples = sineSweep(22000)
        val report = SpectralAnalyzer.analyze(samples, sampleRate)

        assertNotNull(report.cutoffHz)
        assertTrue(report.cutoffHz!! >= 20000.0, "Expected cutoff near Nyquist, got ${report.cutoffHz} Hz")
        assertTrue(
            report.rolloffShape in setOf(RolloffShape.PSYCHOACOUSTIC, RolloffShape.NONE, RolloffShape.UNKNOWN),
            "Expected natural rolloff, got ${report.rolloffShape}"
        )
    }

    @Test
    fun `16kHz brick-wall filtered signal detects cutoff at ~16kHz with BRICK_WALL shape`() {
        val sweep = sineSweep(22000)
        val filtered = lowPassFilter(sweep, 16000.0)
        val report = SpectralAnalyzer.analyze(filtered, sampleRate)

        assertNotNull(report.cutoffHz)
        val cutoff = report.cutoffHz!!
        assertTrue(cutoff in 14000.0..18000.0, "Expected cutoff ~16kHz, got $cutoff Hz")
        assertEquals(RolloffShape.BRICK_WALL, report.rolloffShape, "Expected BRICK_WALL, got ${report.rolloffShape}")
    }

    @Test
    fun `above22kHz check returns null for 44100 Hz sample rate`() {
        val samples = sineSweep(22000)
        val report = SpectralAnalyzer.analyze(samples, sampleRate)
        assertEquals(null, report.hasContentAbove22kHz)
    }

    @Test
    fun `above22kHz check returns true for genuine 96kHz signal with HF content`() {
        val sr96k = 96000
        val num = (sr96k * 2.0).toInt()
        val samples = FloatArray(num) { i ->
            // Include energy up to 40 kHz
            sin(2.0 * PI * 30000.0 * i / sr96k).toFloat() * 0.5f +
                    sin(2.0 * PI * 1000.0 * i / sr96k).toFloat() * 0.5f
        }
        val report = SpectralAnalyzer.analyze(samples, sr96k)
        assertEquals(true, report.hasContentAbove22kHz, "Expected HF content detected")
    }

    @Test
    fun `above22kHz check returns false for upsampled 44kHz content in 96kHz file`() {
        val sr96k = 96000
        val num = (sr96k * 2.0).toInt()
        // Only 1kHz tone → no content above 22kHz
        val samples = FloatArray(num) { i ->
            sin(2.0 * PI * 1000.0 * i / sr96k).toFloat()
        }
        val report = SpectralAnalyzer.analyze(samples, sr96k)
        assertEquals(false, report.hasContentAbove22kHz, "Expected no HF content above 22kHz")
    }
}
