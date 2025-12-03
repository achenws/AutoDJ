package com.ece420.lab1.dsp;

import org.jtransforms.fft.FloatFFT_1D;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility functions for digital signal processing operations.
 * Used by time stretching and crossfade algorithms.
 */
public class DSPUtils {

    // FFT instance cache to avoid creating thousands of instances in loops
    private static final Map<Integer, FloatFFT_1D> fftCache = new HashMap<>();

    /**
     * Get a cached FFT instance for the given size.
     * Reuses existing instances to avoid allocation overhead.
     *
     * @param size FFT size (should be power of 2)
     * @return Cached FloatFFT_1D instance
     */
    public static synchronized FloatFFT_1D getFFT(int size) {
        FloatFFT_1D fft = fftCache.get(size);
        if (fft == null) {
            fft = new FloatFFT_1D(size);
            fftCache.put(size, fft);
        }
        return fft;
    }

    /**
     * Clear FFT cache to free memory if needed.
     */
    public static synchronized void clearFFTCache() {
        fftCache.clear();
    }

    /**
     * Compute normalized cross-correlation between two equal-length signals.
     * corr = dot(a, b) / (norm(a) * norm(b))
     *
     * @param a First signal array
     * @param b Second signal array
     * @return Correlation coefficient in range [-1, 1]
     */
    public static float normalizedCorrelation(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Arrays must be same length");
        }

        float dotProduct = 0;
        float normA = 0;
        float normB = 0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        float denom = (float) (Math.sqrt(normA) * Math.sqrt(normB));
        if (denom < 1e-10f) {
            return 0;  // Avoid division by zero
        }

