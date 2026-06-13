package dev.niessen.flacclassifier

import dev.niessen.flacclassifier.model.Classification
import dev.niessen.flacclassifier.model.RolloffShape
import org.junit.jupiter.api.Test
import kotlin.test.assertIs

class ClassificationEngineTest {

    private fun info(sampleRate: Int = 44100, bits: Int = 16, channels: Int = 2, total: Long = 44100 * 60L) =
        StreamInfoSnapshot(sampleRate, channels, bits, total)

    private fun spectral(
        cutoffHz: Double? = 22000.0,
        shape: RolloffShape = RolloffShape.PSYCHOACOUSTIC,
        above22k: Boolean? = null
    ) = SpectralReport(cutoffHz, shape, above22k)

    private fun bitDepth(declared: Int = 16, effective: Int = 16, entropy: Double = 7.5, padded: Boolean = false) =
        BitDepthReport(declared, effective, entropy, padded)

    @Test
    fun `true CD quality - 16bit 44100 full spectrum`() {
        val (c, _) = ClassificationEngine.classify(info(), spectral(), bitDepth())
        assertIs<Classification.TrueCdQuality>(c)
    }

    @Test
    fun `true hires - 24bit 96kHz with genuine HF content`() {
        val (c, _) = ClassificationEngine.classify(
            info(sampleRate = 96000, bits = 24),
            spectral(cutoffHz = 40000.0, shape = RolloffShape.PSYCHOACOUSTIC, above22k = true),
            bitDepth(declared = 24, effective = 24, entropy = 7.8)
        )
        assertIs<Classification.TrueHiRes>(c)
    }

    @Test
    fun `upsampled fake - 96kHz no HF content`() {
        val (c, _) = ClassificationEngine.classify(
            info(sampleRate = 96000, bits = 24),
            spectral(cutoffHz = 22000.0, shape = RolloffShape.PSYCHOACOUSTIC, above22k = false),
            bitDepth(declared = 24, effective = 24, entropy = 7.5)
        )
        assertIs<Classification.UpsampledFake>(c)
    }

    @Test
    fun `MP3 128kbps transcode - brick wall at 16kHz`() {
        val (c, _) = ClassificationEngine.classify(
            info(),
            spectral(cutoffHz = 16000.0, shape = RolloffShape.BRICK_WALL),
            bitDepth()
        )
        assertIs<Classification.Mp3Transcode>(c)
    }

    @Test
    fun `MP3 192kbps transcode - brick wall at 19kHz`() {
        val (c, _) = ClassificationEngine.classify(
            info(),
            spectral(cutoffHz = 19000.0, shape = RolloffShape.BRICK_WALL),
            bitDepth()
        )
        assertIs<Classification.Mp3Transcode>(c)
    }

    @Test
    fun `MP3 320kbps transcode - brick wall at 20500Hz`() {
        val (c, _) = ClassificationEngine.classify(
            info(),
            spectral(cutoffHz = 20500.0, shape = RolloffShape.BRICK_WALL),
            bitDepth()
        )
        assertIs<Classification.Mp3Transcode>(c)
    }

    @Test
    fun `AAC 128kbps transcode - gradual rolloff at 15500Hz`() {
        val (c, _) = ClassificationEngine.classify(
            info(),
            spectral(cutoffHz = 15500.0, shape = RolloffShape.GRADUAL),
            bitDepth()
        )
        assertIs<Classification.AacTranscode>(c)
    }

    @Test
    fun `AAC 192kbps transcode - gradual rolloff at 18500Hz`() {
        val (c, _) = ClassificationEngine.classify(
            info(),
            spectral(cutoffHz = 18500.0, shape = RolloffShape.GRADUAL),
            bitDepth()
        )
        assertIs<Classification.AacTranscode>(c)
    }

    @Test
    fun `Ogg Vorbis transcode - psychoacoustic rolloff below 20kHz`() {
        val (c, _) = ClassificationEngine.classify(
            info(),
            spectral(cutoffHz = 17000.0, shape = RolloffShape.PSYCHOACOUSTIC),
            bitDepth()
        )
        assertIs<Classification.OggTranscode>(c)
    }

    @Test
    fun `lossy unknown - brick wall at unrecognised frequency`() {
        val (c, _) = ClassificationEngine.classify(
            info(),
            spectral(cutoffHz = 12000.0, shape = RolloffShape.BRICK_WALL),
            bitDepth()
        )
        assertIs<Classification.LossyUnknown>(c)
    }

    @Test
    fun `padded 16bit in 24bit container - 44100Hz becomes CD quality with note`() {
        val (c, notes) = ClassificationEngine.classify(
            info(bits = 24),
            spectral(cutoffHz = 22000.0, shape = RolloffShape.PSYCHOACOUSTIC),
            bitDepth(declared = 24, effective = 16, entropy = 0.5, padded = true)
        )
        assertIs<Classification.TrueCdQuality>(c)
        assert(notes.any { it.contains("padded") })
    }
}
