# Using flac-classifier as a library

This document covers how to import and use flac-classifier as a Maven / Gradle dependency in another JVM project, including a Spring Boot server.

---

## Artefact coordinates

```
groupId:    dev.niessen
artifactId: flac-classifier
version:    1.0.0-SNAPSHOT
```

The `1.0.0-SNAPSHOT` version is rebuilt and republished to GitHub Packages on every push to `main`.

---

## Adding the dependency

### Maven

Add the GitHub Packages repository and the dependency to your `pom.xml`. GitHub Packages requires authentication even for read access, so you also need credentials in your local `~/.m2/settings.xml`.

**`pom.xml`**

```xml
<repositories>
  <repository>
    <id>github-flac-classifier</id>
    <url>https://maven.pkg.github.com/YOUR_GITHUB_USER/flac-classifier</url>
    <snapshots>
      <enabled>true</enabled>
    </snapshots>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>dev.niessen</groupId>
    <artifactId>flac-classifier</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </dependency>
</dependencies>
```

**`~/.m2/settings.xml`**

```xml
<settings>
  <servers>
    <server>
      <id>github-flac-classifier</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_PERSONAL_ACCESS_TOKEN</password>
    </server>
  </servers>
</settings>
```

The personal access token needs the `read:packages` scope. Create one at: **GitHub → Settings → Developer settings → Personal access tokens**.

### Gradle (Kotlin DSL)

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/YOUR_GITHUB_USER/flac-classifier")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("dev.niessen:flac-classifier:1.0.0-SNAPSHOT")
}
```

### Maven Local (development only)

If you are working on both this library and the consuming project at the same time, you can install directly to your local Maven cache without going through GitHub:

```bash
cd /path/to/flac-classifier
./gradlew publishToMavenLocal
```

Then in the consuming project, add `mavenLocal()` as the first repository:

```xml
<!-- Maven pom.xml -->
<repositories>
  <repository>
    <id>local</id>
    <url>file://${user.home}/.m2/repository</url>
  </repository>
</repositories>
```

---

## Public API

The library exposes two entry points: `FlacClassifier` for analysis and `ClassifierConfig` for optional tuning.

### Analysing a file

```kotlin
import dev.niessen.flacclassifier.FlacClassifier
import java.io.File

val result = FlacClassifier.analyze(File("/path/to/track.flac"))
```

### Analysing a stream

Useful for Spring Boot multipart file uploads or any scenario where the audio arrives as a stream rather than a file on disk.

```kotlin
import dev.niessen.flacclassifier.FlacClassifier

// With an optional display name for the filePath field in the result
val result = FlacClassifier.analyze(inputStream, "track.flac")

// Without a name (filePath will be "<stream>")
val result = FlacClassifier.analyze(inputStream)
```

### Using a custom configuration

By default, `FlacClassifier.analyze` loads its configuration from `~/.config/flac-classifier/config.properties` (or `./flac-classifier.properties`) and falls back to the bundled defaults. You can bypass this and pass a `ClassifierConfig` directly — useful in server environments where configuration is managed programmatically rather than via files on disk.

```kotlin
import dev.niessen.flacclassifier.ClassifierConfig
import dev.niessen.flacclassifier.FlacClassifier

// Load from the standard locations (same as the default behaviour)
val config = ClassifierConfig.load()
val result = FlacClassifier.analyze(File("track.flac"), config)

// Construct a config entirely in code (no file I/O)
val config = ClassifierConfig.load().copy(
    psychoacousticEnergyCliffDb = 35.0f,  // stricter Ogg detection (fewer false positives)
    lsbEntropyThreshold = 3.5             // looser 24-bit gate
)
val result = FlacClassifier.analyze(inputStream, "track.flac", config)
```

`ClassifierConfig` is a Kotlin data class, so `copy()` is available for targeted overrides without reconstructing the whole object.

**Important:** The stream is fully consumed and decoded into memory before analysis begins. For a typical 5-minute stereo 24-bit/96 kHz file this requires approximately 220 MB of heap. Ensure your Spring Boot application has sufficient heap configured (`-Xmx512m` or higher for large files).

### The `AnalysisResult`

```kotlin
data class AnalysisResult(
    val filePath: String,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val channels: Int,
    val durationSeconds: Double,
    val spectralCutoffHz: Double?,      // null if file is too short or silent
    val rolloffShape: RolloffShape,
    val hasContentAbove22kHz: Boolean?, // null for 44.1 kHz files
    val effectiveBitDepth: Int,
    val lsbEntropyBits: Double,
    val classification: Classification,
    val confidenceNotes: List<String>
)