        return dotProduct / denom;
    }

    /**
     * Convert musical bars to samples (assuming 4/4 time).
     *
     * @param bars       Number of bars
     * @param bpm        Tempo in beats per minute
     * @param sampleRate Audio sample rate in Hz
     * @return Number of samples
     */
    public static int barsToSamples(int bars, float bpm, int sampleRate) {
        float beats = bars * 4;  // 4/4 time
        float seconds = beats * 60.0f / bpm;
        return (int) (seconds * sampleRate);
    }

    /**
     * Convert bars to seconds (assuming 4/4 time).
     *
     * @param bars Number of bars
     * @param bpm  Tempo in beats per minute
     * @return Duration in seconds
     */
    public static float barsToSeconds(int bars, float bpm) {
        float beats = bars * 4;  // 4/4 time
        return beats * 60.0f / bpm;
    }

    /**
     * Convert seconds to samples.
     *
     * @param seconds    Duration in seconds
     * @param sampleRate Audio sample rate in Hz
     * @return Number of samples
     */
    public static int secondsToSamples(float seconds, int sampleRate) {
        return (int) (seconds * sampleRate);
    }

    /**
     * Normalize buffer to prevent clipping (peak normalization).
     *
     * @param buffer   Audio samples to normalize in-place
     * @param maxLevel Maximum allowed level (e.g., 0.95 for headroom)
     */
    public static void normalize(float[] buffer, float maxLevel) {
        float maxAbs = 0;
        for (float sample : buffer) {
            float abs = Math.abs(sample);
            if (abs > maxAbs) {
                maxAbs = abs;
            }
        }

        if (maxAbs > maxLevel) {
            float gain = maxLevel / maxAbs;
            for (int i = 0; i < buffer.length; i++) {
                buffer[i] *= gain;
            }
        }
    }

    /**
     * Extract a frame (segment) from an audio array.
     *
     * @param input  Source audio array
     * @param start  Start index
     * @param length Number of samples to extract
     * @return Extracted frame (zero-padded if needed)
     */
    public static float[] extractFrame(float[] input, int start, int length) {
        float[] frame = new float[length];
        int end = Math.min(start + length, input.length);
        for (int i = start; i < end; i++) {
            frame[i - start] = input[i];
        }
        return frame;
    }

    /**
     * Apply linear crossfade between two overlapping buffers.
     *
     * @param out    Output buffer (will be modified in-place)
     * @param fadeIn Incoming audio to fade in
     * @param offset Start position in output buffer for crossfade
     * @param length Length of crossfade region
     */
    public static void crossfadeOverlap(float[] out, float[] fadeIn, int offset, int length) {
        for (int i = 0; i < length && (offset + i) < out.length && i < fadeIn.length; i++) {
            float fadeInLevel = (float) i / length;
            float fadeOutLevel = 1.0f - fadeInLevel;
            out[offset + i] = out[offset + i] * fadeOutLevel + fadeIn[i] * fadeInLevel;
        }
    }

    /**
     * Find the best alignment offset using FFT-based cross-correlation.
     * This computes cross-correlation for ALL offsets at once using O(n log n) FFT,
     * instead of O(n * searchRange) for the direct method.
     *
     * @param reference    Reference signal to match against
     * @param target       Target signal (longer, we search within this)
     * @param targetOffset Starting position in target for the search window
     * @param searchRange  Number of samples to search (±searchRange)
     * @return Best offset within [-searchRange, +searchRange]
     */
    public static int findBestOffsetFFT(float[] reference, float[] target,
                                         int targetOffset, int searchRange) {
        int refLen = reference.length;

        // Extract search window from target: [targetOffset - searchRange, targetOffset + refLen + searchRange]
        int windowStart = Math.max(0, targetOffset - searchRange);
        int windowEnd = Math.min(target.length, targetOffset + refLen + searchRange);
        int windowLen = windowEnd - windowStart;

        if (windowLen < refLen) {
            return 0; // Not enough data
        }

        // Pad to next power of 2 for efficient FFT
        int fftSize = 1;
        while (fftSize < windowLen + refLen) {
            fftSize *= 2;
        }

        // Prepare reference (zero-padded, reversed for correlation)
        float[] refPadded = new float[fftSize * 2]; // Interleaved real/imag
        for (int i = 0; i < refLen; i++) {
            refPadded[i] = reference[refLen - 1 - i]; // Reverse for correlation
        }

        // Prepare target window (zero-padded)
        float[] targetPadded = new float[fftSize * 2];
        for (int i = 0; i < windowLen; i++) {
            targetPadded[i] = target[windowStart + i];
        }

        // FFT both signals (use cached FFT instance)
        FloatFFT_1D fft = getFFT(fftSize);
        fft.realForwardFull(refPadded);
        fft.realForwardFull(targetPadded);

        // Multiply in frequency domain (complex multiplication)
        // Result = IFFT(FFT(target) * conj(FFT(ref_reversed))) = cross-correlation
        float[] result = new float[fftSize * 2];
        for (int i = 0; i < fftSize; i++) {
            float re1 = targetPadded[2 * i];
            float im1 = targetPadded[2 * i + 1];
            float re2 = refPadded[2 * i];
            float im2 = -refPadded[2 * i + 1]; // Conjugate

            result[2 * i] = re1 * re2 - im1 * im2;
            result[2 * i + 1] = re1 * im2 + im1 * re2;
        }

        // Inverse FFT
        fft.complexInverse(result, true);

        // Find peak within valid offset range
        int bestOffset = 0;
        float bestCorr = Float.NEGATIVE_INFINITY;

        // Map from correlation index to actual offset
        int centerIdx = targetOffset - windowStart + refLen - 1;

        for (int offset = -searchRange; offset <= searchRange; offset++) {
            int corrIdx = centerIdx + offset;
            if (corrIdx >= 0 && corrIdx < fftSize) {
                float corr = result[2 * corrIdx]; // Real part
                if (corr > bestCorr) {
                    bestCorr = corr;
                    bestOffset = offset;
                }
            }
        }

        return bestOffset;
    }

    /**
     * Compute full cross-correlation between two signals using FFT.
     * Returns correlation values for all lags from 0 to maxLag.
     *
     * @param signal1 First signal
     * @param signal2 Second signal (typically same as signal1 for autocorrelation)
     * @param maxLag  Maximum lag to compute
     * @return Cross-correlation values for lags 0 to maxLag-1
     */
    public static float[] crossCorrelationFFT(float[] signal1, float[] signal2, int maxLag) {
        int n1 = signal1.length;
        int n2 = signal2.length;

        // Pad to next power of 2
        int fftSize = 1;
        while (fftSize < n1 + n2) {
            fftSize *= 2;
        }

        // Prepare signals (zero-padded)
        float[] s1Padded = new float[fftSize * 2];
        float[] s2Padded = new float[fftSize * 2];
        System.arraycopy(signal1, 0, s1Padded, 0, n1);
        System.arraycopy(signal2, 0, s2Padded, 0, n2);

        // FFT both signals (use cached FFT instance)
        FloatFFT_1D fft = getFFT(fftSize);
        fft.realForwardFull(s1Padded);
        fft.realForwardFull(s2Padded);

        // Multiply S1 * conj(S2) in frequency domain
        float[] result = new float[fftSize * 2];
        for (int i = 0; i < fftSize; i++) {
            float re1 = s1Padded[2 * i];
            float im1 = s1Padded[2 * i + 1];
            float re2 = s2Padded[2 * i];
            float im2 = -s2Padded[2 * i + 1]; // Conjugate

            result[2 * i] = re1 * re2 - im1 * im2;
            result[2 * i + 1] = re1 * im2 + im1 * re2;
        }

        // Inverse FFT
        fft.complexInverse(result, true);

        // Extract real part for positive lags
        float[] correlation = new float[maxLag];
        for (int i = 0; i < maxLag && i < fftSize; i++) {
            correlation[i] = result[2 * i]; // Real part at even indices
        }

        return correlation;
    }
}
