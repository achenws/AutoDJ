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
    private static final int MIN_BPM = 160;  // D&B range
    private static final int MAX_BPM = 190;
    private static final int WINDOW_SIZE = 16;
    private static final int MAX_HARMONICS = 8;

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
            return Float.compare(other.score, this.score);  // Descending order
        }
    }

    // Cue points for DJ transitions
    public static class CuePoints {
        public final long cueInSample;   // Where to start mixing in (for incoming track)
        public final long cueOutSample;  // Where to start fading out (for outgoing track)

        public CuePoints(long cueIn, long cueOut) {
            this.cueInSample = cueIn;
            this.cueOutSample = cueOut;
        }
    }

    // Beat grid with beats, downbeats, BPM, and phase
    public static class BeatGrid {
        public final float[] beats;        // All beat timestamps in seconds
        public final float[] downbeats;    // Downbeat timestamps (every 4th beat)
        public final float bpm;            // Detected BPM
        public final float phase;          // Phase offset in seconds

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

    public float detectBPM(String filePath) {
        Log.d(TAG, "Detecting BPM for: " + filePath);

        try {
            // Decode audio (only 5 seconds needed for BPM)
            float[] audioSamples = decodeAudioFile(filePath);
            if (audioSamples == null || audioSamples.length == 0) {
                Log.e(TAG, "Failed to decode audio");
                return 175.0f;  // Default
            }

            return detectBPMFromSamples(audioSamples, 44100);

        } catch (Exception e) {
            Log.e(TAG, "Error detecting BPM", e);
            return 175.0f;  // Default fallback
        }
    }

    // Detect BPM from already-decoded samples
    public float detectBPMFromSamples(float[] audioSamples, int sampleRate) {
        Log.d(TAG, "Detecting BPM from " + audioSamples.length + " samples");

        try {
            if (audioSamples == null || audioSamples.length == 0) {
                Log.e(TAG, "No audio samples provided");
                return 175.0f;  // Default
            }

            // Limit to 5 seconds for efficiency
            int maxSamples = sampleRate * 5 * 2;
            float[] samples = audioSamples;
            if (audioSamples.length > maxSamples) {
                samples = new float[maxSamples];
                System.arraycopy(audioSamples, 0, samples, 0, maxSamples);
            }

            // Compute onset strength and process
            float[] onsetStrength = computeOnsetStrength(samples, sampleRate);
            float[] onsetHWR = halfWaveRectify(onsetStrength);
            float[] autocorr = computeAutocorrelation(onsetHWR);
            float bpm = findBestTempo(autocorr, sampleRate);

            Log.d(TAG, "Detected BPM: " + bpm);
            return bpm;

        } catch (Exception e) {
            Log.e(TAG, "Error detecting BPM from samples", e);
            return 175.0f;  // Default fallback
        }
    }

    // Detect full beat grid with phase alignment
    public BeatGrid detectBeatGrid(float[] audioSamples, int sampleRate) {
        Log.d(TAG, "Detecting beat grid from " + audioSamples.length + " samples");

        try {
            // Detect BPM
            float bpm = detectBPMFromSamples(audioSamples, sampleRate);

            // Compute onset strength and find phase
            float[] onsetStrength = computeOnsetStrength(audioSamples, sampleRate);
            float[] onsetHWR = halfWaveRectify(onsetStrength);
            float period = 60.0f / bpm;
            float phase = findPhaseOffset(onsetHWR, period, sampleRate);

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
            float[] defaultBeats = new float[]{0.0f};
            float[] defaultDownbeats = new float[]{0.0f};
            return new BeatGrid(defaultBeats, defaultDownbeats, 175.0f, 0.0f);
        }
    }

    // Find phase offset by testing different phases using floating-point precision
    private float findPhaseOffset(float[] onsetHWR, float period, int sampleRate) {
        int numPhases = 64;  // Doubled resolution for better accuracy
        float bestPhase = 0.0f;
        float bestScore = 0.0f;
        float framesPerSecond = (float) sampleRate / HOP_LENGTH;

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
        if (idx >= onset.length - 1) return onset[onset.length - 1];
        float frac = framePos - idx;
        return onset[idx] * (1 - frac) + onset[idx + 1] * frac;
    }

    // Detect downbeats (every 4 beats for 4/4 time)
    private float[] detectDownbeats(float[] beats) {
        if (beats.length < 4) {
            return beats;  // Not enough beats
        }

        // Every 4th beat is a downbeat
        int numDownbeats = beats.length / 4;
        float[] downbeats = new float[numDownbeats];

        for (int i = 0; i < numDownbeats; i++) {
            downbeats[i] = beats[i * 4];
        }

        return downbeats;
    }

    // Find cue points from pre-extracted waveform data
    public CuePoints findCuePointsFromWaveform(float[] waveformData, float bpm, long durationMs) {
        Log.d(TAG, "Finding cue points from waveform, BPM=" + bpm + ", points=" + waveformData.length);

        // Default fallback values
        float defaultCueIn = 0.0f;
        float defaultCueOut = 0.8f;

        try {
            if (waveformData == null || waveformData.length < 100 || bpm <= 0) {
                Log.w(TAG, "Insufficient data for cue point detection");
                return new CuePoints((long)(defaultCueIn * durationMs * 44.1f),
                                    (long)(defaultCueOut * durationMs * 44.1f));
            }

            // Calculate bar dimensions in waveform points
            float barDurationSec = 4 * 60.0f / bpm;  // 4 beats per bar
            float trackDurationSec = durationMs / 1000.0f;
            int pointsPerBar = (int) (waveformData.length * barDurationSec / trackDurationSec);
            if (pointsPerBar < 1) pointsPerBar = 1;

            int numBars = waveformData.length / pointsPerBar;
            if (numBars < 8) {
                Log.w(TAG, "Track too short: " + numBars + " bars");
                return new CuePoints(0, (long)(defaultCueOut * durationMs * 44.1f));
            }

            // Compute per-bar energy (RMS of absolute waveform values)
            float[] barEnergies = new float[numBars];
            float totalEnergy = 0;
            for (int bar = 0; bar < numBars; bar++) {
                float sum = 0;
                int start = bar * pointsPerBar;
                int end = Math.min(start + pointsPerBar, waveformData.length);
                for (int i = start; i < end; i++) {
                    sum += Math.abs(waveformData[i]);
                }
                barEnergies[bar] = sum / (end - start);
                totalEnergy += barEnergies[bar];
            }
            float meanEnergy = totalEnergy / numBars;

            // Find cue-in: low energy bar ~30s in
            int startSearchBar = Math.max(1, (int) (30.0f / barDurationSec));
            startSearchBar = Math.min(startSearchBar, numBars / 4);

            float cueInNorm = defaultCueIn;
            for (int bar = startSearchBar; bar < Math.min(startSearchBar + 16, numBars / 2); bar++) {
                if (barEnergies[bar] < 0.7f * meanEnergy) {
                    // Also check next bar to avoid transient dips
                    if (bar + 1 < numBars && barEnergies[bar + 1] < 0.8f * meanEnergy) {
                        cueInNorm = (float) bar / numBars;
                        Log.d(TAG, "Found cue-in at bar " + bar);
                        break;
                    }
                }
            }

            // Find cue-out: high energy bar in second half
            int endSearchBar = numBars - 16;  // Stop 16 bars before end
            int startMidSearch = Math.max(numBars / 2, (int)(cueInNorm * numBars) + 8);

            float cueOutNorm = defaultCueOut;
            for (int bar = startMidSearch; bar < endSearchBar; bar++) {
                if (barEnergies[bar] > meanEnergy) {
                    cueOutNorm = (float) bar / numBars;
                    Log.d(TAG, "Found cue-out at bar " + bar);
                    break;
                }
            }

            // Ensure minimum 8 bars between cue-in and cue-out
            int cueInBar = (int)(cueInNorm * numBars);
            int cueOutBar = (int)(cueOutNorm * numBars);
            if (cueOutBar - cueInBar < 8) {
                cueOutNorm = Math.min((float)(cueInBar + 8) / numBars, defaultCueOut);
                Log.d(TAG, "Adjusted cue-out to ensure minimum 8-bar gap");
            }

            // Convert to sample positions (assuming 44100 Hz)
            long totalSamples = (long) (durationMs * 44.1f);
            long cueInSample = (long) (cueInNorm * totalSamples);
            long cueOutSample = (long) (cueOutNorm * totalSamples);

            Log.d(TAG, "Cue points: in=" + cueInNorm + ", out=" + cueOutNorm);
            return new CuePoints(cueInSample, cueOutSample);

        } catch (Exception e) {
            Log.e(TAG, "Error in fast cue point detection", e);
            long totalSamples = (long) (durationMs * 44.1f);
            return new CuePoints(0, (long)(defaultCueOut * totalSamples));
        }
    }

    // Find optimal cue points for DJ transitions based on energy analysis
    public CuePoints findCuePoints(float[] audioSamples, int sampleRate, float bpm) {
        Log.d(TAG, "Finding cue points for BPM=" + bpm + ", samples=" + audioSamples.length);

        // Default fallback values (80% point for cue-out, matching current mixer behavior)
        long defaultCueIn = 0;
        long defaultCueOut = (long) (audioSamples.length * 0.8f);

        try {
            if (audioSamples == null || audioSamples.length == 0) {
                Log.w(TAG, "No audio samples provided for cue point detection");
                return new CuePoints(defaultCueIn, defaultCueOut);
            }

            // Compute onset strength
            float[] onsetStrength = computeOnsetStrength(audioSamples, sampleRate);
            if (onsetStrength.length < 10) {
                Log.w(TAG, "Track too short for cue point detection");
                return new CuePoints(defaultCueIn, defaultCueOut);
            }

            // Calculate bar dimensions
            float barDurationSec = 4 * 60.0f / bpm;
            int barDurationSamples = (int) (barDurationSec * sampleRate);
            int framesPerBar = barDurationSamples / HOP_LENGTH;
            if (framesPerBar < 1) framesPerBar = 1;

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

            // Find cue-in point (low energy bar, ~30-45s into track)
            int startSearchBar = Math.max(1, (int) (30.0f / barDurationSec));
            int endCueInSearch = Math.min((int) (45.0f / barDurationSec), numBars / 3);

            long cueInSample = defaultCueIn;
            boolean foundCueIn = false;

            for (int bar = startSearchBar; bar < endCueInSearch; bar++) {
                // Look for low energy bar
                if (barEnergies[bar] < 0.7f * meanEnergy) {
                    // Also check next bar to avoid transient dips
                    if (bar + 1 < numBars && barEnergies[bar + 1] < 0.8f * meanEnergy) {
                        cueInSample = (long) (bar * barDurationSec * sampleRate);
                        Log.d(TAG, String.format("Found cue-in at bar %d (~%.1fs, energy=%.3f, %.0f%% of mean)",
                                bar, bar * barDurationSec, barEnergies[bar], 100 * barEnergies[bar] / meanEnergy));
                        foundCueIn = true;
                        break;
                    }
                }
            }

            if (!foundCueIn) {
                Log.d(TAG, "No ideal cue-in found, using default (start of track)");
            }

            // Find cue-out point (high energy bar in 60-75% range)
            int cueOutStart = (int) (numBars * 0.60f);  // Start at 60%
            int cueOutEnd = Math.min((int) (numBars * 0.75f), numBars - 16);  // End at 75% or 16 bars before end
            int cueInBar = (int) (cueInSample / (barDurationSec * sampleRate));
            cueOutStart = Math.max(cueOutStart, cueInBar + 8);  // Ensure at least 8 bars after cue-in

            long cueOutSample = defaultCueOut;
            boolean foundCueOut = false;

            for (int bar = cueOutStart; bar < cueOutEnd; bar++) {
                // Look for high energy bar
                if (barEnergies[bar] > meanEnergy) {
                    cueOutSample = (long) (bar * barDurationSec * sampleRate);
                    Log.d(TAG, String.format("Found cue-out at bar %d (~%.1fs, energy=%.3f, %.0f%% through track)",
                            bar, bar * barDurationSec, barEnergies[bar], 100.0f * bar / numBars));
                    foundCueOut = true;
                    break;
                }
            }

            if (!foundCueOut) {
                // Fallback: use 70% through track if no high-energy section found
                cueOutSample = (long) (audioSamples.length * 0.70f);
                Log.d(TAG, "No ideal cue-out found, using 70% point");
            }

            // Ensure minimum 8 bars between cue-in and cue-out
            float minGapSamples = 8 * barDurationSec * sampleRate;
            if (cueOutSample - cueInSample < minGapSamples) {
                cueOutSample = Math.min((long) (cueInSample + minGapSamples), defaultCueOut);
                Log.d(TAG, "Adjusted cue-out to ensure minimum gap");
            }

            Log.d(TAG, "Final cue points: in=" + cueInSample + ", out=" + cueOutSample);
            return new CuePoints(cueInSample, cueOutSample);

        } catch (Exception e) {
            Log.e(TAG, "Error finding cue points", e);
            return new CuePoints(defaultCueIn, defaultCueOut);
        }
    }

    private float[] decodeAudioFile(String filePath) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;

        // Pre-allocate buffer: 5s × 48000Hz × 2 channels (enough for BPM detection)
        final int ESTIMATED_SAMPLES = 5 * 48000 * 2;
        float[] sampleBuffer = new float[ESTIMATED_SAMPLES];
        int sampleCount = 0;

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

            // Create decoder
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();

            // Decode audio
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean isEOS = false;
            long timeoutUs = 10000;

            // Limit to first 5 seconds for BPM detection (sufficient for D&B 160-190 BPM)
            long maxDurationUs = 5_000_000;  // 5 seconds in microseconds

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
                        if (presentationTimeUs > maxDurationUs) {
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
        return samples;
    }

    private float[] computeOnsetStrength(float[] audio, int sampleRate) {
        // Simple onset strength using energy (RMS) in frames
        int frameSize = 2048;
        int hopSize = HOP_LENGTH;
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
            fftData[2 * i + 1] = 0;  // Imaginary part is 0 for power spectrum
        }

        // Perform inverse FFT
        fft.complexInverse(fftData, true);

        // Extract real part (autocorrelation) and normalize
        int maxLag = Math.min(n, 1000);  // Same limit as before
        float[] autocorr = new float[maxLag];
        float normFactor = fftData[0] > 0 ? 1.0f / fftData[0] : 1.0f;

        for (int i = 0; i < maxLag; i++) {
            // Real part is at even indices
            autocorr[i] = fftData[2 * i] * normFactor;
        }

        return autocorr;
    }

    private float findBestTempo(float[] autocorr, int sampleRate) {
        // Calculate lag range for 160-190 BPM
        int minLag = (int) ((60.0f / MAX_BPM) * sampleRate / HOP_LENGTH);
        int maxLag = (int) ((60.0f / MIN_BPM) * sampleRate / HOP_LENGTH);

        // Make sure lags are within bounds
        minLag = Math.max(1, minLag);
        maxLag = Math.min(autocorr.length - 1, maxLag);

        List<LagScore> candidates = new ArrayList<>();

        // Score each candidate lag using multi-harmonic approach
        for (int lag = minLag; lag <= maxLag; lag++) {
            float score = 0;
            int count = 0;

            // Check harmonics
            for (int i = 1; i <= MAX_HARMONICS; i++) {
                int harmonicLag = i * lag;
                if (harmonicLag < autocorr.length) {
                    score += autocorr[harmonicLag];
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
            return 175.0f;  // Default
        }

        // Get best lag and original tempo
        int bestLag = candidates.get(0).lag;
        int originalLag = bestLag;
        float tempo = 60.0f / (bestLag * HOP_LENGTH / (float) sampleRate);
        float originalTempo = tempo;

        // Half-time detection: if tempo < 100, try doubling it
        if (tempo < 100) {
            float doubleTempo = tempo * 2;
            int halfLag = bestLag / 2;

            if (doubleTempo >= MIN_BPM && doubleTempo <= MAX_BPM && halfLag >= minLag) {
                // Calculate score at half lag
                float scoreHalf = 0;
                int countHalf = 0;
                for (int i = 1; i <= MAX_HARMONICS; i++) {
                    int harmonicLag = i * halfLag;
                    if (harmonicLag < autocorr.length) {
                        scoreHalf += autocorr[harmonicLag];
                        countHalf++;
                    }
                }
                if (countHalf > 0) scoreHalf /= countHalf;

                // Calculate score at original lag
                float scoreOriginal = 0;
                int countOriginal = 0;
                for (int i = 1; i <= MAX_HARMONICS; i++) {
                    int harmonicLag = i * originalLag;
                    if (harmonicLag < autocorr.length) {
                        scoreOriginal += autocorr[harmonicLag];
                        countOriginal++;
                    }
                }
                if (countOriginal > 0) scoreOriginal /= countOriginal;

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
                // Calculate score at double lag
                float scoreDouble = 0;
                int countDouble = 0;
                for (int i = 1; i <= Math.min(4, MAX_HARMONICS); i++) {
                    int harmonicLag = i * doubleLag;
                    if (harmonicLag < autocorr.length) {
                        scoreDouble += autocorr[harmonicLag];
                        countDouble++;
                    }
                }
                if (countDouble > 0) scoreDouble /= countDouble;

                // Calculate score at original lag
                float scoreOriginal = 0;
                int countOriginal = 0;
                for (int i = 1; i <= Math.min(4, MAX_HARMONICS); i++) {
                    int harmonicLag = i * originalLag;
                    if (harmonicLag < autocorr.length) {
                        scoreOriginal += autocorr[harmonicLag];
                        countOriginal++;
                    }
                }
                if (countOriginal > 0) scoreOriginal /= countOriginal;

                // Use halved tempo if double-lag score is reasonable
                if (scoreDouble > scoreOriginal * 0.8f) {
                    Log.d(TAG, String.format("Double-time correction: %.1f BPM -> %.1f BPM (half-time feel)", tempo, halfTempo));
                    tempo = halfTempo;
                    bestLag = doubleLag;
                }
            }
        }

        // Clamp to expected range
        tempo = Math.max(MIN_BPM, Math.min(MAX_BPM, tempo));

        return tempo;
    }
}