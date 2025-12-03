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

    // Constants matching the Python implementation
    private static final int HOP_LENGTH = 512;
    private static final int MIN_BPM = 160;
    private static final int MAX_BPM = 190;
    private static final int WINDOW_SIZE = 16;  // For moving average
    private static final int MAX_HARMONICS = 8;  // Check up to 8 harmonics

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

    /**
     * Container for cue point detection results.
     * Cue points are sample positions optimal for DJ transitions.
     */
    public static class CuePoints {
        public final long cueInSample;   // Where to start mixing in (for incoming track)
        public final long cueOutSample;  // Where to start fading out (for outgoing track)

        public CuePoints(long cueIn, long cueOut) {
            this.cueInSample = cueIn;
            this.cueOutSample = cueOut;
        }
    }

    public SimpleBPMDetector() {
        // Constructor
    }

    public float detectBPM(String filePath) {
        Log.d(TAG, "Detecting BPM for: " + filePath);

        try {
            // Step 1: Decode audio file to PCM (only 5 seconds for fast BPM detection)
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

    /**
     * Detect BPM from already-decoded audio samples.
     * Use this to avoid duplicate decoding when samples are already available.
     *
     * @param audioSamples Decoded audio samples (float array)
     * @param sampleRate   Sample rate in Hz
     * @return Detected BPM, or 175.0f on error
     */
    public float detectBPMFromSamples(float[] audioSamples, int sampleRate) {
        Log.d(TAG, "Detecting BPM from " + audioSamples.length + " samples");

        try {
            if (audioSamples == null || audioSamples.length == 0) {
                Log.e(TAG, "No audio samples provided");
                return 175.0f;  // Default
            }

            // BPM detection only needs first ~5 seconds of audio
            // Limit to 5 seconds worth of samples for efficiency
            int maxSamples = sampleRate * 5 * 2;  // 5 seconds, stereo
            float[] samples = audioSamples;
            if (audioSamples.length > maxSamples) {
                samples = new float[maxSamples];
                System.arraycopy(audioSamples, 0, samples, 0, maxSamples);
            }

            // Step 2: Compute onset strength
            float[] onsetStrength = computeOnsetStrength(samples, sampleRate);

            // Step 3: Apply half-wave rectification
            float[] onsetHWR = halfWaveRectify(onsetStrength);

            // Step 4: Compute autocorrelation
            float[] autocorr = computeAutocorrelation(onsetHWR);

            // Step 5: Find best tempo using multi-harmonic scoring
            float bpm = findBestTempo(autocorr, sampleRate);

            Log.d(TAG, "Detected BPM: " + bpm);
            return bpm;

        } catch (Exception e) {
            Log.e(TAG, "Error detecting BPM from samples", e);
            return 175.0f;  // Default fallback
        }
    }

    /**
     * Fast cue point detection using pre-extracted waveform data.
     * This avoids re-decoding the audio file.
     *
     * @param waveformData Pre-extracted waveform (amplitude values)
     * @param bpm          Detected BPM of the track
     * @param durationMs   Total track duration in milliseconds
     * @return CuePoints with normalized positions (0.0 to 1.0)
     */
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
                    // Also check next bar to avoid transient dips (matching Python)
                    if (bar + 1 < numBars && barEnergies[bar + 1] < 0.8f * meanEnergy) {
                        cueInNorm = (float) bar / numBars;
                        Log.d(TAG, "Found cue-in at bar " + bar);
                        break;
                    }
                }
            }

            // Find cue-out: high energy bar in second half
            int endSearchBar = numBars - 16;  // Stop 16 bars before end (matching Python)
            int startMidSearch = Math.max(numBars / 2, (int)(cueInNorm * numBars) + 8);

            float cueOutNorm = defaultCueOut;
            for (int bar = startMidSearch; bar < endSearchBar; bar++) {
                if (barEnergies[bar] > meanEnergy) {
                    cueOutNorm = (float) bar / numBars;
                    Log.d(TAG, "Found cue-out at bar " + bar);
                    break;
                }
            }

            // Ensure minimum 8 bars between cue-in and cue-out (matching Python)
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

    /**
     * Find optimal cue points for DJ transitions based on energy analysis.
     *
     * Algorithm (ported from Python AutoDJ):
     * 1. Compute RMS energy per bar using onset strength frames
     * 2. Cue-In: Find low-energy bar (< 0.7× mean) starting ~30s into track
     * 3. Cue-Out: Find high-energy bar (> mean) in second half
     * 4. Both snap to bar boundaries
     *
     * @param audioSamples Full decoded audio samples
     * @param sampleRate   Sample rate in Hz
     * @param bpm          Detected BPM of the track
     * @return CuePoints with sample positions, or defaults if detection fails
     */
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

            // Step 1: Compute onset strength (RMS energy per frame)
            float[] onsetStrength = computeOnsetStrength(audioSamples, sampleRate);
            if (onsetStrength.length < 10) {
                Log.w(TAG, "Track too short for cue point detection");
                return new CuePoints(defaultCueIn, defaultCueOut);
            }

            // Step 2: Calculate bar dimensions
            // Bar duration in seconds (4 beats per bar in 4/4 time)
            float barDurationSec = 4 * 60.0f / bpm;
            // Samples per bar
            int barDurationSamples = (int) (barDurationSec * sampleRate);
            // Frames per bar (onset strength uses HOP_LENGTH hop size)
            int framesPerBar = barDurationSamples / HOP_LENGTH;
            if (framesPerBar < 1) framesPerBar = 1;

            // Calculate number of complete bars
            int numBars = onsetStrength.length / framesPerBar;
            if (numBars < 4) {
                Log.w(TAG, "Track has only " + numBars + " bars, using defaults");
                return new CuePoints(defaultCueIn, defaultCueOut);
            }

            Log.d(TAG, "Bar analysis: " + numBars + " bars, " + framesPerBar + " frames/bar");

            // Step 3: Compute per-bar energy
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

            // Step 4: Find cue-in point (low energy bar, ~30s into track)
            // Start searching from ~30 seconds in
            int startSearchBar = Math.max(1, (int) (30.0f / barDurationSec));
            startSearchBar = Math.min(startSearchBar, numBars / 4);  // Don't start past first quarter

            long cueInSample = defaultCueIn;
            for (int bar = startSearchBar; bar < Math.min(startSearchBar + 16, numBars / 2); bar++) {
                // Look for low energy bar (< 0.7 × mean) - indicates breakdown/intro section
                if (barEnergies[bar] < 0.7f * meanEnergy) {
                    // Also check next bar to avoid transient dips
                    if (bar + 1 < numBars && barEnergies[bar + 1] < 0.8f * meanEnergy) {
                        cueInSample = (long) (bar * barDurationSec * sampleRate);
                        Log.d(TAG, "Found cue-in at bar " + bar + " (energy=" + barEnergies[bar] + ")");
                        break;
                    }
                }
            }

            // Step 5: Find cue-out point (high energy bar in second half)
            int endSearchBar = numBars - 16;  // Stop 16 bars before end
            int startMidSearch = Math.max(numBars / 2, (int) (cueInSample / (barDurationSec * sampleRate)) + 8);

            long cueOutSample = defaultCueOut;
            for (int bar = startMidSearch; bar < endSearchBar && bar < numBars - 4; bar++) {
                // Look for high energy bar (> mean) - indicates main section
                if (barEnergies[bar] > meanEnergy) {
                    cueOutSample = (long) (bar * barDurationSec * sampleRate);
                    Log.d(TAG, "Found cue-out at bar " + bar + " (energy=" + barEnergies[bar] + ")");
                    break;
                }
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

    /**
     * Compute autocorrelation using FFT (Wiener-Khinchin theorem).
     * This is O(n log n) instead of O(n²) for the direct method.
     *
     * Autocorrelation(x) = IFFT(|FFT(x)|²)
     */
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
        int minLag = (int) ((60.0f / MAX_BPM) * sampleRate / HOP_LENGTH);  // ~29
        int maxLag = (int) ((60.0f / MIN_BPM) * sampleRate / HOP_LENGTH);  // ~34

        // Make sure lags are within bounds
        minLag = Math.max(1, minLag);
        maxLag = Math.min(autocorr.length - 1, maxLag);

        List<LagScore> candidates = new ArrayList<>();

        // Score each candidate lag using multi-harmonic approach
        for (int lag = minLag; lag <= maxLag; lag++) {
            float score = 0;
            int count = 0;

            // Check harmonics (matching Python implementation)
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

        // Sort candidates by score
        Collections.sort(candidates);

        if (candidates.isEmpty()) {
            return 175.0f;  // Default
        }

        // Get best lag
        int bestLag = candidates.get(0).lag;

        // Convert lag to BPM
        float tempo = 60.0f / (bestLag * HOP_LENGTH / (float) sampleRate);

        // Check for half-time detection (matching Python logic)
        if (tempo < 100 && candidates.size() > 1) {
            // Try doubling the tempo
            float doubleTempo = tempo * 2;
            if (doubleTempo >= MIN_BPM && doubleTempo <= MAX_BPM) {
                int halfLag = bestLag / 2;

                // Check if half-lag has good score
                for (LagScore candidate : candidates) {
                    if (Math.abs(candidate.lag - halfLag) <= 1) {
                        // Compare scores
                        if (candidate.score > candidates.get(0).score * 0.4f) {
                            tempo = doubleTempo;
                            break;
                        }
                    }
                }
            }
        }

        // Check for double-time detection
        if (tempo > 185 && candidates.size() > 1) {
            // Try halving the tempo
            float halfTempo = tempo / 2;
            if (halfTempo >= 80 && halfTempo <= 95) {
                int doubleLag = bestLag * 2;

                // Check if double-lag has good score
                for (LagScore candidate : candidates) {
                    if (Math.abs(candidate.lag - doubleLag) <= 1) {
                        // Compare scores
                        if (candidate.score > candidates.get(0).score * 0.8f) {
                            tempo = halfTempo * 2;  // Keep in 160-190 range
                            break;
                        }
                    }
                }
            }
        }

        // Clamp to expected range
        tempo = Math.max(MIN_BPM, Math.min(MAX_BPM, tempo));

        return tempo;
    }
}