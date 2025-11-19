package com.ece420.lab1;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

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

    public SimpleBPMDetector() {
        // Constructor
    }

    public float detectBPM(String filePath) {
        Log.d(TAG, "Detecting BPM for: " + filePath);

        try {
            // Step 1: Decode audio file to PCM
            float[] audioSamples = decodeAudioFile(filePath);
            if (audioSamples == null || audioSamples.length == 0) {
                Log.e(TAG, "Failed to decode audio");
                return 175.0f;  // Default
            }

            // Step 2: Compute onset strength
            float[] onsetStrength = computeOnsetStrength(audioSamples, 44100);

            // Step 3: Apply half-wave rectification
            float[] onsetHWR = halfWaveRectify(onsetStrength);

            // Step 4: Compute autocorrelation
            float[] autocorr = computeAutocorrelation(onsetHWR);

            // Step 5: Find best tempo using multi-harmonic scoring
            float bpm = findBestTempo(autocorr, 44100);

            Log.d(TAG, "Detected BPM: " + bpm);
            return bpm;

        } catch (Exception e) {
            Log.e(TAG, "Error detecting BPM", e);
            return 175.0f;  // Default fallback
        }
    }

    private float[] decodeAudioFile(String filePath) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        List<Float> allSamples = new ArrayList<>();

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

            // Limit to first 30 seconds for BPM detection
            long maxDurationUs = 30_000_000;  // 30 seconds in microseconds

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

                    // Convert to float samples
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.order(ByteOrder.LITTLE_ENDIAN);
                        outputBuffer.rewind();

                        // Assuming 16-bit PCM
                        ShortBuffer shortBuffer = outputBuffer.asShortBuffer();
                        while (shortBuffer.hasRemaining()) {
                            short sample = shortBuffer.get();
                            float normalizedSample = sample / 32768.0f;
                            allSamples.add(normalizedSample);
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

        // Convert to array
        float[] samples = new float[allSamples.size()];
        for (int i = 0; i < allSamples.size(); i++) {
            samples[i] = allSamples.get(i);
        }

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

    private float[] computeAutocorrelation(float[] signal) {
        // Compute autocorrelation for all lags up to signal length
        int maxLag = Math.min(signal.length, 1000);  // Limit for performance
        float[] autocorr = new float[maxLag];

        for (int lag = 0; lag < maxLag; lag++) {
            float sum = 0;
            int count = 0;

            for (int i = 0; i < signal.length - lag; i++) {
                sum += signal[i] * signal[i + lag];
                count++;
            }

            if (count > 0) {
                autocorr[lag] = sum / count;
            }
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