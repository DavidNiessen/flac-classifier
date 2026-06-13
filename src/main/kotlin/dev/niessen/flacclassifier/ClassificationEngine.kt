package dev.niessen.flacclassifier

import dev.niessen.flacclassifier.model.AnalysisResult
import dev.niessen.flacclassifier.model.Classification
import dev.niessen.flacclassifier.model.RolloffShape

object ClassificationEngine {
    private const val LSB_ENTROPY_THRESHOLD = 4.0
    private const val EFFECTIVE_DEPTH_THRESHOLD = 20

    // MP3 cutoff ranges by bitrate (Hz): [low, high]
    private val mp3CutoffRanges = listOf(
        15500.0..16500.0,  // ~128 kbps
        18500.0..19500.0,  // ~192 kbps
        19500.0..21000.0   // ~256–320 kbps
    )
    private val mp3BitrateLabels = listOf("~128 kbps", "~192 kbps", "~256–320 kbps")

    // AAC cutoff ranges by bitrate (Hz)
    private val aacCutoffRanges = listOf(
        14900.0..16500.0,  // ~128 kbps
        17800.0..19400.0   // ~192 kbps
    )
    private val aacBitrateLabels = listOf("~128 kbps", "~192 kbps")

    fun classify(
        info: StreamInfoSnapshot,
        spectral: SpectralReport,
        bitDepth: BitDepthReport
    ): Pair<Classification, List<String>> {
        val notes = mutableListOf<String>()

        // 1. Upsampling check
        if (info.sampleRate > 44100 && spectral.hasContentAbove22kHz == false) {
            notes.add("No energy above 22 kHz in a ${info.sampleRate} Hz file → upsampled")
            return Classification.UpsampledFake to notes
        }

        // 2. Bit depth authenticity note (continue, don't return yet - may also have codec cutoff)
        val isShallowBitDepth = info.bitsPerSample == 24 &&
                (bitDepth.effectiveBitDepth <= 16 || bitDepth.lsbEntropyBits < LSB_ENTROPY_THRESHOLD)
        if (isShallowBitDepth) {
            notes.add(
                "Declared 24-bit but effective depth=${bitDepth.effectiveBitDepth}, " +
                        "LSB entropy=${"%.2f".format(bitDepth.lsbEntropyBits)} bits → padded 16-bit"
            )
        }

        val cutoff = spectral.cutoffHz
        val shape = spectral.rolloffShape

        // 3. Brick-wall: MP3 fingerprinting
        if (shape == RolloffShape.BRICK_WALL && cutoff != null) {
            val mp3Index = mp3CutoffRanges.indexOfFirst { cutoff in it }
            if (mp3Index >= 0) {
                notes.add("Brick-wall cutoff at ${"%.0f".format(cutoff)} Hz → ${mp3BitrateLabels[mp3Index]} MP3")
                return Classification.Mp3Transcode to notes
            }
            notes.add("Brick-wall cutoff at ${"%.0f".format(cutoff)} Hz; codec not identified")
            return Classification.LossyUnknown to notes
        }

        // 4. Gradual rolloff: AAC fingerprinting
        if (shape == RolloffShape.GRADUAL && cutoff != null) {
            val aacIndex = aacCutoffRanges.indexOfFirst { cutoff in it }
            if (aacIndex >= 0) {
                notes.add("Gradual rolloff at ${"%.0f".format(cutoff)} Hz → ${aacBitrateLabels[aacIndex]} AAC")
                return Classification.AacTranscode to notes
            }
            notes.add("Gradual rolloff at ${"%.0f".format(cutoff)} Hz; consistent with AAC or similar")
            return Classification.LossyUnknown to notes
        }

        // 5. Psychoacoustic rolloff below 20 kHz: Ogg Vorbis
        if (shape == RolloffShape.PSYCHOACOUSTIC && cutoff != null && cutoff < 20000.0) {
            notes.add("Psychoacoustic rolloff at ${"%.0f".format(cutoff)} Hz → consistent with Ogg Vorbis")
            return Classification.OggTranscode to notes
        }

        // 6. Full-bandwidth determination
        if (cutoff == null || cutoff >= 20000.0) {
            if (info.sampleRate > 44100) {
                if (spectral.hasContentAbove22kHz == true) {
                    return if (bitDepth.effectiveBitDepth >= EFFECTIVE_DEPTH_THRESHOLD) {
                        notes.add("Genuine HF content above 22 kHz, effective bit depth=${bitDepth.effectiveBitDepth}")
                        Classification.TrueHiRes to notes
                    } else {
                        notes.add("High sample rate but effective bit depth only ${bitDepth.effectiveBitDepth} → padded")
                        Classification.UpsampledFake to notes
                    }
                }
            }
            if (info.sampleRate == 44100) {
                return when {
                    info.bitsPerSample == 16 -> {
                        notes.add("Full spectrum to Nyquist, genuine 16-bit")
                        Classification.TrueCdQuality to notes
                    }
                    info.bitsPerSample == 24 && bitDepth.effectiveBitDepth >= EFFECTIVE_DEPTH_THRESHOLD -> {
                        notes.add("Genuine 24-bit at 44.1 kHz (high-res master)")
                        Classification.TrueHiRes to notes
                    }
                    info.bitsPerSample == 24 && isShallowBitDepth -> {
                        // Padded 16-bit in a 24-bit container at 44.1kHz - CD quality with warning
                        notes.add("24-bit container but 16-bit effective depth; CD quality content")
                        Classification.TrueCdQuality to notes
                    }
                    else -> Classification.TrueCdQuality to notes
                }
            }
        }

        notes.add("Could not determine classification from available evidence")
        return Classification.Uncertain to notes
    }

    fun buildResult(
        filePath: String,
        info: StreamInfoSnapshot,
        spectral: SpectralReport,
        bitDepth: BitDepthReport
    ): AnalysisResult {
        val (classification, notes) = classify(info, spectral, bitDepth)
        return AnalysisResult(
            filePath = filePath,
            sampleRate = info.sampleRate,
            bitsPerSample = info.bitsPerSample,
            channels = info.channels,
            durationSeconds = info.totalSamples.toDouble() / info.sampleRate,
            spectralCutoffHz = spectral.cutoffHz,
            rolloffShape = spectral.rolloffShape,
            hasContentAbove22kHz = spectral.hasContentAbove22kHz,
            effectiveBitDepth = bitDepth.effectiveBitDepth,
            lsbEntropyBits = bitDepth.lsbEntropyBits,
            classification = classification,
            confidenceNotes = notes
        )
    }
}
