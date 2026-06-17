package dev.niessen.flacclassifier

import dev.niessen.flacclassifier.model.AnalysisResult
import java.io.File
import java.io.InputStream

object FlacClassifier {
    fun analyze(file: File, config: ClassifierConfig = ClassifierConfig.loaded): AnalysisResult =
        analyze(file.path, decodeFlac(file), config)

    fun analyze(stream: InputStream, filePath: String = "<stream>", config: ClassifierConfig = ClassifierConfig.loaded): AnalysisResult =
        analyze(filePath, decodeFlac(stream), config)

    private fun analyze(filePath: String, audio: DecodedAudio, config: ClassifierConfig): AnalysisResult {
        val spectral = SpectralAnalyzer.analyzeMultiChannel(
            audio.channelSamplesFloat,
            audio.streamInfo.sampleRate,
            config
        )
        // Take the worst (lowest entropy / shallowest effective depth) across all channels
        val bitDepth = audio.channelSamplesInt.indices
            .map { ch ->
                BitDepthAnalyzer.analyze(
                    samplesInt = audio.channelSamplesInt[ch],
                    samplesFloat = audio.channelSamplesFloat[ch],
                    declaredBitDepth = audio.streamInfo.bitsPerSample,
                    config = config
                )
            }
            .minBy { it.effectiveBitDepth }

        return ClassificationEngine.buildResult(
            filePath = filePath,
            info = audio.streamInfo,
            spectral = spectral,
            bitDepth = bitDepth,
            config = config
        )
    }
}
