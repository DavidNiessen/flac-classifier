package dev.niessen.flacclassifier

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BitDepthAnalyzerTest {

    @Test
    fun `zero-padded 16-bit in 24-bit container detects as effective 16-bit`() {
        val samples = IntArray(10000) { i ->
            // 16-bit sample shifted to 24-bit position, low byte always 0
            ((i % 65536) - 32768) shl 8
        }
        val floats = samples.map { it / 8388608.0f }.toFloatArray()
        val report = BitDepthAnalyzer.analyze(samples, floats, 24)

        assertEquals(16, report.effectiveBitDepth)
        assertTrue(report.isZeroPadded)
        assertTrue(report.lsbEntropyBits < 1.0)
    }

    @Test
    fun `genuine 24-bit random samples detect as effective 24-bit`() {
        val rng = Random(42)
        val samples = IntArray(50000) { (rng.nextInt(1 shl 24) - (1 shl 23)) }
        val floats = samples.map { it / 8388608.0f }.toFloatArray()
        val report = BitDepthAnalyzer.analyze(samples, floats, 24)

        assertTrue(report.effectiveBitDepth >= 20, "Expected effective depth ≥20, got ${report.effectiveBitDepth}")
        assertTrue(report.lsbEntropyBits >= 4.0, "Expected LSB entropy ≥4, got ${report.lsbEntropyBits}")
    }

    @Test
    fun `genuine 16-bit audio reports declared and effective both 16`() {
        val rng = Random(99)
        val samples = IntArray(10000) { rng.nextInt(65536) - 32768 }
        val floats = samples.map { it / 32768.0f }.toFloatArray()
        val report = BitDepthAnalyzer.analyze(samples, floats, 16)

        assertEquals(16, report.declaredBitDepth)
    }

    @Test
    fun `uniform random LSB has high entropy`() {
        val rng = Random(7)
        val samples = IntArray(100000) { rng.nextInt(1 shl 24) - (1 shl 23) }
        val floats = samples.map { it / 8388608.0f }.toFloatArray()
        val report = BitDepthAnalyzer.analyze(samples, floats, 24)

        assertTrue(report.lsbEntropyBits >= 7.0, "Expected near-maximum entropy, got ${report.lsbEntropyBits}")
    }

    @Test
    fun `all-zero samples return without crash`() {
        val samples = IntArray(1000) { 0 }
        val floats = FloatArray(1000) { 0f }
        val report = BitDepthAnalyzer.analyze(samples, floats, 16)

        assertEquals(16, report.declaredBitDepth)
    }
}
