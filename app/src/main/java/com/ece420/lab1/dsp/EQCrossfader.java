package com.ece420.lab1.dsp;

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
 */
public class EQCrossfader {

    // Default parameters matching Python implementation
    private static final int DEFAULT_SAMPLE_RATE = 44100;
    private static final int DEFAULT_NUM_SEGMENTS = 8;

    // Filter frequency ranges (Hz)
    private static final float HP_START_FREQ = 20.0f;
    private static final float HP_END_FREQ = 200.0f;
    private static final float LP_START_FREQ = 200.0f;

    private int sampleRate;

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

        float[] mixed = new float[fadeLen];
        for (int i = 0; i < fadeLen; i++) {
            float fadeOutLevel = 1.0f - (float) i / fadeLen;
            float fadeInLevel = (float) i / fadeLen;

            mixed[i] = outgoing[i] * fadeOutLevel + incoming[i] * fadeInLevel;
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
}
