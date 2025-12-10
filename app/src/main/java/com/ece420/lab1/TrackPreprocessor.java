package com.ece420.lab1;

import android.content.Context;
import android.util.Log;

import com.ece420.lab1.dsp.SOLATimeStretcher;

import java.io.File;
import java.io.IOException;

// Preprocesses tracks by time-stretching them to 175 BPM
public class TrackPreprocessor {

    private static final String TAG = "TrackPreprocessor";

    // Processing parameters
    private static final float TARGET_BPM = 175.0f;
    private static final int SAMPLE_RATE = 44100;
    private static final int READ_CHUNK_SIZE = 10 * SAMPLE_RATE;  // 10 seconds for reading

    // Callback for progress updates
    public interface ProgressCallback {
        void onProgress(float progress);  // 0.0 to 1.0
        void onStatusUpdate(String status);
    }

    // Result of preprocessing operation
    public static class PreprocessResult {
        public final String stretchedFilePath;
        public final float originalBpm;
        public final float targetBpm;
        public final long durationMs;
        public final float phase;           // Beat phase offset in seconds (for beat alignment)
        public final boolean success;
        public final String errorMessage;

        private PreprocessResult(String path, float origBpm, float targetBpm,
                                 long durationMs, float phase, boolean success, String error) {
            this.stretchedFilePath = path;
            this.originalBpm = origBpm;
            this.targetBpm = targetBpm;
            this.durationMs = durationMs;
            this.phase = phase;
            this.success = success;
            this.errorMessage = error;
        }

        public static PreprocessResult success(String path, float origBpm, float targetBpm,
                                                long durationMs, float phase) {
            return new PreprocessResult(path, origBpm, targetBpm, durationMs, phase, true, null);
        }

        public static PreprocessResult failure(String error) {
            return new PreprocessResult(null, 0, 0, 0, 0, false, error);
        }
    }

