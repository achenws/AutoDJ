package com.ece420.lab1.dsp;

import android.util.Log;

import java.util.Arrays;

/**
 * DJ-style crossfade with progressive EQ filtering.
 *
 * This is a manual implementation equivalent to the Python crossfade
 * function from the reference DJ application.
 *
 * Algorithm:
 * - Outgoing track gets progressive high-pass filter (removes bass as it fades)
 * - Incoming track starts with low-pass filter that opens up
 * - Linear volume fades with loudness compensation
 * - RMS-based volume normalization ensures consistent loudness
 */
public class EQCrossfader {

    private static final String TAG = "EQCrossfader";

    // Default parameters matching Python implementation
    private static final int DEFAULT_SAMPLE_RATE = 44100;
    private static final int DEFAULT_NUM_SEGMENTS = 8;

    // Filter frequency ranges (Hz)
    private static final float HP_START_FREQ = 20.0f;
    private static final float HP_END_FREQ = 200.0f;
    private static final float LP_START_FREQ = 200.0f;

    // RMS normalization target (typical music level)
    private static final float TARGET_RMS = 0.15f;
    private static final float MAX_GAIN = 4.0f;  // Limit to prevent excessive amplification

    private int sampleRate;
    private float targetRMS = TARGET_RMS;

    /**
     * Create a crossfader with default sample rate (44100 Hz).
     */
    public EQCrossfader() {
        this(DEFAULT_SAMPLE_RATE);
    }

