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
import java.util.List;

public class WaveformExtractor {
    private static final String TAG = "WaveformExtractor";

    /**
     * Extract waveform data from an audio file for visualization
     * @param filePath Path to the audio file
     * @param targetPoints Number of data points to generate for the waveform (typically 1000-2000)
     * @return Array of normalized amplitude values (-1.0 to 1.0), or null on error
     */
    public static float[] extractWaveform(String filePath, int targetPoints) {
        Log.d(TAG, "extractWaveform called for: " + filePath);

        try {
            // Step 1: Decode the entire audio file to PCM samples
            Log.d(TAG, "Starting audio decode...");
            float[] allSamples = decodeAudioFile(filePath);

            if (allSamples == null) {
                Log.e(TAG, "decodeAudioFile returned null");
                return null;
            }

            if (allSamples.length == 0) {
                Log.e(TAG, "decodeAudioFile returned empty array");
                return null;
            }

            Log.d(TAG, "Successfully decoded " + allSamples.length + " samples, downsampling to " + targetPoints + " points");

            // Step 2: Downsample to target number of points for visualization
            float[] result = downsampleForVisualization(allSamples, targetPoints);
            Log.d(TAG, "Downsampling complete. Result length: " + (result != null ? result.length : "null"));
            return result;

        } catch (Exception e) {
            Log.e(TAG, "Error extracting waveform: " + e.getMessage(), e);
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Ultra-fast waveform generation using strategic seeking
     * Sample ~500 positions across the track for instant waveform preview
     */
    private static float[] decodeAudioFile(String filePath) {
        Log.d(TAG, "decodeAudioFile: Starting ultra-fast decode for " + filePath);
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;

        final int TARGET_SAMPLES = 500; // Sample 500 positions across the track
        List<Float> peakSamples = new ArrayList<>();

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

            // Get track duration
            long durationUs = format.getLong(MediaFormat.KEY_DURATION);
            int durationSec = (int) (durationUs / 1000000);
            Log.d(TAG, "Track duration: " + durationSec + " seconds, sampling " + TARGET_SAMPLES + " points");

            // Create decoder
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long timeoutUs = 10000;

            // Calculate seek interval
            long seekIntervalUs = durationUs / TARGET_SAMPLES;

            for (int sampleIdx = 0; sampleIdx < TARGET_SAMPLES; sampleIdx++) {
                // Seek to position
                long seekTimeUs = sampleIdx * seekIntervalUs;
                extractor.seekTo(seekTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

                // Decode a few frames at this position to get peak
                boolean gotSample = false;
                float maxPeak = 0;
                int framesDecoded = 0;
                int maxFramesToDecode = 3; // Decode up to 3 frames per position

                while (!gotSample && framesDecoded < maxFramesToDecode) {
                    // Input
                    int inputIndex = decoder.dequeueInputBuffer(timeoutUs);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);

                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            break;
                        } else {
                            long presentationTimeUs = extractor.getSampleTime();
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0);
                            extractor.advance();
                        }
                    }

                    // Output - extract peak from decoded frame
                    int outputIndex = decoder.dequeueOutputBuffer(info, timeoutUs);
                    if (outputIndex >= 0) {
                        ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);

                        if (outputBuffer != null && info.size > 0) {
                            outputBuffer.order(ByteOrder.LITTLE_ENDIAN);
                            outputBuffer.rewind();

                            // Extract peak from this frame
                            ShortBuffer shortBuffer = outputBuffer.asShortBuffer();
                            while (shortBuffer.hasRemaining()) {
                                short sample = shortBuffer.get();
                                float absValue = Math.abs(sample / 32768.0f);
                                if (absValue > maxPeak) {
                                    maxPeak = absValue;
                                }
                            }

                            gotSample = true;
                            framesDecoded++;
                        }

                        decoder.releaseOutputBuffer(outputIndex, false);
                    }
                }

                peakSamples.add(maxPeak);

                // Log progress every 100 samples
                if (sampleIdx % 100 == 0 && sampleIdx > 0) {
                    Log.d(TAG, "Sampled " + sampleIdx + "/" + TARGET_SAMPLES + " positions");
                }
            }

            Log.d(TAG, "Sampled " + peakSamples.size() + " positions across track");

        } catch (IOException e) {
            Log.e(TAG, "IOException while decoding audio: " + e.getMessage(), e);
            e.printStackTrace();
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected exception while decoding audio: " + e.getMessage(), e);
            e.printStackTrace();
            return null;
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                    decoder.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing decoder", e);
                }
            }
            extractor.release();
        }

        // Convert to array
        float[] samples = new float[peakSamples.size()];
        for (int i = 0; i < peakSamples.size(); i++) {
            samples[i] = peakSamples.get(i);
        }

        Log.d(TAG, "Ultra-fast decode complete: " + samples.length + " peak samples");
        return samples;
    }

    /**
     * Downsample audio data for visualization by taking peak values in windows
     */
    private static float[] downsampleForVisualization(float[] samples, int targetPoints) {
        if (samples.length <= targetPoints) {
            return samples;
        }

        float[] downsampled = new float[targetPoints];
        int windowSize = samples.length / targetPoints;

        for (int i = 0; i < targetPoints; i++) {
            int start = i * windowSize;
            int end = Math.min(start + windowSize, samples.length);

            // Use peak (maximum absolute value) in each window for better visualization
            float maxAbs = 0;
            for (int j = start; j < end; j++) {
                float absValue = Math.abs(samples[j]);
                if (absValue > maxAbs) {
                    maxAbs = absValue;
                }
            }

            // Preserve sign by checking which had larger magnitude
            float maxPos = 0, maxNeg = 0;
            for (int j = start; j < end; j++) {
                if (samples[j] > maxPos) maxPos = samples[j];
                if (samples[j] < maxNeg) maxNeg = samples[j];
            }

            // Use the value with larger absolute magnitude
            downsampled[i] = (Math.abs(maxPos) > Math.abs(maxNeg)) ? maxPos : maxNeg;
        }

        return downsampled;
    }
}
