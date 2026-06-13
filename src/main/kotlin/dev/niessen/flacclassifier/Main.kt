package dev.niessen.flacclassifier

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.niessen.flacclassifier.model.AnalysisResult
import dev.niessen.flacclassifier.model.Classification
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File
import java.util.concurrent.Callable
import kotlin.system.exitProcess

@Command(
    name = "flac-classifier",
    mixinStandardHelpOptions = true,
    version = ["flac-classifier 1.0.0"],
    description = ["Analyzes a FLAC file and classifies its audio quality and authenticity."]
)
class FlacClassifierCommand : Callable<Int> {

    @Parameters(index = "0", description = ["Path to the FLAC file to analyze"])
    lateinit var inputFile: File

    @Option(names = ["--json"], description = ["Output results as JSON"])
    var jsonOutput: Boolean = false

    @Option(names = ["--verbose", "-v"], description = ["Print detailed diagnostic notes"])
    var verbose: Boolean = false

    override fun call(): Int {
        if (!inputFile.exists()) {
            System.err.println("Error: File not found: ${inputFile.path}")
            return 1
        }
        if (!inputFile.name.endsWith(".flac", ignoreCase = true)) {
            System.err.println("Warning: File does not have .flac extension: ${inputFile.name}")
        }

        val result = try {
            FlacClassifier.analyze(inputFile)
        } catch (e: Exception) {
            System.err.println("Error decoding FLAC: ${e.message}")
            return 2
        }

        if (jsonOutput) {
            printJson(result)
        } else {
            printTable(result, verbose)
        }

        return 0
    }

    private fun printJson(result: AnalysisResult) {
        val mapper = jacksonObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
        // Serialize classification as its label string
        val node = mapper.createObjectNode().apply {
            put("filePath", result.filePath)
            put("sampleRate", result.sampleRate)
            put("bitsPerSample", result.bitsPerSample)
            put("channels", result.channels)
            put("durationSeconds", result.durationSeconds)
            result.spectralCutoffHz?.let { put("spectralCutoffHz", it) } ?: putNull("spectralCutoffHz")
            put("rolloffShape", result.rolloffShape.name)
            result.hasContentAbove22kHz?.let { put("hasContentAbove22kHz", it) } ?: putNull("hasContentAbove22kHz")
            put("effectiveBitDepth", result.effectiveBitDepth)
            put("lsbEntropyBits", result.lsbEntropyBits)
            put("classification", result.classification.label)
            putArray("confidenceNotes").also { arr ->
                result.confidenceNotes.forEach { arr.add(it) }
            }
        }
        println(mapper.writeValueAsString(node))
    }

    private fun printTable(result: AnalysisResult, verbose: Boolean) {
        val width = 60
        val border = "─".repeat(width - 2)
        val durationStr = formatDuration(result.durationSeconds)
        val channelStr = when (result.channels) {
            1 -> "1 (Mono)"
            2 -> "2 (Stereo)"
            else -> "${result.channels}"
        }
        val cutoffStr = result.spectralCutoffHz?.let { "${"%.0f".format(it)} Hz" } ?: "N/A"
        val above22kStr = when (result.hasContentAbove22kHz) {
            true -> "YES"
            false -> "NO"
            null -> "N/A (44.1 kHz file)"
        }

        println("┌$border┐")
        println("│  flac-classifier - Audio Authenticity Report".padEnd(width - 1) + "│")
        println("├$border┤")
        println("│  File           : ${result.filePath.take(width - 22)}".padEnd(width - 1) + "│")
        println("│  Sample Rate    : ${result.sampleRate} Hz".padEnd(width - 1) + "│")
        println("│  Bit Depth      : ${result.bitsPerSample}-bit (declared)".padEnd(width - 1) + "│")
        println("│  Channels       : $channelStr".padEnd(width - 1) + "│")
        println("│  Duration       : $durationStr".padEnd(width - 1) + "│")
        println("├$border┤")
        println("│  Spectral Cutoff    : $cutoffStr".padEnd(width - 1) + "│")
        println("│  Rolloff Shape      : ${result.rolloffShape}".padEnd(width - 1) + "│")
        println("│  Content Above 22kHz: $above22kStr".padEnd(width - 1) + "│")
        println("│  Effective Bit Depth: ${result.effectiveBitDepth} bits".padEnd(width - 1) + "│")
        println("│  LSB Entropy        : ${"%.2f".format(result.lsbEntropyBits)} bits".padEnd(width - 1) + "│")
        println("├$border┤")
        println("│  CLASSIFICATION: ${result.classification.label}".padEnd(width - 1) + "│")
        println("│  ${result.classification.description}".take(width - 4).padEnd(width - 1) + "│")
        if (result.confidenceNotes.isNotEmpty() && verbose) {
            println("├$border┤")
            result.confidenceNotes.forEach { note ->
                println("│  • $note".take(width - 2).padEnd(width - 1) + "│")
            }
        }
        println("└$border┘")
    }

    private fun formatDuration(seconds: Double): String {
        val total = seconds.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

fun main(args: Array<String>) {
    val exitCode = CommandLine(FlacClassifierCommand()).execute(*args)
    exitProcess(exitCode)
}
