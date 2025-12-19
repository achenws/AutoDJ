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

// extracts waveform data from audio files
public class WaveformExtractor {
    private static final String TAG = "WaveformExtractor";

    // extract waveform for visualization
    public static float[] extractWaveform(String filePath, int targetPoints) {
        try {
            float[] allSamples = decodeAudioFile(filePath);
            if (allSamples == null || allSamples.length == 0) {
                Log.e(TAG, "Failed to decode audio");
                return null;
            }
            return downsampleForVisualization(allSamples, targetPoints);
        } catch (Exception e) {
            Log.e(TAG, "Error extracting waveform", e);
            return null;
        }
    }

    // sample positions across track for fast waveform preview
    private static float[] decodeAudioFile(String filePath) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;

        final int TARGET_SAMPLES = 500;
        List<Float> peakSamples = new ArrayList<>();

        try {
            extractor.setDataSource(filePath);

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
            long durationUs = format.getLong(MediaFormat.KEY_DURATION);

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long timeoutUs = 10000;
            long seekIntervalUs = durationUs / TARGET_SAMPLES;

            for (int sampleIdx = 0; sampleIdx < TARGET_SAMPLES; sampleIdx++) {
                long seekTimeUs = sampleIdx * seekIntervalUs;
                extractor.seekTo(seekTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

                boolean gotSample = false;
                float maxPeak = 0;
                int framesDecoded = 0;
                int maxFramesToDecode = 3;

                while (!gotSample && framesDecoded < maxFramesToDecode) {
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

                    int outputIndex = decoder.dequeueOutputBuffer(info, timeoutUs);
                    if (outputIndex >= 0) {
                        ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);

                        if (outputBuffer != null && info.size > 0) {
                            outputBuffer.order(ByteOrder.LITTLE_ENDIAN);
                            outputBuffer.rewind();

                            ShortBuffer shortBuffer = outputBuffer.asShortBuffer();
                            while (shortBuffer.hasRemaining()) {
                                short sample = shortBuffer.get();
                                float absValue = Math.abs(sample / 32768.0f);
                                if (absValue > maxPeak) maxPeak = absValue;
                            }

                            gotSample = true;
                            framesDecoded++;
                        }
                        decoder.releaseOutputBuffer(outputIndex, false);
                    }
                }
                peakSamples.add(maxPeak);
            }

        } catch (IOException e) {
            Log.e(TAG, "Error decoding audio", e);
            return null;
        } finally {
            if (decoder != null) {
                try { decoder.stop(); decoder.release(); } catch (Exception ignored) {}
            }
            extractor.release();
        }

        float[] samples = new float[peakSamples.size()];
        for (int i = 0; i < peakSamples.size(); i++) {
            samples[i] = peakSamples.get(i);
        }
        return samples;
    }

    // downsample by taking peak values in windows
    private static float[] downsampleForVisualization(float[] samples, int targetPoints) {
        if (samples.length <= targetPoints) return samples;

        float[] downsampled = new float[targetPoints];
        int windowSize = samples.length / targetPoints;

        for (int i = 0; i < targetPoints; i++) {
            int start = i * windowSize;
            int end = Math.min(start + windowSize, samples.length);

            float maxPos = 0, maxNeg = 0;
            for (int j = start; j < end; j++) {
                if (samples[j] > maxPos) maxPos = samples[j];
                if (samples[j] < maxNeg) maxNeg = samples[j];
            }

            downsampled[i] = (Math.abs(maxPos) > Math.abs(maxNeg)) ? maxPos : maxNeg;
        }
        return downsampled;
    }
}
