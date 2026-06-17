# flac-classifier

A Kotlin tool that analyses a FLAC file and determines whether the audio is genuinely lossless or a fake - a lossy file (MP3, AAC, Ogg Vorbis) that has been re-encoded into a FLAC container. It also detects upsampling fraud, where a standard CD-quality file has had its sample rate inflated to appear as hi-res.

## Classifications

| Label | Meaning |
|---|---|
| `TRUE_HIRES` | Genuine 24-bit hi-res with verified high-frequency content above 22 kHz |
| `TRUE_CD` | Genuine 16-bit / 44.1 kHz CD-quality lossless |
| `UPSAMPLED_FAKE` | Sample rate or bit depth has been inflated; no genuine content to back it up |
| `MP3_TRANSCODE` | Brick-wall spectral cutoff consistent with MP3 encoding |
| `AAC_TRANSCODE` | Gradual high-frequency rolloff consistent with AAC encoding |
| `OGG_TRANSCODE` | Psychoacoustic gentle rolloff consistent with Ogg Vorbis encoding |
| `LOSSY_UNKNOWN` | Lossy transcode detected; codec could not be identified |
| `UNCERTAIN` | Insufficient evidence to classify |

## Usage

### CLI

Build the fat JAR once, then run it:

```bash
./gradlew shadowJar
java -jar build/libs/flac-classifier-1.0.0-SNAPSHOT-all.jar <file> [options]
```

For convenience you can alias it:

```bash
alias flac-classifier='java -jar /path/to/flac-classifier-1.0.0-SNAPSHOT-all.jar'
```

#### Synopsis

```
flac-classifier [-hV] [-v] [--json] <file>
```

#### Positional argument

| Argument | Description |
|---|---|
| `<file>` | Path to the FLAC file to analyse. Required. |

#### Options

| Option | Description |
|---|---|
| `--json` | Print results as a machine-readable JSON object instead of the default table. |
| `-v`, `--verbose` | Include the detailed confidence notes in the table output. Has no effect when combined with `--json` (notes are always included in JSON). |
| `-h`, `--help` | Print usage help and exit. |
| `-V`, `--version` | Print version (`flac-classifier 1.0.0`) and exit. |

#### Exit codes

| Code | Meaning |
|---|---|
| `0` | Analysis completed successfully. |
| `1` | File not found. |
| `2` | FLAC decoding error (corrupt or non-FLAC file). |

#### Default table output

```
┌──────────────────────────────────────────────────────────┐
│  flac-classifier - Audio Authenticity Report             │
├──────────────────────────────────────────────────────────┤
│  File           : /music/track01.flac                    │
│  Sample Rate    : 44100 Hz                               │
│  Bit Depth      : 16-bit (declared)                      │
│  Channels       : 2 (Stereo)                             │
│  Duration       : 4:32                                   │
├──────────────────────────────────────────────────────────┤
│  Spectral Cutoff    : 16021 Hz                           │
│  Rolloff Shape      : BRICK_WALL                         │
│  Content Above 22kHz: N/A (44.1 kHz file)               │
│  Effective Bit Depth: 16 bits                            │
│  LSB Entropy        : 7.82 bits                          │
├──────────────────────────────────────────────────────────┤
│  CLASSIFICATION: MP3_TRANSCODE                           │
│  Brick-wall cutoff consistent with MP3 encoding          │
└──────────────────────────────────────────────────────────┘
```

With `--verbose` an additional section appears below the classification showing each confidence note (e.g. the measured cutoff window that matched an MP3 bitrate fingerprint).

#### JSON output (`--json`)

```json
{
  "filePath": "/music/track01.flac",
  "sampleRate": 44100,
  "bitsPerSample": 16,
  "channels": 2,
  "durationSeconds": 272.4,
  "spectralCutoffHz": 16021.0,
  "rolloffShape": "BRICK_WALL",
  "hasContentAbove22kHz": null,
  "effectiveBitDepth": 16,
  "lsbEntropyBits": 7.82,
  "classification": "MP3_TRANSCODE",
  "confidenceNotes": [
    "Brick-wall rolloff at 16021 Hz matches MP3 128 kbps fingerprint (window 15500–16500 Hz)"
  ]
}
```

`hasContentAbove22kHz` is `null` for 44.1 kHz files (the check is only meaningful for higher sample rates).

### Library (Spring / Kotlin)

Import from GitHub Packages or Maven Local (see [docs/library-api.md](docs/library-api.md)):