    // Preprocess a track by time-stretching it to 175 BPM
    public static PreprocessResult preprocessTrack(Context context, String inputPath,
                                                    float originalBpm, ProgressCallback callback) {
        long startTime = System.currentTimeMillis();

        // Calculate stretch factor
        float stretchFactor = originalBpm / TARGET_BPM;

        // Generate output path in app files directory
        File inputFile = new File(inputPath);
        String baseName = inputFile.getName().replaceFirst("[.][^.]+$", "");
        String outputFileName = baseName + "_175bpm.wav";
        File outputFile = new File(context.getFilesDir(), outputFileName);
        String outputPath = outputFile.getAbsolutePath();

        // Skip if no stretching needed
        if (Math.abs(stretchFactor - 1.0f) < 0.01f) {
            return decodeToWav(inputPath, outputPath, callback);
        }

        AudioChunkReader reader = null;
        StreamingWavWriter writer = null;

        try {
            // Phase 1: Load entire audio into memory
            if (callback != null) {
                callback.onStatusUpdate("Loading audio...");
                callback.onProgress(0.0f);
            }

            reader = new AudioChunkReader(inputPath, READ_CHUNK_SIZE);
            int inputSampleRate = reader.getSampleRate();
            int channels = reader.getChannelCount();
            long totalSamples = reader.getTotalSamples();

            // Estimate mono sample count for allocation
            long monoSamples = (channels == 2) ? totalSamples / 2 : totalSamples;
            if (inputSampleRate != SAMPLE_RATE) {
                monoSamples = (long) (monoSamples * ((double) SAMPLE_RATE / inputSampleRate));
            }

            // Read all audio into memory
            float[] allAudio = new float[(int) monoSamples + READ_CHUNK_SIZE];
            int totalRead = 0;

            while (!reader.isEOF()) {
                float[] chunk = reader.readNextChunk();
                if (chunk == null || chunk.length == 0) break;

                // Convert stereo to mono
                if (channels == 2) {
                    chunk = stereoToMono(chunk);
                }

                // Resample if needed
                if (inputSampleRate != SAMPLE_RATE) {
                    chunk = resample(chunk, inputSampleRate, SAMPLE_RATE);
                }

                // Append to buffer
                if (totalRead + chunk.length > allAudio.length) {
                    float[] newBuffer = new float[allAudio.length * 2];
                    System.arraycopy(allAudio, 0, newBuffer, 0, totalRead);
                    allAudio = newBuffer;
                }
                System.arraycopy(chunk, 0, allAudio, totalRead, chunk.length);
                totalRead += chunk.length;

                if (callback != null) {
                    callback.onProgress(0.3f * totalRead / monoSamples);
                }
            }
            reader.close();
            reader = null;

            // Trim to actual size
            float[] audio = new float[totalRead];
            System.arraycopy(allAudio, 0, audio, 0, totalRead);
            allAudio = null; // Free memory

            // Phase 2: Apply SOLA time stretching to entire audio
            if (callback != null) {
                callback.onStatusUpdate("Stretching audio...");
                callback.onProgress(0.4f);
            }

            SOLATimeStretcher stretcher = new SOLATimeStretcher();
            float[] stretched = stretcher.stretch(audio, stretchFactor);
            audio = null; // Free memory

            if (callback != null) {
                callback.onProgress(0.7f);
            }

            // Phase 2.5: Detect beat phase on stretched audio (for beat alignment during mixing)
            if (callback != null) {
                callback.onStatusUpdate("Detecting beats...");
            }
            float phase = 0.0f;
            try {
                SimpleBPMDetector bpmDetector = new SimpleBPMDetector();
                // Use first 10 seconds for phase detection (consistent with mixer expectations)
                int phaseSamples = Math.min(10 * SAMPLE_RATE, stretched.length);
                float[] phaseAudio = new float[phaseSamples];
                System.arraycopy(stretched, 0, phaseAudio, 0, phaseSamples);

                SimpleBPMDetector.BeatGrid beatGrid = bpmDetector.detectBeatGrid(phaseAudio, SAMPLE_RATE);
                phase = beatGrid.phase;
            } catch (Exception e) {
                Log.w(TAG, "Phase detection failed, using 0", e);
                phase = 0.0f;
            }

            if (callback != null) {
                callback.onProgress(0.8f);
            }

            // Phase 3: Write output WAV
            if (callback != null) {
                callback.onStatusUpdate("Saving...");
            }

            writer = new StreamingWavWriter();
            writer.open(outputPath, SAMPLE_RATE, 1);
            writer.writeChunk(stretched);
            writer.close();
            writer = null;

            if (callback != null) {
                callback.onProgress(1.0f);
            }

            long durationMs = System.currentTimeMillis() - startTime;

            return PreprocessResult.success(outputPath, originalBpm, TARGET_BPM, durationMs, phase);

        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Out of memory during preprocessing", e);
            if (outputFile.exists()) outputFile.delete();
            return PreprocessResult.failure("Track too large for memory. Try a shorter track.");

        } catch (Exception e) {
            Log.e(TAG, "Error preprocessing track", e);
            if (outputFile.exists()) outputFile.delete();
            return PreprocessResult.failure("Preprocessing failed: " + e.getMessage());

        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Exception e) { /* ignore */ }
            }
            if (reader != null) {
                try { reader.close(); } catch (Exception e) { /* ignore */ }
            }
        }
    }

    // Decode audio file to WAV without stretching
    private static PreprocessResult decodeToWav(String inputPath, String outputPath,
                                                 ProgressCallback callback) {
        long startTime = System.currentTimeMillis();
        AudioChunkReader reader = null;
        StreamingWavWriter writer = null;

        try {
            reader = new AudioChunkReader(inputPath, READ_CHUNK_SIZE);
            int inputSampleRate = reader.getSampleRate();
            int channels = reader.getChannelCount();
            long totalSamples = reader.getTotalSamples();

            writer = new StreamingWavWriter();
            writer.open(outputPath, SAMPLE_RATE, 1);

            long samplesProcessed = 0;

            while (!reader.isEOF()) {
                float[] chunk = reader.readNextChunk();
                if (chunk == null) break;

                if (channels == 2) {
                    chunk = stereoToMono(chunk);
                }

                if (inputSampleRate != SAMPLE_RATE) {
                    chunk = resample(chunk, inputSampleRate, SAMPLE_RATE);
                }

                writer.writeChunk(chunk);
                samplesProcessed += chunk.length;

                if (callback != null && totalSamples > 0) {
                    callback.onProgress((float) samplesProcessed / totalSamples);
                }
            }

            writer.close();
            reader.close();

            // Detect phase from the output file
            float phase = 0.0f;
            try {
                AudioChunkReader phaseReader = new AudioChunkReader(outputPath);
                int phaseSamples = Math.min(10 * SAMPLE_RATE, (int)phaseReader.getTotalSamples());
                float[] phaseAudio = new float[phaseSamples];
                phaseReader.readSamples(phaseAudio, 0, phaseSamples);
                phaseReader.close();

                SimpleBPMDetector bpmDetector = new SimpleBPMDetector();
                SimpleBPMDetector.BeatGrid beatGrid = bpmDetector.detectBeatGrid(phaseAudio, SAMPLE_RATE);
                phase = beatGrid.phase;
            } catch (Exception e) {
                Log.w(TAG, "Phase detection failed, using 0", e);
            }

            long durationMs = System.currentTimeMillis() - startTime;
            return PreprocessResult.success(outputPath, TARGET_BPM, TARGET_BPM, durationMs, phase);

        } catch (Exception e) {
            Log.e(TAG, "Error decoding to WAV", e);
            new File(outputPath).delete();
            return PreprocessResult.failure("Decode failed: " + e.getMessage());
        } finally {
            if (writer != null) try { writer.close(); } catch (Exception e) { }
            if (reader != null) try { reader.close(); } catch (Exception e) { }
        }
    }

    // Convert stereo to mono
    private static float[] stereoToMono(float[] stereo) {
        if (stereo.length % 2 != 0) {
            return stereo;  // Not stereo
        }

        float[] mono = new float[stereo.length / 2];
        for (int i = 0; i < mono.length; i++) {
            mono[i] = (stereo[i * 2] + stereo[i * 2 + 1]) / 2.0f;
        }
        return mono;
    }

    // Simple linear resampling
    private static float[] resample(float[] input, int inputRate, int outputRate) {
        if (inputRate == outputRate) {
            return input;
        }

        double ratio = (double) outputRate / inputRate;
        int outputLength = (int) (input.length * ratio);
        float[] output = new float[outputLength];

        for (int i = 0; i < outputLength; i++) {
            double srcPos = i / ratio;
            int srcIdx = (int) srcPos;
            double frac = srcPos - srcIdx;

            if (srcIdx + 1 < input.length) {
                // Linear interpolation
                output[i] = (float) (input[srcIdx] * (1 - frac) + input[srcIdx + 1] * frac);
            } else if (srcIdx < input.length) {
                output[i] = input[srcIdx];
            }
        }

        return output;
    }

    public static float getTargetBpm() {
        return TARGET_BPM;
    }

    // Check if preprocessed file exists
    public static boolean hasPreprocessedFile(Context context, String inputPath) {
        File inputFile = new File(inputPath);
        String baseName = inputFile.getName().replaceFirst("[.][^.]+$", "");
        String outputFileName = baseName + "_175bpm.wav";
        File outputFile = new File(context.getFilesDir(), outputFileName);
        return outputFile.exists() && outputFile.length() > 44; // > WAV header size
    }

    // Get preprocessed file path
    public static String getPreprocessedPath(Context context, String inputPath) {
        File inputFile = new File(inputPath);
        String baseName = inputFile.getName().replaceFirst("[.][^.]+$", "");
        String outputFileName = baseName + "_175bpm.wav";
        return new File(context.getFilesDir(), outputFileName).getAbsolutePath();
    }

    // Delete preprocessed file
    public static boolean deletePreprocessedFile(Context context, String inputPath) {
        String preprocessedPath = getPreprocessedPath(context, inputPath);
        File file = new File(preprocessedPath);
        if (file.exists()) {
            return file.delete();
        }
        return false;
    }
}