    /**
     * Create a crossfader with specified sample rate.
     *
     * @param sampleRate Audio sample rate in Hz
     */
    public EQCrossfader(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    /**
     * Calculate RMS (root mean square) loudness of audio segment.
     * RMS represents the average power/loudness of the audio signal.
     *
     * @param audio  Audio samples
     * @param start  Start index
     * @param length Number of samples to analyze
     * @return RMS value (0.0 to 1.0 typical range)
     */
    private float calculateRMS(float[] audio, int start, int length) {
        if (audio == null || length <= 0 || start + length > audio.length) {
            return 0.0f;
        }

        double sumSquares = 0.0;
        for (int i = start; i < start + length; i++) {
            sumSquares += audio[i] * audio[i];
        }
        return (float) Math.sqrt(sumSquares / length);
    }

    /**
     * Calculate RMS of entire audio buffer.
     *
     * @param audio Audio samples
     * @return RMS value
     */
    public float calculateRMS(float[] audio) {
        return calculateRMS(audio, 0, audio.length);
    }

    /**
     * Normalize audio to target RMS level.
     * Adjusts the gain so the audio has consistent perceived loudness.
     *
     * @param audio     Audio samples (modified in-place)
     * @param targetRMS Desired RMS level (e.g., 0.15 for typical music)
     * @return The gain factor applied
     */
    public float normalizeToRMS(float[] audio, float targetRMS) {
        float currentRMS = calculateRMS(audio);

        if (currentRMS < 1e-6f) {  // Avoid division by zero (silence)
            Log.w(TAG, "Audio is silent (RMS < 1e-6), skipping normalization");
            return 1.0f;
        }

        float gain = targetRMS / currentRMS;

        // Limit gain to prevent excessive amplification and clipping
        if (gain > MAX_GAIN) {
            Log.w(TAG, String.format("Gain %.2f exceeds max %.2f, limiting", gain, MAX_GAIN));
            gain = MAX_GAIN;
        }

        // Apply gain
        for (int i = 0; i < audio.length; i++) {
            audio[i] *= gain;
        }

        Log.d(TAG, String.format("RMS normalization: %.4f -> %.4f (gain: %.2fx)",
                currentRMS, targetRMS, gain));

        return gain;
    }

    /**
     * Set the target RMS level for normalization.
     * Default is 0.15 (typical music level).
     *
     * @param targetRMS Target RMS level (0.0 to 1.0)
     */
    public void setTargetRMS(float targetRMS) {
        this.targetRMS = targetRMS;
    }

    /**
     * Get the current target RMS level.
     *
     * @return Target RMS level
     */
    public float getTargetRMS() {
        return targetRMS;
    }

    /**
     * Perform DJ-style EQ crossfade between two tracks.
     *
     * @param outgoing            Audio from outgoing track (will be high-passed)
     * @param incoming            Audio from incoming track (will be low-passed initially)
     * @param fadeDurationSamples Length of crossfade in samples
     * @return Mixed output with continuation of incoming track
     */
    public float[] crossfade(float[] outgoing, float[] incoming, int fadeDurationSamples) {
        float nyquist = sampleRate / 2.0f;

        // Ensure we have enough audio
        int fadeLen = Math.min(fadeDurationSamples,
                Math.min(outgoing.length, incoming.length));

        // Create working copies of the fade regions
        float[] fadeOut = Arrays.copyOf(outgoing, fadeLen);
        float[] fadeIn = Arrays.copyOf(incoming, fadeLen);

        // **RMS-based volume matching BEFORE crossfade**
        // This ensures both tracks have consistent perceived loudness
        float rmsOut = calculateRMS(fadeOut);
        float rmsIn = calculateRMS(fadeIn);

        Log.d(TAG, String.format("Pre-normalization RMS: outgoing=%.4f, incoming=%.4f", rmsOut, rmsIn));

        // Normalize both tracks to the target RMS level
        normalizeToRMS(fadeOut, targetRMS);
        normalizeToRMS(fadeIn, targetRMS);

        int numSegments = DEFAULT_NUM_SEGMENTS;
        int segmentLen = fadeLen / numSegments;

        // Only apply filters if we have enough samples
        if (segmentLen > 0) {
            // Progressive HIGH-PASS on outgoing track (second half only)
            ButterworthFilter hpf = new ButterworthFilter(
                    sampleRate, HP_START_FREQ, ButterworthFilter.FilterType.HIGH_PASS);

            for (int seg = numSegments / 2; seg < numSegments; seg++) {
                int start = seg * segmentLen;
                int end = Math.min((seg + 1) * segmentLen, fadeLen);

                // Calculate progressive cutoff: 20Hz -> 200Hz
                float progress = (float) (seg - numSegments / 2) / (numSegments / 2);
                float cutoff = HP_START_FREQ + (HP_END_FREQ - HP_START_FREQ) * progress;
                cutoff = Math.min(cutoff, nyquist * 0.99f);

                hpf.setCutoff(cutoff);
                hpf.processBuffer(fadeOut, start, end - start);
            }

            // Progressive LOW-PASS on incoming track (first half only)
            ButterworthFilter lpf = new ButterworthFilter(
                    sampleRate, LP_START_FREQ, ButterworthFilter.FilterType.LOW_PASS);

            for (int seg = 0; seg < numSegments / 2; seg++) {
                int start = seg * segmentLen;
                int end = Math.min((seg + 1) * segmentLen, fadeLen);

                // Calculate progressive cutoff: 200Hz -> near-nyquist
                float progress = (float) seg / (numSegments / 2);
                float cutoff = LP_START_FREQ + (nyquist * 0.9f - LP_START_FREQ) * progress;
                cutoff = Math.min(cutoff, nyquist * 0.99f);

                lpf.setCutoff(cutoff);
                lpf.processBuffer(fadeIn, start, end - start);
            }
        }

        // Create linear fade curves and mix
        float[] mixed = new float[fadeLen];
        for (int i = 0; i < fadeLen; i++) {
            float fadeOutLevel = 1.0f - (float) i / fadeLen;
            float fadeInLevel = (float) i / fadeLen;

            // Apply volume fades
            float outSample = fadeOut[i] * fadeOutLevel;
            float inSample = fadeIn[i] * fadeInLevel;

            // Mix
            mixed[i] = outSample + inSample;

            // Apply loudness compensation
            // During crossfade, perceived loudness dips at 50%
            // This compensates by boosting the middle of the fade
            float compensation = (float) Math.sqrt(1.0 /
                    (fadeOutLevel * fadeOutLevel + fadeInLevel * fadeInLevel + 1e-10));
            compensation = Math.min(compensation, 1.5f);  // Limit gain
            mixed[i] *= compensation;
        }

        // Build complete output: crossfaded portion + rest of incoming track
        float[] output;
        if (incoming.length > fadeLen) {
            output = new float[fadeLen + (incoming.length - fadeLen)];
            System.arraycopy(mixed, 0, output, 0, fadeLen);
            System.arraycopy(incoming, fadeLen, output, fadeLen, incoming.length - fadeLen);
        } else {
            output = mixed;
        }

        return output;
    }

    /**
     * Perform simple volume-only crossfade (no EQ filtering).
     * Used for non-DJ transitions.
     *
     * @param outgoing            Audio from outgoing track
     * @param incoming            Audio from incoming track
     * @param fadeDurationSamples Length of crossfade in samples
     * @return Mixed output with continuation of incoming track
     */
    public float[] simpleCrossfade(float[] outgoing, float[] incoming, int fadeDurationSamples) {
        int fadeLen = Math.min(fadeDurationSamples,
                Math.min(outgoing.length, incoming.length));

        // Create working copies
        float[] fadeOut = Arrays.copyOf(outgoing, fadeLen);
        float[] fadeIn = Arrays.copyOf(incoming, fadeLen);

        // **RMS-based volume matching BEFORE crossfade**
        float rmsOut = calculateRMS(fadeOut);
        float rmsIn = calculateRMS(fadeIn);

        Log.d(TAG, String.format("Simple crossfade - Pre-normalization RMS: outgoing=%.4f, incoming=%.4f", rmsOut, rmsIn));

        // Normalize both tracks to the target RMS level
        normalizeToRMS(fadeOut, targetRMS);
        normalizeToRMS(fadeIn, targetRMS);

        float[] mixed = new float[fadeLen];
        for (int i = 0; i < fadeLen; i++) {
            float fadeOutLevel = 1.0f - (float) i / fadeLen;
            float fadeInLevel = (float) i / fadeLen;

            mixed[i] = fadeOut[i] * fadeOutLevel + fadeIn[i] * fadeInLevel;
        }

        // Append rest of incoming track
        float[] output;
        if (incoming.length > fadeLen) {
            output = new float[fadeLen + (incoming.length - fadeLen)];
            System.arraycopy(mixed, 0, output, 0, fadeLen);
            System.arraycopy(incoming, fadeLen, output, fadeLen, incoming.length - fadeLen);
        } else {
            output = mixed;
        }

        return output;
    }

    /**
     * Perform simple overlap mix (both tracks play at same time).
     * No fading, just overlaps the tracks.
     *
     * @param outgoing              Audio from outgoing track
     * @param incoming              Audio from incoming track
     * @param overlapDurationSamples Length of overlap in samples
     * @return Mixed output
     */
    public float[] simpleOverlap(float[] outgoing, float[] incoming, int overlapDurationSamples) {
        int overlapLen = Math.min(overlapDurationSamples,
                Math.min(outgoing.length, incoming.length));

        float[] mixed = new float[overlapLen];
        for (int i = 0; i < overlapLen; i++) {
            // Both tracks at equal volume
            mixed[i] = (outgoing[i] + incoming[i]) * 0.5f;  // Attenuate to prevent clipping
        }

        // Append rest of incoming track
        float[] output;
        if (incoming.length > overlapLen) {
            output = new float[overlapLen + (incoming.length - overlapLen)];
            System.arraycopy(mixed, 0, output, 0, overlapLen);
            System.arraycopy(incoming, overlapLen, output, overlapLen, incoming.length - overlapLen);
        } else {
            output = mixed;
        }

        return output;
    }

    /**
     * Calculate fade duration from musical parameters.
     *
     * @param bars        Number of bars for transition
     * @param bpm         Tempo in beats per minute
     * @param beatsPerBar Usually 4 for 4/4 time
     * @return Duration in samples
     */
    public int calculateFadeDuration(int bars, float bpm, int beatsPerBar) {
        float beats = bars * beatsPerBar;
        float seconds = beats * 60.0f / bpm;
        return (int) (seconds * sampleRate);
    }

    /**
     * Calculate fade duration from seconds.
     *
     * @param seconds Duration in seconds
     * @return Duration in samples
     */
    public int calculateFadeDuration(float seconds) {
        return (int) (seconds * sampleRate);
    }

    /**
     * Apply hard limiter to prevent clipping.
     * Clips any samples exceeding the threshold to prevent distortion.
     *
     * @param audio     Audio samples (modified in-place)
     * @param threshold Maximum allowed amplitude (typically 0.95 to 1.0)
     */
    public void applyPeakLimiter(float[] audio, float threshold) {
        int clippedCount = 0;
        for (int i = 0; i < audio.length; i++) {
            if (audio[i] > threshold) {
                audio[i] = threshold;
                clippedCount++;
            } else if (audio[i] < -threshold) {
                audio[i] = -threshold;
                clippedCount++;
            }
        }

        if (clippedCount > 0) {
            Log.d(TAG, String.format("Peak limiter: clipped %d samples (%.2f%%)",
                    clippedCount, 100.0f * clippedCount / audio.length));
        }
    }
}