```kotlin
import dev.niessen.flacclassifier.FlacClassifier
import dev.niessen.flacclassifier.model.Classification
import java.io.File

// From a file
val result = FlacClassifier.analyze(File("track.flac"))

// From a stream (e.g. Spring multipart upload)
val result = FlacClassifier.analyze(multipartFile.inputStream, multipartFile.originalFilename ?: "upload")

println(result.classification)       // e.g. Classification.Mp3Transcode
println(result.spectralCutoffHz)     // e.g. 16021.0
println(result.confidenceNotes)      // human-readable reasoning
```

## Configuration

All classifier thresholds and codec fingerprints are defined in `src/main/resources/classifier.properties`, which is bundled inside the JAR and serves as the single source of defaults. No values are hardcoded — if you want to tune behaviour, override any subset of properties in a file outside the JAR.

### Override locations

The tool looks for an override file at the following paths in order, and uses the first one found:

1. `~/.config/flac-classifier/config.properties` — user-level overrides
2. `./flac-classifier.properties` — working-directory overrides

Only the properties you want to change need to appear in your override file; everything else falls back to the bundled defaults.

### Available properties

| Property | Default | Description |
|---|---|---|
| `spectral.cutoffThresholdDb` | `-60.0` | Noise-floor threshold (dB re. peak) for detecting a frequency cutoff |
| `spectral.slopeBrickWallDb` | `-40.0` | Slope below which a rolloff is classified as brick-wall (MP3-like) |
| `spectral.slopeGradualDb` | `-15.0` | Slope below which a rolloff is classified as gradual (AAC-like) |
| `spectral.above22kRatioThreshold` | `0.001` | Minimum power ratio above 22 kHz to count as genuine hi-res content |
| `bitdepth.maxSampleCount` | `500000` | Maximum samples analysed for bit-depth detection (performance knob) |
| `bitdepth.zeroPaddingThreshold` | `0.99` | LSB zero-fraction above which a 24-bit file is flagged as zero-padded |
| `bitdepth.lsbEntropyThreshold` | `4.0` | Minimum LSB Shannon entropy (bits) to classify a file as genuine 24-bit |
| `classification.effectiveDepthThreshold` | `20` | Minimum effective bit depth to classify a file as true hi-res |
| `classification.fullBandwidthCutoffHz` | `20000.0` | Frequency boundary between "lossy" and "full bandwidth" |
| `classification.mp3CutoffRanges` | `15500-16500;18500-19500;19500-21000` | MP3 encoder cutoff fingerprints (Hz), semicolon-separated `low-high` ranges |
| `classification.mp3BitrateLabels` | `~128 kbps;~192 kbps;~256-320 kbps` | Labels for each MP3 range (semicolon-separated, same order) |
| `classification.aacCutoffRanges` | `14900-16500;17800-19400` | AAC encoder cutoff fingerprints (Hz), semicolon-separated `low-high` ranges |
| `classification.aacBitrateLabels` | `~128 kbps;~192 kbps` | Labels for each AAC range (semicolon-separated, same order) |

### Library usage with a custom config

When using flac-classifier as a library you can construct and pass a `ClassifierConfig` directly, bypassing the properties file entirely:

```kotlin
val config = ClassifierConfig.load()                  // reads bundled defaults + user file
val result = FlacClassifier.analyze(File("track.flac"), config)
```

## Building

```bash
./gradlew test          # run unit tests
./gradlew shadowJar     # build CLI fat JAR  → build/libs/*-all.jar
./gradlew jar           # build library JAR  → build/libs/*.jar (no -all suffix)
./gradlew publishToMavenLocal  # install to ~/.m2 for local use
```

## How it works

See [docs/how-it-works.md](docs/how-it-works.md) for the full technical explanation of the detection pipeline.

## Project structure

```
src/main/kotlin/dev/niessen/flacclassifier/
├── FlacClassifier.kt        Public API facade
├── FlacDecoder.kt           JFLAC wrapper - FLAC → PCM samples
├── SpectralAnalyzer.kt      FFT pipeline - cutoff frequency + rolloff shape
├── BitDepthAnalyzer.kt      LSB entropy - effective bit depth
├── ClassificationEngine.kt  Heuristic decision tree → Classification
├── ClassifierConfig.kt      Configuration loader (reads classifier.properties)
├── Main.kt                  CLI entry point (picocli)
└── model/
    ├── Classification.kt    Sealed class with 8 subtypes
    └── AnalysisResult.kt    Output data class

src/main/resources/
└── classifier.properties    Bundled defaults for all thresholds and fingerprints
```

## Releasing

Push a `v*` tag to cut a release. The tag name becomes the published version.

```bash
git tag v1.2.0
git push origin v1.2.0
```

The `.github/workflows/publish.yml` pipeline runs tests first; if they pass it publishes `dev.niessen:flac-classifier:<version>` to GitHub Packages. See [docs/library-api.md](docs/library-api.md) for how to consume it.
