package com.ece420.lab1.dsp;

import android.util.Log;

import java.util.Arrays;

// DJ-style crossfade with progressive EQ filtering
public class EQCrossfader {

    private static final String TAG = "EQCrossfader";

    // Default parameters
    private static final int DEFAULT_SAMPLE_RATE = 44100;
    private static final int DEFAULT_NUM_SEGMENTS = 8;

    // Filter frequency ranges (Hz)
    private static final float HP_START_FREQ = 20.0f;
    private static final float HP_END_FREQ = 200.0f;
    private static final float LP_START_FREQ = 200.0f;

    // Peak-based level matching limits
    private static final float MIN_GAIN = 0.5f;  // Minimum gain factor
    private static final float MAX_GAIN = 2.0f;  // Maximum gain factor to prevent over-amplification

    private int sampleRate;

    public EQCrossfader(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    // Find peak amplitude in audio buffer
    public float findPeakAmplitude(float[] audio) {
        if (audio == null || audio.length == 0) {
            return 0.0f;
        }

        float peak = 0.0f;
        for (float sample : audio) {
            float abs = Math.abs(sample);
            if (abs > peak) {
                peak = abs;
            }
        }
        return peak;
    }

    // Match track2 level to track1 using peak amplitudes
    public float matchLevelsByPeak(float[] track1, float[] track2) {
        float peak1 = findPeakAmplitude(track1);
        float peak2 = findPeakAmplitude(track2);

        if (peak2 < 1e-6f) {  // Avoid division by zero (silence)
            Log.w(TAG, "Track 2 is silent (peak < 1e-6), skipping level matching");
            return 1.0f;
        }

        float gain = peak1 / peak2;

        // Limit gain to prevent extreme adjustments
        gain = Math.max(MIN_GAIN, Math.min(MAX_GAIN, gain));

        // Apply gain to track2
        for (int i = 0; i < track2.length; i++) {
            track2[i] *= gain;
        }

        return gain;
    }

    // DJ-style EQ crossfade between two tracks
    public float[] crossfade(float[] outgoing, float[] incoming, int fadeDurationSamples) {
        float nyquist = sampleRate / 2.0f;

        // Ensure we have enough audio
        int fadeLen = Math.min(fadeDurationSamples,
                Math.min(outgoing.length, incoming.length));

        // Create working copies of the fade regions
        float[] fadeOut = Arrays.copyOf(outgoing, fadeLen);
        float[] fadeIn = Arrays.copyOf(incoming, fadeLen);

        // **Peak-based level matching BEFORE crossfade**
        // Match incoming track's level to outgoing track for natural sound
        // (no pumping artifacts like RMS normalization can cause)
        matchLevelsByPeak(fadeOut, fadeIn);

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

    // Simple volume crossfade (no EQ filtering)
    public float[] simpleCrossfade(float[] outgoing, float[] incoming, int fadeDurationSamples) {
        int fadeLen = Math.min(fadeDurationSamples,
                Math.min(outgoing.length, incoming.length));

        // Create working copies
        float[] fadeOut = Arrays.copyOf(outgoing, fadeLen);
        float[] fadeIn = Arrays.copyOf(incoming, fadeLen);

        // **Peak-based level matching BEFORE crossfade**
        // Match incoming track's level to outgoing track for natural sound
        matchLevelsByPeak(fadeOut, fadeIn);

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

    // Calculate fade duration from bars and BPM
    public int calculateFadeDuration(int bars, float bpm, int beatsPerBar) {
        float beats = bars * beatsPerBar;
        float seconds = beats * 60.0f / bpm;
        return (int) (seconds * sampleRate);
    }

    // Calculate fade duration from seconds
    public int calculateFadeDuration(float seconds) {
        return (int) (seconds * sampleRate);
    }

    // Apply hard limiter to prevent clipping
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

    }
}
