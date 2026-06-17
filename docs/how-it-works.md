# How flac-classifier works

This document explains the detection pipeline in detail - what is measured, how it is measured, and how the measurements map to a classification.

## Overview

Every FLAC file carries a header that declares its sample rate, bit depth, and number of channels. These declarations cannot be trusted: any audio tool can write a 44.1 kHz / 16-bit WAV into a 96 kHz / 24-bit FLAC container without resampling or bit-depth conversion, producing a file that _looks_ hi-res but sounds identical to the original. Likewise, an MP3 can be decoded to PCM and re-encoded as FLAC. The FLAC container is lossless, but the content inside was already damaged before it arrived there.

The classifier detects these cases using two independent analyses:

1. **Spectral analysis** - where does the frequency content actually stop, and what shape does the cutoff have?
2. **Bit-depth analysis** - do the least-significant bits of the audio samples carry real information?

These two signals are fed into a heuristic decision tree that produces a `Classification` and a list of human-readable confidence notes.

---

## Step 1 - FLAC decoding (`FlacDecoder.kt`)

The file is decoded using [JFLAC](https://sourceforge.net/projects/jflac/) via the `FLACDecoder` class. JFLAC reads the compressed FLAC frames and delivers raw interleaved PCM bytes through a `PCMProcessor` callback.

The decoder accumulates all PCM chunks in memory, then de-interleaves them into per-channel arrays. Two representations are kept:

- **`channelSamplesFloat`** - normalised to the range `[-1.0, 1.0]`, used as input for FFT-based spectral analysis.
- **`channelSamplesInt`** - raw signed integers at full declared bit depth, used for bit-depth analysis.

### Byte-to-sample conversion

PCM bytes arrive in little-endian order:

| Bit depth | Bytes per sample | Conversion |
|-----------|-----------------|------------|
| 16-bit | 2 | `(b1 << 8) \| b0` - signed short; divide by 32 768 to normalise |
| 24-bit | 3 | `(b2 << 16) \| (b1 << 8) \| b0` with sign extension if bit 23 is set; divide by 8 388 608 to normalise |

For stereo, the PCM stream is interleaved as `[L, R, L, R, …]`. The de-interleaving loop steps through `bytesPerSample` bytes per channel per frame.

---

## Step 2 - Spectral analysis (`SpectralAnalyzer.kt`)

The spectral analyser answers two questions:

1. At what frequency does the audio content effectively stop?
2. What does that cutoff look like - a hard brick wall, a gentle slope, or a psychoacoustic fade?

### Short-Time Fourier Transform (STFT)

The audio is processed in overlapping frames using these parameters:

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| `FFT_SIZE` | 4096 samples | Frequency resolution of `sampleRate / FFT_SIZE` Hz per bin (~10.8 Hz at 44.1 kHz) |
| `HOP_SIZE` | 2048 samples (50% overlap) | Captures transient content that falls between frame boundaries |
| Window function | Hann | Suppresses spectral leakage - without it, a sharp low-pass filter in the source would produce ringing artefacts in the FFT output that blur the cutoff frequency |

Each frame is multiplied element-wise by the Hann window before the FFT is applied:

```
window[i] = sample[i] × 0.5 × (1 − cos(2π × i / (N − 1)))
```

The FFT is computed using [JTransforms](https://github.com/wendykierp/JTransforms) (`FloatFFT_1D.realForward`), which operates in-place and packs the output in a compact format:

| Index | Content |
|-------|---------|
| `a[0]` | DC component (bin 0, real only) |
| `a[1]` | Nyquist component (bin N/2, real only) |
| `a[2k]` | Real part of bin k, for k = 1 … N/2−1 |
| `a[2k+1]` | Imaginary part of bin k, for k = 1 … N/2−1 |

Power at each bin is `real² + imag²`.

### Averaged power spectrum

Power is accumulated across all frames and divided by the frame count to produce a stable averaged spectrum. This is important: any single frame might be a silence, a transient, or a narrow-band tone. Averaging across the whole file reveals the overall spectral envelope.

The spectrum is then converted to decibels relative to the peak bin:

```
powerDb[k] = 10 × log10(avgPower[k]) − 10 × log10(peakPower)
```

This gives 0 dB at the loudest bin and negative values everywhere else.

### Smoothing

A rolling median (radius 5 → 11-bin window) is applied to the dB spectrum before cutoff detection. This removes narrow spectral notches that are common in real music content (resonances, comb filtering) and would otherwise cause the cutoff detector to stop prematurely at a notch rather than at the true low-pass edge.

### Cutoff frequency detection

The smoothed spectrum is scanned from the Nyquist frequency downward. The first bin whose smoothed power exceeds −60 dBr is declared the cutoff bin. Everything above this bin is treated as "dead air".

The −60 dBr threshold means: if a frequency band is more than 60 dB below the loudest part of the spectrum, it is considered absent. For reference, 60 dB is roughly the dynamic range of a compressed pop recording; 90–96 dB covers a full 16-bit CD.

### Rolloff shape classification

The shape of the spectrum around the cutoff bin determines the codec fingerprint. A 10-bin window (≈1 kHz wide at 44.1 kHz) is taken on each side of the cutoff and the dB drop is measured:

```
slope = averageDb(bins above cutoff) − averageDb(bins below cutoff)
```

A more negative slope = a steeper cutoff.

| Slope | Energy cliff (ref band − near-cutoff, dB) | `RolloffShape` | What it indicates |
|-------|------------------------------------------|---------------|-------------------|
| `< −40 dB` | any | `BRICK_WALL` | Hard low-pass filter - characteristic of MP3 encoding |
| `−40 dB … −15 dB` | any | `GRADUAL` | Moderate rolloff - characteristic of AAC encoding |
| `> −15 dB` | `≥ 30 dB` (configurable) | `PSYCHOACOUSTIC` | Very gentle fade with a large energy cliff below the cutoff - characteristic of Ogg Vorbis encoding |
| `> −15 dB` | `< 30 dB` | `UNKNOWN` | Very gentle slope with no significant energy cliff - natural HF attenuation, not a codec-induced cutoff |

The distinction between `PSYCHOACOUSTIC` and `UNKNOWN` is controlled by `spectral.psychoacousticEnergyCliffDb` (default 30 dB). The cliff is measured as the average level of a reference band 2–4 kHz below the cutoff minus the average level of the window immediately before the cutoff. A codec boundary (e.g. Ogg Vorbis) leaves active content in the reference band while the psychoacoustic model attenuates the near-cutoff region — a large positive cliff. Natural HF attenuation declines gradually across that whole band, producing only a small cliff. When audio simply lacks high-frequency content (a common property of acoustic recordings, vinyl rips, and most real-world music), the cliff is below the threshold and the rolloff is reported as `UNKNOWN`.

### Above-22 kHz detection (hi-res files only)

For files with a sample rate above 44.1 kHz (e.g. 96 kHz, 192 kHz), the classifier checks whether the file contains genuine content above 22 kHz. The average power of all bins above 22 050 Hz is compared against the overall peak power:

```
hasContentAbove22kHz = (avgPowerAbove22k / peakPower) > 0.001
```

The 0.001 threshold corresponds to −30 dBr. A genuine 96 kHz recording will have instrument harmonics and room noise well within this range. An upsampled 44.1 kHz file will have only quantization noise above 22 kHz, typically 60–90 dB below the peak - far below the threshold.

### Multi-channel handling

Both channels of a stereo file are analysed independently. The classifier uses the most conservative result - the channel with the lower spectral cutoff. This prevents one near-silent channel from masking a problematic spectrum on the other.

---

## Step 3 - Bit-depth analysis (`BitDepthAnalyzer.kt`)

A file declared as 24-bit may contain only 16-bit audio that has been zero-padded or upsampled into a wider container. The bit-depth analyser measures how much genuine information is carried in the least-significant bits.

### Sampling strategy

Up to 500 000 samples are examined. For long files, the samples are drawn at regular stride intervals across the whole file. Silent samples (amplitude below −60 dBFS) are skipped, because silence always has zero LSBs regardless of the true bit depth.

### Zero-padding fast path

For 24-bit declared files, if more than 99% of samples have a low byte (`sample & 0xFF`) equal to zero, the file is immediately flagged as zero-padded 16-bit. This is the most common form of fake 24-bit audio - a converter writes a 16-bit sample into the upper 16 bits of a 24-bit word and leaves the lower 8 bits at zero.

### Shannon entropy of the low 8 bits

The frequency distribution of the low byte value (0–255) across the sample subset is used to compute Shannon entropy:

```
H = −Σ p(v) × log₂(p(v))   for v = 0…255
```

A uniform distribution gives maximum entropy of 8.0 bits. The expected entropy by bit depth type:

| Audio type | Typical LSB entropy |
|---|---|
| Genuine 24-bit | 6.5–8.0 bits |
| TPDF-dithered 16-bit padded to 24 | 1.5–3.0 bits |
| Zero-padded 16-bit | 0.0 bits |

The threshold of 4.0 bits cleanly separates genuine 24-bit from all padded variants, including dithered files.

### Effective bit depth

The effective bit depth is the number of bits that carry genuine information. It is found by scanning bit positions from LSB (bit 0) upward and finding the lowest bit position where more than 0.5% and less than 99.5% of samples have that bit set. Bits below this position are DC-biased or zero and carry no information.

```
effectiveBitDepth = declaredBitDepth − lowestActiveBit
```

Example: if bits 0–7 are always zero (zero-padded 16→24 bit), `lowestActiveBit = 8` and `effectiveBitDepth = 24 − 8 = 16`.

---

## Step 4 - Classification (`ClassificationEngine.kt`)

The classification engine applies a deterministic decision tree to the outputs of the spectral and bit-depth analysers. Rules are evaluated in priority order:

```
1. SR > 44100 Hz AND hasContentAbove22kHz == false
   → UPSAMPLED_FAKE (sample rate fraud)

2. Declared 24-bit AND (effectiveBitDepth ≤ 16 OR lsbEntropy < 4.0)
   → record "padded 16-bit" note, continue to codec detection

3. Rolloff == BRICK_WALL:
   cutoff 15 500–16 500 Hz  → MP3_TRANSCODE (~128 kbps)
   cutoff 18 500–19 500 Hz  → MP3_TRANSCODE (~192 kbps)
   cutoff 19 500–21 000 Hz  → MP3_TRANSCODE (~256–320 kbps)
   any other cutoff         → LOSSY_UNKNOWN

4. Rolloff == GRADUAL:
   cutoff 14 900–16 500 Hz  → AAC_TRANSCODE (~128 kbps)
   cutoff 17 800–19 400 Hz  → AAC_TRANSCODE (~192 kbps)
   any other cutoff         → LOSSY_UNKNOWN

5. Rolloff == PSYCHOACOUSTIC AND cutoff < 20 000 Hz
   → OGG_TRANSCODE
   (UNKNOWN rolloff falls through - see rolloff classification above)

6. cutoff ≥ 20 000 Hz (full bandwidth):
   SR > 44100 AND hasContentAbove22kHz AND effectiveBitDepth ≥ 20 → TRUE_HIRES
   SR > 44100 AND hasContentAbove22kHz AND effectiveBitDepth < 20  → UPSAMPLED_FAKE
   SR == 44100 AND bitsPerSample == 16                             → TRUE_CD
   SR == 44100 AND bitsPerSample == 24 AND effectiveBitDepth ≥ 20  → TRUE_HIRES
   SR == 44100 AND bitsPerSample == 24 AND isShallowBitDepth       → TRUE_CD (with note)

7. Fallback → UNCERTAIN
```

### MP3 cutoff frequencies

MP3 encoders apply a low-pass filter whose cutoff frequency is determined by the target bitrate. The LAME encoder (the most widely used) uses these cutoffs:

| Bitrate | Cutoff | Detection window |
|---------|--------|-----------------|
| 128 kbps | 16 000 Hz | 15 500–16 500 Hz |
| 192 kbps | 19 000 Hz | 18 500–19 500 Hz |
| 256 kbps | 20 000 Hz | 19 500–21 000 Hz |
| 320 kbps | 20 500 Hz | 19 500–21 000 Hz |

The ±500 Hz tolerance accounts for encoder version differences and the frequency resolution of the FFT.

### AAC cutoff frequencies

AAC encoders use psychoacoustic modelling to remove high-frequency content, producing a more gradual rolloff than MP3's hard filter. The FDK AAC encoder (used by Android and many desktop tools) uses:

| Bitrate | Approximate cutoff | Detection window |
|---------|-------------------|-----------------|
| 128 kbps | 15 700 Hz | 14 900–16 500 Hz |
| 192 kbps | 18 600 Hz | 17 800–19 400 Hz |

### Known limitations

- **VBR MP3**: Variable-bitrate MP3 uses different cutoff frequencies in different sections of the file. The averaged spectrum reflects the highest bitrate used (loudest passages), which is generally the correct behaviour.
- **High-bitrate AAC (256 kbps+)**: Apple AAC at 256 kbps and similar high-quality encoders preserve content to near-Nyquist and are not reliably detected as lossy.
- **Vinyl and cassette rips**: Analogue sources naturally roll off before 20 kHz. The energy cliff gate on the `PSYCHOACOUSTIC` shape (`spectral.psychoacousticEnergyCliffDb`) substantially reduces false `OGG_TRANSCODE` results for these files — when the HF region is already near the noise floor, the cliff is small and the rolloff shape is reported as `UNKNOWN`, causing the file to fall through to `UNCERTAIN`. Files that still produce a false positive can be tuned by raising `spectral.psychoacousticEnergyCliffDb` to a higher value (e.g. 35).
- **Very short files**: Files shorter than one FFT frame (~93 ms at 44.1 kHz) return `UNCERTAIN`.

---

## Data flow diagram

```
FLAC file / InputStream
        │
        ▼
   FlacDecoder
   ─────────────────────────────────────────────
   • JFLAC decodes compressed frames
   • Accumulates interleaved PCM bytes
   • De-interleaves into per-channel arrays
   • Produces: channelSamplesFloat, channelSamplesInt
        │
        ├──────────────────────────┐
        ▼                          ▼
 SpectralAnalyzer           BitDepthAnalyzer
 ──────────────────         ──────────────────
 • Hann window              • Skip silent samples
 • FFT (JTransforms)        • Zero-padding check
 • Average power spectrum   • LSB Shannon entropy
 • Smooth (rolling median)  • Effective bit depth
 • Find cutoff bin          Produces: BitDepthReport
 • Classify rolloff shape
 • Check above-22kHz
 Produces: SpectralReport
        │                          │
        └──────────┬───────────────┘
                   ▼
        ClassificationEngine
        ──────────────────────
        • Priority decision tree
        • Codec fingerprinting
        • Confidence notes
        Produces: AnalysisResult
```
