package dev.niessen.flacclassifier

import org.jflac.FLACDecoder
import org.jflac.PCMProcessor
import org.jflac.metadata.StreamInfo
import org.jflac.util.ByteData
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

data class StreamInfoSnapshot(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val totalSamples: Long
)

data class DecodedAudio(
    val streamInfo: StreamInfoSnapshot,
    val channelSamplesFloat: List<FloatArray>,
    val channelSamplesInt: List<IntArray>
)

fun decodeFlac(file: File): DecodedAudio = FileInputStream(file).use { decodeFlac(it) }

fun decodeFlac(input: InputStream): DecodedAudio {
    var streamInfo: StreamInfo? = null
    val pcmBuffer = mutableListOf<ByteArray>()
    var totalBytes = 0

    val processor = object : PCMProcessor {
        override fun processStreamInfo(info: StreamInfo) {
            streamInfo = info
        }

        override fun processPCM(data: ByteData) {
            val copy = data.data.copyOf(data.len)
            pcmBuffer.add(copy)
            totalBytes += data.len
        }
    }

    val decoder = FLACDecoder(input)
    decoder.addPCMProcessor(processor)
    decoder.decode()

    val info = streamInfo ?: error("No stream info found in FLAC file")
    val channels = info.channels
    val bitsPerSample = info.bitsPerSample
    val bytesPerSample = (bitsPerSample + 7) / 8
    val frameBytes = bytesPerSample * channels
    val totalSampleFrames = totalBytes / frameBytes

    val floatSamples = Array(channels) { FloatArray(totalSampleFrames) }
    val intSamples = Array(channels) { IntArray(totalSampleFrames) }

    var frameIndex = 0
    val combined = ByteArray(totalBytes)
    var offset = 0
    for (chunk in pcmBuffer) {
        chunk.copyInto(combined, offset)
        offset += chunk.size
    }

    var pos = 0
    while (pos + frameBytes <= totalBytes) {
        for (ch in 0 until channels) {
            val raw = when (bytesPerSample) {
                2 -> {
                    val lo = combined[pos].toInt() and 0xFF
                    val hi = combined[pos + 1].toInt()
                    (hi shl 8) or lo
                }
                3 -> {
                    val b0 = combined[pos].toInt() and 0xFF
                    val b1 = combined[pos + 1].toInt() and 0xFF
                    val b2 = combined[pos + 2].toInt()
                    var v = (b2 shl 16) or (b1 shl 8) or b0
                    if (v and 0x800000 != 0) v = v or 0xFF000000.toInt()
                    v
                }
                else -> {
                    val lo = combined[pos].toInt() and 0xFF
                    val hi = combined[pos + 1].toInt()
                    (hi shl 8) or lo
                }
            }
            intSamples[ch][frameIndex] = raw
            floatSamples[ch][frameIndex] = when (bytesPerSample) {
                3 -> raw / 8388608.0f
                else -> raw / 32768.0f
            }
            pos += bytesPerSample
        }
        frameIndex++
    }

    val actualFrames = frameIndex
    return DecodedAudio(
        streamInfo = StreamInfoSnapshot(
            sampleRate = info.sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            totalSamples = actualFrames.toLong()
        ),
        channelSamplesFloat = floatSamples.map { it.copyOf(actualFrames) },
        channelSamplesInt = intSamples.map { it.copyOf(actualFrames) }
    )
}