enum class RolloffShape { BRICK_WALL, GRADUAL, PSYCHOACOUSTIC, NONE, UNKNOWN }
```

### The `Classification` sealed class

```kotlin
sealed class Classification(val label: String, val description: String) {
    object TrueHiRes     : Classification("TRUE_HIRES",     "...")
    object TrueCdQuality : Classification("TRUE_CD",        "...")
    object UpsampledFake : Classification("UPSAMPLED_FAKE", "...")
    object Mp3Transcode  : Classification("MP3_TRANSCODE",  "...")
    object AacTranscode  : Classification("AAC_TRANSCODE",  "...")
    object OggTranscode  : Classification("OGG_TRANSCODE",  "...")
    object LossyUnknown  : Classification("LOSSY_UNKNOWN",  "...")
    object Uncertain     : Classification("UNCERTAIN",      "...")
}
```

Use `is` checks or `when` expressions to branch on the result:

```kotlin
when (result.classification) {
    is Classification.TrueHiRes,
    is Classification.TrueCdQuality -> handleGenuine(result)
    is Classification.Mp3Transcode,
    is Classification.AacTranscode,
    is Classification.OggTranscode,
    is Classification.LossyUnknown  -> rejectFake(result)
    is Classification.UpsampledFake -> rejectUpsampled(result)
    is Classification.Uncertain     -> flagForManualReview(result)
}
```

---

## Spring Boot example

A minimal REST endpoint that accepts a FLAC upload and returns the classification:

```kotlin
@RestController
@RequestMapping("/api/audio")
class AudioClassificationController {

    @PostMapping("/classify", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun classify(@RequestParam("file") file: MultipartFile): ResponseEntity<ClassificationResponse> {
        if (file.isEmpty) return ResponseEntity.badRequest().build()

        val result = FlacClassifier.analyze(
            stream = file.inputStream,
            filePath = file.originalFilename ?: "upload"
        )

        return ResponseEntity.ok(
            ClassificationResponse(
                classification = result.classification.label,
                description = result.classification.description,
                spectralCutoffHz = result.spectralCutoffHz,
                effectiveBitDepth = result.effectiveBitDepth,
                lsbEntropyBits = result.lsbEntropyBits,
                notes = result.confidenceNotes
            )
        )
    }
}

data class ClassificationResponse(
    val classification: String,
    val description: String,
    val spectralCutoffHz: Double?,
    val effectiveBitDepth: Int,
    val lsbEntropyBits: Double,
    val notes: List<String>
)
```

### Async / coroutines

The `FlacClassifier.analyze` call is CPU-bound (FFT over the entire file). In a reactive or coroutines-based server, dispatch it to a thread pool rather than running it on the event loop:

```kotlin
// Kotlin coroutines
val result = withContext(Dispatchers.Default) {
    FlacClassifier.analyze(file.inputStream, file.originalFilename ?: "upload")
}

// Spring WebFlux (Reactor)
Mono.fromCallable { FlacClassifier.analyze(stream, name) }
    .subscribeOn(Schedulers.boundedElastic())
```

---

## Transitive dependencies

The library JAR pulls in the following dependencies transitively:

| Dependency | Version | Purpose |
|---|---|---|
| `org.jflac:jflac-codec` | 1.5.2 | FLAC decoding |
| `com.github.wendykierp:JTransforms` | 3.1 | FFT |
| `info.picocli:picocli` | 4.7.6 | CLI (not used by library consumers) |
| `com.fasterxml.jackson.module:jackson-module-kotlin` | 2.17.2 | JSON output (not used by library consumers) |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.0.21 | Kotlin runtime |

picocli and Jackson are included because the library and CLI share a single module. They are harmless when imported as a library - no code in the analysis pipeline references them. A future split into `flac-classifier-core` and `flac-classifier-cli` modules would eliminate them from the transitive set.
