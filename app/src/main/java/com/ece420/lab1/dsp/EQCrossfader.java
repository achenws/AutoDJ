package com.ece420.lab1.dsp;

import android.util.Log;
import java.util.Arrays;

// dj style crossfade with eq filtering
public class EQCrossfader {

    private static final String TAG = "EQCrossfader";
    private static final int DEFAULT_NUM_SEGMENTS = 8;
    private static final float HP_START_FREQ = 20.0f;
    private static final float HP_END_FREQ = 200.0f;
    private static final float LP_START_FREQ = 200.0f;
    private static final float MIN_GAIN = 0.5f;
    private static final float MAX_GAIN = 2.0f;

    private int sampleRate;

    public EQCrossfader(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public float findPeakAmplitude(float[] audio) {
        if (audio == null || audio.length == 0) return 0.0f;

        float peak = 0.0f;
        for (float sample : audio) {
            float abs = Math.abs(sample);
            if (abs > peak) peak = abs;
        }
        return peak;
    }

    public float matchLevelsByPeak(float[] track1, float[] track2) {
        float peak1 = findPeakAmplitude(track1);
        float peak2 = findPeakAmplitude(track2);

        if (peak2 < 1e-6f) {
            Log.w(TAG, "Track 2 is silent");
            return 1.0f;
        }

        float gain = Math.max(MIN_GAIN, Math.min(MAX_GAIN, peak1 / peak2));

        for (int i = 0; i < track2.length; i++) {
            track2[i] *= gain;
        }
        return gain;
    }

    public float[] crossfade(float[] outgoing, float[] incoming, int fadeDurationSamples) {
        float nyquist = sampleRate / 2.0f;
        int fadeLen = Math.min(fadeDurationSamples, Math.min(outgoing.length, incoming.length));

        float[] fadeOut = Arrays.copyOf(outgoing, fadeLen);
        float[] fadeIn = Arrays.copyOf(incoming, fadeLen);

        matchLevelsByPeak(fadeOut, fadeIn);

        int numSegments = DEFAULT_NUM_SEGMENTS;
        int segmentLen = fadeLen / numSegments;

        if (segmentLen > 0) {
            // highpass on outgoing
            ButterworthFilter hpf = new ButterworthFilter(
                    sampleRate, HP_START_FREQ, ButterworthFilter.FilterType.HIGH_PASS);

            for (int seg = numSegments / 2; seg < numSegments; seg++) {
                int start = seg * segmentLen;
                int end = Math.min((seg + 1) * segmentLen, fadeLen);

                float progress = (float) (seg - numSegments / 2) / (numSegments / 2);
                float cutoff = Math.min(HP_START_FREQ + (HP_END_FREQ - HP_START_FREQ) * progress, nyquist * 0.99f);

                hpf.setCutoff(cutoff);
                hpf.processBuffer(fadeOut, start, end - start);
            }

            // lowpass on incoming
            ButterworthFilter lpf = new ButterworthFilter(
                    sampleRate, LP_START_FREQ, ButterworthFilter.FilterType.LOW_PASS);

            for (int seg = 0; seg < numSegments / 2; seg++) {
                int start = seg * segmentLen;
                int end = Math.min((seg + 1) * segmentLen, fadeLen);

                float progress = (float) seg / (numSegments / 2);
                float cutoff = Math.min(LP_START_FREQ + (nyquist * 0.9f - LP_START_FREQ) * progress, nyquist * 0.99f);

                lpf.setCutoff(cutoff);
                lpf.processBuffer(fadeIn, start, end - start);
            }
        }

        // mix with volume fades
        float[] mixed = new float[fadeLen];
        for (int i = 0; i < fadeLen; i++) {
            float fadeOutLevel = 1.0f - (float) i / fadeLen;
            float fadeInLevel = (float) i / fadeLen;

            float outSample = fadeOut[i] * fadeOutLevel;
            float inSample = fadeIn[i] * fadeInLevel;

            mixed[i] = outSample + inSample;

            // loudness compensation
            float compensation = (float) Math.sqrt(1.0 / (fadeOutLevel * fadeOutLevel + fadeInLevel * fadeInLevel + 1e-10));
            mixed[i] *= Math.min(compensation, 1.5f);
        }

        // append rest of incoming
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

    public float[] simpleCrossfade(float[] outgoing, float[] incoming, int fadeDurationSamples) {
        int fadeLen = Math.min(fadeDurationSamples, Math.min(outgoing.length, incoming.length));

        float[] fadeOut = Arrays.copyOf(outgoing, fadeLen);
        float[] fadeIn = Arrays.copyOf(incoming, fadeLen);

        matchLevelsByPeak(fadeOut, fadeIn);

        float[] mixed = new float[fadeLen];
        for (int i = 0; i < fadeLen; i++) {
            float fadeOutLevel = 1.0f - (float) i / fadeLen;
            float fadeInLevel = (float) i / fadeLen;
            mixed[i] = fadeOut[i] * fadeOutLevel + fadeIn[i] * fadeInLevel;
        }

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

    public int calculateFadeDuration(int bars, float bpm, int beatsPerBar) {
        float beats = bars * beatsPerBar;
        float seconds = beats * 60.0f / bpm;
        return (int) (seconds * sampleRate);
    }

    public int calculateFadeDuration(float seconds) {
        return (int) (seconds * sampleRate);
    }

    public void applyPeakLimiter(float[] audio, float threshold) {
        for (int i = 0; i < audio.length; i++) {
            if (audio[i] > threshold) audio[i] = threshold;
            else if (audio[i] < -threshold) audio[i] = -threshold;
        }
    }
}
