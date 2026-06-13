package dev.niessen.flacclassifier

import dev.niessen.flacclassifier.model.AnalysisResult
import java.io.File
import java.io.InputStream

object FlacClassifier {
    fun analyze(file: File): AnalysisResult = analyze(file.path, decodeFlac(file))

    fun analyze(stream: InputStream, filePath: String = "<stream>"): AnalysisResult =
        analyze(filePath, decodeFlac(stream))

    private fun analyze(filePath: String, audio: DecodedAudio): AnalysisResult {
        val spectral = SpectralAnalyzer.analyzeMultiChannel(
            audio.channelSamplesFloat,
            audio.streamInfo.sampleRate
        )
        // Take the worst (lowest entropy / shallowest effective depth) across all channels
        val bitDepth = audio.channelSamplesInt.indices
            .map { ch ->
                BitDepthAnalyzer.analyze(
                    samplesInt = audio.channelSamplesInt[ch],
                    samplesFloat = audio.channelSamplesFloat[ch],
                    declaredBitDepth = audio.streamInfo.bitsPerSample
                )
            }
            .minBy { it.effectiveBitDepth }

        return ClassificationEngine.buildResult(
            filePath = filePath,
            info = audio.streamInfo,
            spectral = spectral,
            bitDepth = bitDepth
        )
    }
}
