package dev.niessen.flacclassifier.model

sealed class Classification(val label: String, val description: String) {
    object TrueHiRes : Classification(
        "TRUE_HIRES",
        "Genuine 24-bit hi-res with verified HF content above 22 kHz"
    )
    object TrueCdQuality : Classification(
        "TRUE_CD",
        "Genuine 16-bit/44.1 kHz CD-quality lossless"
    )
    object UpsampledFake : Classification(
        "UPSAMPLED_FAKE",
        "Sample rate inflated; no genuine content above 22 kHz"
    )
    object Mp3Transcode : Classification(
        "MP3_TRANSCODE",
        "Brick-wall cutoff consistent with MP3 encoding"
    )
    object AacTranscode : Classification(
        "AAC_TRANSCODE",
        "Gradual high-frequency rolloff consistent with AAC encoding"
    )
    object OggTranscode : Classification(
        "OGG_TRANSCODE",
        "Psychoacoustic gentle rolloff consistent with Ogg Vorbis encoding"
    )
    object LossyUnknown : Classification(
        "LOSSY_UNKNOWN",
        "Lossy transcode detected; codec could not be identified"
    )
    object Uncertain : Classification(
        "UNCERTAIN",
        "Insufficient evidence to classify confidently"
    )

    override fun toString() = label
}
