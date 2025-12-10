package com.ece420.lab1;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import org.jtransforms.fft.FloatFFT_1D;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SimpleBPMDetector {
    private static final String TAG = "SimpleBPMDetector";

    // BPM detection constants
    private static final int HOP_LENGTH = 512;
    private static final int MIN_BPM = 160; // D&B range
    private static final int MAX_BPM = 190;
    private static final int WINDOW_SIZE = 16;
    private static final int MAX_HARMONICS = 8;

    // Beat grid detection constants (higher resolution)
    private static final int BEAT_GRID_HOP_LENGTH = 128; // ~2.9ms at 44.1kHz
    private static final int PHASE_CANDIDATES = 256; // Sub-millisecond phase precision

    // Helper class for lag-score pairs
    private static class LagScore implements Comparable<LagScore> {
        int lag;
        float score;

        LagScore(int lag, float score) {
            this.lag = lag;
            this.score = score;
        }

        @Override
        public int compareTo(LagScore other) {
            return Float.compare(other.score, this.score); // Descending order
        }
    }

    // Cue points for DJ transitions
    public static class CuePoints {
        public final long cueInSample; // Where to start mixing in (for incoming track)
        public final long cueOutSample; // Where to start fading out (for outgoing track)

        public CuePoints(long cueIn, long cueOut) {
            this.cueInSample = cueIn;
            this.cueOutSample = cueOut;
        }
    }

    // Beat grid with beats, downbeats, BPM, and phase
    public static class BeatGrid {
        public final float[] beats; // All beat timestamps in seconds
        public final float[] downbeats; // Downbeat timestamps (every 4th beat)
        public final float bpm; // Detected BPM
        public final float phase; // Phase offset in seconds

        public BeatGrid(float[] beats, float[] downbeats, float bpm, float phase) {
            this.beats = beats;
            this.downbeats = downbeats;
            this.bpm = bpm;
            this.phase = phase;
        }
    }

    public SimpleBPMDetector() {
        // Constructor
    }

    // Helper class to hold audio data and its sample rate
    private static class AudioData {
        final float[] samples;
        final int sampleRate;

        AudioData(float[] samples, int sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    public float detectBPM(String filePath) {
        Log.d(TAG, "Detecting BPM for: " + filePath);

        try {
            // Decode audio (only 30 seconds needed for BPM)
            AudioData audioData = decodeAudioFile(filePath);
            if (audioData == null || audioData.samples == null || audioData.samples.length == 0) {
                Log.e(TAG, "Failed to decode audio");
                return 175.0f; // Default
            }

            return detectBPMFromSamples(audioData.samples, audioData.sampleRate);

        } catch (Exception e) {
            Log.e(TAG, "Error detecting BPM", e);
            return 175.0f; // Default fallback
        }
    }

    // Detect BPM from already-decoded samples
    public float detectBPMFromSamples(float[] audioSamples, int sampleRate) {
        Log.d(TAG, "Detecting BPM from " + audioSamples.length + " samples at " + sampleRate + "Hz");

        try {
            if (audioSamples == null || audioSamples.length == 0) {
                Log.e(TAG, "No audio samples provided");
                return 175.0f; // Default
            }

            // Normalization: Ensure we work with 44100Hz for consistent detection results
            float[] samples = audioSamples;
            int analysisRate = sampleRate;

            if (sampleRate != 44100) {
                Log.d(TAG, "Resampling from " + sampleRate + " to 44100Hz for BPM detection");
                samples = resampleTo44100(audioSamples, sampleRate);
                analysisRate = 44100;
            }

            // Limit to max 30 seconds to avoid memory issues
            int maxSamples = analysisRate * 30 * 2;
            if (samples.length > maxSamples) {
                float[] truncated = new float[maxSamples];
                System.arraycopy(samples, 0, truncated, 0, maxSamples);
                samples = truncated;
            }

            // Compute onset strength and process
            float[] onsetStrength = computeOnsetStrength(samples, analysisRate);
            float[] onsetHWR = halfWaveRectify(onsetStrength);
            float[] autocorr = computeAutocorrelation(onsetHWR);
            float bpm = findBestTempo(autocorr, analysisRate);

            Log.d(TAG, "Detected BPM: " + bpm);
            return bpm;

        } catch (Exception e) {
            Log.e(TAG, "Error detecting BPM from samples", e);
            return 175.0f; // Default fallback
        }
    }

    // Helper to resample audio to 44100Hz
    private float[] resampleTo44100(float[] input, int inputRate) {
        if (inputRate == 44100)
            return input;

        // Simple linear interpolation
        float ratio = 44100.0f / inputRate;
        int newLength = (int) (input.length * ratio);
        float[] output = new float[newLength];

        for (int i = 0; i < newLength; i++) {
            float srcIdx = i / ratio;
            int idx = (int) srcIdx;
            if (idx >= input.length - 1) {
                output[i] = input[input.length - 1];
            } else {
                float frac = srcIdx - idx;
                output[i] = input[idx] * (1 - frac) + input[idx + 1] * frac;
            }
        }
        return output;
    }

    // Detect full beat grid with phase alignment
    public BeatGrid detectBeatGrid(float[] audioSamples, int sampleRate) {
        Log.d(TAG, "Detecting beat grid from " + audioSamples.length + " samples");

        try {
            // Detect BPM
            float bpm = detectBPMFromSamples(audioSamples, sampleRate);

            // Compute onset strength and find phase
            // Compute onset strength and find phase using higher resolution
            float[] onsetStrength = computeOnsetStrength(audioSamples, sampleRate, BEAT_GRID_HOP_LENGTH);
            float[] onsetHWR = halfWaveRectify(onsetStrength);
            float period = 60.0f / bpm;
            float phase = findPhaseOffset(onsetHWR, period, sampleRate, BEAT_GRID_HOP_LENGTH);

            Log.d(TAG, "Phase offset: " + phase + "s");

            // Generate beat array
            List<Float> beatList = new ArrayList<>();
            float audioDuration = audioSamples.length / (float) sampleRate;
            float beatTime = phase;

            while (beatTime < audioDuration) {
                beatList.add(beatTime);
                beatTime += period;
            }

            float[] beats = new float[beatList.size()];
            for (int i = 0; i < beatList.size(); i++) {
                beats[i] = beatList.get(i);
            }

            // Detect downbeats (every 4th beat)
            float[] downbeats = detectDownbeats(beats);

            Log.d(TAG, String.format("Beat grid: %d beats, %d downbeats, BPM=%.1f",
                    beats.length, downbeats.length, bpm));

            return new BeatGrid(beats, downbeats, bpm, phase);

        } catch (Exception e) {
            Log.e(TAG, "Error detecting beat grid", e);
            // Return minimal beat grid with defaults
            float[] defaultBeats = new float[] { 0.0f };
            float[] defaultDownbeats = new float[] { 0.0f };
            return new BeatGrid(defaultBeats, defaultDownbeats, 175.0f, 0.0f);
        }
    }

    // Find phase offset by testing different phases using floating-point precision
    private float findPhaseOffset(float[] onsetHWR, float period, int sampleRate, int hopSize) {
        int numPhases = PHASE_CANDIDATES; // High resolution for better accuracy
        float bestPhase = 0.0f;
        float bestScore = 0.0f;
        float framesPerSecond = (float) sampleRate / hopSize;

        for (int p = 0; p < numPhases; p++) {
            float phase = (p / (float) numPhases) * period;
            float score = 0.0f;

            // Use floating-point accumulation to avoid integer truncation error
            float beatTimeSeconds = phase;
            while (beatTimeSeconds * framesPerSecond < onsetHWR.length - 1) {
                float framePos = beatTimeSeconds * framesPerSecond;
                score += interpolatedOnset(onsetHWR, framePos);
                beatTimeSeconds += period;
            }

            if (score > bestScore) {
                bestScore = score;
                bestPhase = phase;
            }
        }

        return bestPhase;
    }

    // Interpolate onset strength for sub-frame accuracy
    private float interpolatedOnset(float[] onset, float framePos) {
        int idx = (int) framePos;
        if (idx >= onset.length - 1)
            return onset[onset.length - 1];
        float frac = framePos - idx;
        return onset[idx] * (1 - frac) + onset[idx + 1] * frac;
    }

    // Detect downbeats (every 4 beats for 4/4 time)
    private float[] detectDownbeats(float[] beats) {
        if (beats.length < 4) {
            return beats; // Not enough beats
        }

        // Every 4th beat is a downbeat
        int numDownbeats = beats.length / 4;
        float[] downbeats = new float[numDownbeats];

        for (int i = 0; i < numDownbeats; i++) {
            downbeats[i] = beats[i * 4];
        }

        return downbeats;
    }

    // Find optimal cue points for DJ transitions based on energy analysis
    public CuePoints findCuePoints(float[] audioSamples, int sampleRate, float bpm) {
        Log.d(TAG, "Finding cue points for BPM=" + bpm + ", samples=" + audioSamples.length);

        // Default fallback values (80% point for cue-out, matching current mixer
        // behavior)
        long defaultCueIn = 0;
        long defaultCueOut = (long) (audioSamples.length * 0.8f);

        try {
            if (audioSamples == null || audioSamples.length == 0) {
                Log.w(TAG, "No audio samples provided for cue point detection");
                return new CuePoints(defaultCueIn, defaultCueOut);
            }

            // Normalization: Run energy analysis at 44100Hz for consistency
            float[] samples = audioSamples;
            int analysisRate = sampleRate;
            boolean wasResampled = false;

            if (sampleRate != 44100) {
                Log.d(TAG, "Resampling from " + sampleRate + " to 44100Hz for cue point detection");
                samples = resampleTo44100(audioSamples, sampleRate);
                analysisRate = 44100;
                wasResampled = true;
            }

            // Compute onset strength
            float[] onsetStrength = computeOnsetStrength(samples, analysisRate);
            if (onsetStrength.length < 10) {
                Log.w(TAG, "Track too short for cue point detection");
                return new CuePoints(defaultCueIn, defaultCueOut);
            }

            // Calculate bar dimensions
            float barDurationSec = 4 * 60.0f / bpm;
            int barDurationSamples = (int) (barDurationSec * analysisRate);
            int framesPerBar = barDurationSamples / HOP_LENGTH;
            if (framesPerBar < 1)
                framesPerBar = 1;

            // Calculate number of complete bars
            int numBars = onsetStrength.length / framesPerBar;
            if (numBars < 4) {
                Log.w(TAG, "Track has only " + numBars + " bars, using defaults");
                return new CuePoints(defaultCueIn, defaultCueOut);
            }

            Log.d(TAG, "Bar analysis: " + numBars + " bars, " + framesPerBar + " frames/bar");

            // Compute per-bar energy
            float[] barEnergies = new float[numBars];
            float totalEnergy = 0;
            for (int bar = 0; bar < numBars; bar++) {
                float sum = 0;
                int frameStart = bar * framesPerBar;
                int frameEnd = Math.min(frameStart + framesPerBar, onsetStrength.length);
                for (int f = frameStart; f < frameEnd; f++) {
                    sum += onsetStrength[f];
                }
                barEnergies[bar] = sum / (frameEnd - frameStart);
                totalEnergy += barEnergies[bar];
            }
            float meanEnergy = totalEnergy / numBars;
            Log.d(TAG, "Mean bar energy: " + meanEnergy);

            // Find cue-in point (first low energy bar after 30s, up to 50% of track)
            int startSearchBar = Math.max(1, (int) (30.0f / barDurationSec));
            int endCueInSearch = numBars / 2;  // Search up to halfway through track

            long cueInSample = 0; // Default to 0 at analysis rate
            boolean foundCueIn = false;

            for (int bar = startSearchBar; bar < endCueInSearch; bar++) {
                // Look for first low energy bar (E < 0.7 × mean)
                if (barEnergies[bar] < 0.7f * meanEnergy) {
                    cueInSample = (long) (bar * barDurationSec * analysisRate);
                    Log.d(TAG, String.format("Found cue-in at bar %d (~%.1fs, energy=%.3f, %.0f%% of mean)",
                            bar, bar * barDurationSec, barEnergies[bar], 100 * barEnergies[bar] / meanEnergy));
                    foundCueIn = true;
                    break;
                }
            }

            if (!foundCueIn) {
                Log.d(TAG, "No ideal cue-in found, using default (start of track)");
            }

            // Find cue-out point (high energy bar in 60-75% range)
            int cueOutStart = (int) (numBars * 0.60f); // Start at 60%
            int cueOutEnd = Math.min((int) (numBars * 0.75f), numBars - 16); // End at 75% or 16 bars before end
            int cueInBar = (int) (cueInSample / (barDurationSec * analysisRate));
            cueOutStart = Math.max(cueOutStart, cueInBar + 8); // Ensure at least 8 bars after cue-in

            long cueOutSample = (long) (samples.length * 0.8f); // Default at analysis rate
            boolean foundCueOut = false;

            for (int bar = cueOutStart; bar < cueOutEnd; bar++) {
                // Look for high energy bar
                if (barEnergies[bar] > meanEnergy) {
                    cueOutSample = (long) (bar * barDurationSec * analysisRate);
                    Log.d(TAG, String.format("Found cue-out at bar %d (~%.1fs, energy=%.3f, %.0f%% through track)",
                            bar, bar * barDurationSec, barEnergies[bar], 100.0f * bar / numBars));
                    foundCueOut = true;
                    break;
                }
            }

            if (!foundCueOut) {
                // Fallback: use 70% through track if no high-energy section found
                // Note: using 70% of analysis samples
                cueOutSample = (long) (samples.length * 0.70f);
                Log.d(TAG, "No ideal cue-out found, using 70% point");
            }

            // Ensure minimum 8 bars between cue-in and cue-out
            float minGapSamples = 8 * barDurationSec * analysisRate;
            if (cueOutSample - cueInSample < minGapSamples) {
                cueOutSample = Math.min((long) (cueInSample + minGapSamples), (long) (samples.length * 0.8f));
                Log.d(TAG, "Adjusted cue-out to ensure minimum gap");
            }

            // Convert back to original sample rate domain if we resampled
            if (wasResampled) {
                double ratio = (double) sampleRate / 44100.0;
                cueInSample = (long) (cueInSample * ratio);
                cueOutSample = (long) (cueOutSample * ratio);
                Log.d(TAG, "Converted cue points back to " + sampleRate + "Hz: in=" + cueInSample + ", out="
                        + cueOutSample);
            }

            Log.d(TAG, "Final cue points: in=" + cueInSample + ", out=" + cueOutSample);
            return new CuePoints(cueInSample, cueOutSample);

        } catch (Exception e) {
            Log.e(TAG, "Error finding cue points", e);
            return new CuePoints(defaultCueIn, defaultCueOut);
        }
    }

    private AudioData decodeAudioFile(String filePath) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;

        // Decode audio (30 seconds needed for stable BPM)
        // Pre-allocate buffer: 30s × 48000Hz × 2 channels
        final int ESTIMATED_SAMPLES = 30 * 48000 * 2;
        float[] sampleBuffer = new float[ESTIMATED_SAMPLES];
        int sampleCount = 0;
        int detectedSampleRate = 44100; // Default

        try {
            extractor.setDataSource(filePath);

            // Find audio track
            int audioTrackIndex = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    break;
                }
            }

            if (audioTrackIndex == -1) {
                Log.e(TAG, "No audio track found");
                return null;
            }

            extractor.selectTrack(audioTrackIndex);
            MediaFormat format = extractor.getTrackFormat(audioTrackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);

            // Get sample rate from format
            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                detectedSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                Log.d(TAG, "Detected sample rate: " + detectedSampleRate);
            }

            // Seek to 30 seconds to skip intro
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION)
                    : 0;
            long seekTimeUs = 30_000_000; // 30 seconds

            // If track is short (e.g. < 45s), seek to start or proportionate point
            if (durationUs > 0 && seekTimeUs > durationUs - 10_000_000) {
                seekTimeUs = 0; // Too short, start from beginning
            }

            extractor.seekTo(seekTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            Log.d(TAG, "Seeked to " + seekTimeUs / 1000000.0f + "s for BPM detection");

            // Create decoder
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();

            // Decode audio
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean isEOS = false;
            long timeoutUs = 10000;

            // Limit to 30 seconds for BPM detection (improved accuracy)
            long maxDurationUs = seekTimeUs + 30_000_000;

            while (!isEOS) {
                // Input
                int inputIndex = decoder.dequeueInputBuffer(timeoutUs);
                if (inputIndex >= 0) {
                    ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                    int sampleSize = extractor.readSampleData(inputBuffer, 0);

                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        isEOS = true;
                    } else {
                        long presentationTimeUs = extractor.getSampleTime();
                        if (presentationTimeUs > maxDurationUs && sampleCount > 44100 * 5) {
                            // Only stop if we have enough samples
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            isEOS = true;
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0);
                            extractor.advance();
                        }
                    }
                }

                // Output
                int outputIndex = decoder.dequeueOutputBuffer(info, timeoutUs);
                while (outputIndex >= 0) {
                    ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);

                    // Convert to float samples - directly to pre-allocated buffer
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.order(ByteOrder.LITTLE_ENDIAN);
                        outputBuffer.rewind();

                        // Assuming 16-bit PCM
                        ShortBuffer shortBuffer = outputBuffer.asShortBuffer();
                        while (shortBuffer.hasRemaining() && sampleCount < sampleBuffer.length) {
                            short sample = shortBuffer.get();
                            sampleBuffer[sampleCount++] = sample / 32768.0f;
                        }
                    }

                    decoder.releaseOutputBuffer(outputIndex, false);

                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEOS = true;
                        break;
                    }

                    // Check if buffer is full
                    if (sampleCount >= sampleBuffer.length) {
                        isEOS = true;
                        break;
                    }

                    outputIndex = decoder.dequeueOutputBuffer(info, timeoutUs);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error decoding audio", e);
            return null;
        } finally {
            if (decoder != null) {
                decoder.stop();
                decoder.release();
            }
            extractor.release();
        }

        // Trim buffer to actual size
        float[] samples = new float[sampleCount];
        System.arraycopy(sampleBuffer, 0, samples, 0, sampleCount);

        Log.d(TAG, "Decoded " + samples.length + " samples");
        return new AudioData(samples, detectedSampleRate);
    }

    private float[] computeOnsetStrength(float[] audio, int sampleRate) {
        return computeOnsetStrength(audio, sampleRate, HOP_LENGTH);
    }

    private float[] computeOnsetStrength(float[] audio, int sampleRate, int hopSize) {
        // Simple onset strength using energy (RMS) in frames
        int frameSize = 2048; // Keep window large for energy averaging but hop small for resolution
        int numFrames = (audio.length - frameSize) / hopSize + 1;

        float[] onsetStrength = new float[numFrames];

        for (int i = 0; i < numFrames; i++) {
            int start = i * hopSize;
            int end = Math.min(start + frameSize, audio.length);

            // Calculate RMS energy
            float sum = 0;
            for (int j = start; j < end; j++) {
                sum += audio[j] * audio[j];
            }
            onsetStrength[i] = (float) Math.sqrt(sum / (end - start));
        }

        return onsetStrength;
    }

    private float[] halfWaveRectify(float[] onset) {
        // Apply moving average
        float[] onsetMean = movingAverage(onset, WINDOW_SIZE);

        // Half-wave rectification: max(0, onset - mean)
        float[] onsetHWR = new float[onset.length];
        for (int i = 0; i < onset.length; i++) {
            onsetHWR[i] = Math.max(0, onset[i] - onsetMean[i]);
        }

        return onsetHWR;
    }

    private float[] movingAverage(float[] signal, int windowSize) {
        float[] averaged = new float[signal.length];

        for (int i = 0; i < signal.length; i++) {
            int start = Math.max(0, i - windowSize / 2);
            int end = Math.min(signal.length, i + windowSize / 2 + 1);

            float sum = 0;
            for (int j = start; j < end; j++) {
                sum += signal[j];
            }
            averaged[i] = sum / (end - start);
        }

        return averaged;
    }

    // Compute autocorrelation using FFT
    private float[] computeAutocorrelation(float[] signal) {
        // Pad to next power of 2 for efficient FFT
        int n = signal.length;
        int fftSize = 1;
        while (fftSize < n * 2) {
            fftSize *= 2;
        }

        // Create zero-padded array for FFT (JTransforms uses interleaved real/imag)
        float[] fftData = new float[fftSize * 2];
        System.arraycopy(signal, 0, fftData, 0, n);

        // Perform forward FFT
        FloatFFT_1D fft = new FloatFFT_1D(fftSize);
        fft.realForwardFull(fftData);

        // Compute power spectrum: |FFT(x)|²
        // fftData is interleaved [re0, im0, re1, im1, ...]
        for (int i = 0; i < fftSize; i++) {
            float re = fftData[2 * i];
            float im = fftData[2 * i + 1];
            float power = re * re + im * im;
            fftData[2 * i] = power;
            fftData[2 * i + 1] = 0; // Imaginary part is 0 for power spectrum
        }

        // Perform inverse FFT
        fft.complexInverse(fftData, true);

        // Extract real part (autocorrelation) and normalize
        int maxLag = Math.min(n, 1000); // Same limit as before
        float[] autocorr = new float[maxLag];
        float normFactor = fftData[0] > 0 ? 1.0f / fftData[0] : 1.0f;

        for (int i = 0; i < maxLag; i++) {
            // Real part is at even indices
            autocorr[i] = fftData[2 * i] * normFactor;
        }

        return autocorr;
    }

    private float findBestTempo(float[] autocorr, int sampleRate) {
        // Apply tempo prior to favor D&B range and reduce octave errors
        float[] weightedAutocorr = applyTempoPrior(autocorr, sampleRate);

        // Calculate lag range for 160-190 BPM
        int minLag = (int) ((60.0f / MAX_BPM) * sampleRate / HOP_LENGTH);
        int maxLag = (int) ((60.0f / MIN_BPM) * sampleRate / HOP_LENGTH);

        // Make sure lags are within bounds
        minLag = Math.max(1, minLag);
        maxLag = Math.min(weightedAutocorr.length - 1, maxLag);

        List<LagScore> candidates = new ArrayList<>();

        // Score each candidate lag using multi-harmonic approach with weighted autocorr
        for (int lag = minLag; lag <= maxLag; lag++) {
            float score = 0;
            int count = 0;

            // Check harmonics using weighted autocorrelation
            for (int i = 1; i <= MAX_HARMONICS; i++) {
                int harmonicLag = i * lag;
                if (harmonicLag < weightedAutocorr.length) {
                    score += weightedAutocorr[harmonicLag];
                    count++;
                }
            }

            if (count > 0) {
                score = score / count;
            }

            candidates.add(new LagScore(lag, score));
        }

        // Sort candidates by score (descending)
        Collections.sort(candidates);

        if (candidates.isEmpty()) {
            return 175.0f; // Default
        }

        // Get best lag and original tempo
        int bestLag = candidates.get(0).lag;
        int originalLag = bestLag;

        // Use parabolic interpolation for sub-sample precision
        float refinedBestLag = refineLag(autocorr, bestLag);
        float tempo = 60.0f / (refinedBestLag * HOP_LENGTH / (float) sampleRate);
        float originalTempo = tempo;

        // Half-time detection: if tempo < 100, try doubling it
        if (tempo < 100) {
            float doubleTempo = tempo * 2;
            int halfLag = bestLag / 2;

            if (doubleTempo >= MIN_BPM && doubleTempo <= MAX_BPM && halfLag >= minLag) {
                // Calculate score at half lag using weighted autocorr
                float scoreHalf = 0;
                int countHalf = 0;
                for (int i = 1; i <= MAX_HARMONICS; i++) {
                    int harmonicLag = i * halfLag;
                    if (harmonicLag < weightedAutocorr.length) {
                        scoreHalf += weightedAutocorr[harmonicLag];
                        countHalf++;
                    }
                }
                if (countHalf > 0)
                    scoreHalf /= countHalf;

                // Calculate score at original lag using weighted autocorr
                float scoreOriginal = 0;
                int countOriginal = 0;
                for (int i = 1; i <= MAX_HARMONICS; i++) {
                    int harmonicLag = i * originalLag;
                    if (harmonicLag < weightedAutocorr.length) {
                        scoreOriginal += weightedAutocorr[harmonicLag];
                        countOriginal++;
                    }
                }
                if (countOriginal > 0)
                    scoreOriginal /= countOriginal;

                // Use doubled tempo if half-lag score is reasonable
                if (scoreHalf > scoreOriginal * 0.4f) {
                    Log.d(TAG, String.format("Half-time correction: %.1f BPM -> %.1f BPM", tempo, doubleTempo));
                    tempo = doubleTempo;
                    bestLag = halfLag;
                }
            }
        }
        // Double-time detection: if tempo > 185, try halving it
        else if (tempo > 185) {
            float halfTempo = tempo / 2;
            int doubleLag = bestLag * 2;

            // Check if half_tempo is in 90-95 range
            if (halfTempo >= 90 && halfTempo <= 95 && doubleLag <= maxLag) {
                // Calculate score at double lag using weighted autocorr
                float scoreDouble = 0;
                int countDouble = 0;
                for (int i = 1; i <= Math.min(4, MAX_HARMONICS); i++) {
                    int harmonicLag = i * doubleLag;
                    if (harmonicLag < weightedAutocorr.length) {
                        scoreDouble += weightedAutocorr[harmonicLag];
                        countDouble++;
                    }
                }
                if (countDouble > 0)
                    scoreDouble /= countDouble;

                // Calculate score at original lag using weighted autocorr
                float scoreOriginal = 0;
                int countOriginal = 0;
                for (int i = 1; i <= Math.min(4, MAX_HARMONICS); i++) {
                    int harmonicLag = i * originalLag;
                    if (harmonicLag < weightedAutocorr.length) {
                        scoreOriginal += weightedAutocorr[harmonicLag];
                        countOriginal++;
                    }
                }
                if (countOriginal > 0)
                    scoreOriginal /= countOriginal;

                // Use halved tempo if double-lag score is reasonable
                if (scoreDouble > scoreOriginal * 0.8f) {
                    Log.d(TAG, String.format("Double-time correction: %.1f BPM -> %.1f BPM (half-time feel)", tempo,
                            halfTempo));
                    tempo = halfTempo;
                    bestLag = doubleLag;
                }
            }
        }

        // Clamp to expected range
        tempo = Math.max(MIN_BPM, Math.min(MAX_BPM, tempo));

        return tempo;
    }


    /**
     * Apply a tempo prior distribution to favor D&B tempo range.
     * Uses log-normal distribution centered at 175 BPM to reduce octave errors.
     * 
     * @param autocorr The autocorrelation array
     * @param sampleRate The sample rate in Hz
     * @return Weighted autocorrelation array
     */
    private float[] applyTempoPrior(float[] autocorr, int sampleRate) {
        float[] weighted = new float[autocorr.length];
        float priorCenter = 175.0f;  // D&B center tempo
        float sigma = 0.3f;  // Narrow distribution for D&B specificity

        for (int lag = 1; lag < autocorr.length; lag++) {
            float bpm = 60.0f / (lag * HOP_LENGTH / (float) sampleRate);

            // Log-normal prior centered at 175 BPM
            float logRatio = (float) (Math.log(bpm / priorCenter) / Math.log(2));
            float weight = (float) Math.exp(-0.5 * (logRatio * logRatio) / (sigma * sigma));

            weighted[lag] = autocorr[lag] * weight;
        }
        
        // Copy the first element unchanged (lag=0)
        weighted[0] = autocorr[0];
        
        return weighted;
    }

    /**
     * Refine lag estimate using parabolic interpolation around the peak.
     * This allows for sub-sample precision in the lag estimate.
     */
    private float refineLag(float[] autocorr, int peakLag) {
        if (peakLag <= 0 || peakLag >= autocorr.length - 1) {
            return peakLag;
        }

        // y values at x-1, x, x+1
        float y1 = autocorr[peakLag - 1];
        float y2 = autocorr[peakLag];
        float y3 = autocorr[peakLag + 1];

        // Parabolic interpolation formula for peak location offset
        float denominator = 2 * (2 * y2 - y1 - y3);
        if (Math.abs(denominator) < 1e-9) {
            return peakLag;
        }

        float delta = (y1 - y3) / denominator; // Note: simplified formula derivation often gives different signs based
                                               // on convention
        // Correct formula for peak offset d = (alpha - gamma) / (2 * (alpha - 2*beta +
        // gamma)) where y1=alpha, y2=beta, y3=gamma
        // Using standard vertex formula x = -b/2a from fitted parabola ax^2+bx+c
        // Let x indices be -1, 0, 1.
        // y(-1) = a - b + c = y1
        // y(0) = c = y2
        // y(1) = a + b + c = y3
        // a = (y1 + y3)/2 - y2
        // b = (y3 - y1)/2
        // Peak offset = -b / (2a)

        float a = (y1 + y3) / 2.0f - y2;
        float b = (y3 - y1) / 2.0f;

        if (Math.abs(a) < 1e-9)
            return peakLag; // Linear or flat

        float offset = -b / (2 * a);

        // Clamp offset to [-0.5, 0.5] as it should be within the bin
        offset = Math.max(-0.5f, Math.min(0.5f, offset));

        return peakLag + offset;
    }
}