package com.ece420.lab1;

import android.content.Context;
import android.util.Log;

import com.ece420.lab1.dsp.SOLATimeStretcher;

import java.io.File;

// preprocesses tracks by time stretching to 175 bpm
public class TrackPreprocessor {

    private static final String TAG = "TrackPreprocessor";
    private static final float TARGET_BPM = 175.0f;
    private static final int SAMPLE_RATE = 44100;
    private static final int READ_CHUNK_SIZE = 10 * SAMPLE_RATE;

    public interface ProgressCallback {
        void onProgress(float progress);
        void onStatusUpdate(String status);
    }

    public static class PreprocessResult {
        public final String stretchedFilePath;
        public final float originalBpm;
        public final float targetBpm;
        public final long durationMs;
        public final float phase;
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

    public static PreprocessResult preprocessTrack(Context context, String inputPath,
                                                    float originalBpm, ProgressCallback callback) {
        long startTime = System.currentTimeMillis();
        float stretchFactor = originalBpm / TARGET_BPM;

        File inputFile = new File(inputPath);
        String baseName = inputFile.getName().replaceFirst("[.][^.]+$", "");
        String outputFileName = baseName + "_175bpm.wav";
        File outputFile = new File(context.getFilesDir(), outputFileName);
        String outputPath = outputFile.getAbsolutePath();

        if (Math.abs(stretchFactor - 1.0f) < 0.01f) {
            return decodeToWav(inputPath, outputPath, callback);
        }

        AudioChunkReader reader = null;
        StreamingWavWriter writer = null;

        try {
            if (callback != null) {
                callback.onStatusUpdate("Loading audio...");
                callback.onProgress(0.0f);
            }

            reader = new AudioChunkReader(inputPath, READ_CHUNK_SIZE);
            int inputSampleRate = reader.getSampleRate();
            int channels = reader.getChannelCount();
            long totalSamples = reader.getTotalSamples();

            long monoSamples = (channels == 2) ? totalSamples / 2 : totalSamples;
            if (inputSampleRate != SAMPLE_RATE) {
                monoSamples = (long) (monoSamples * ((double) SAMPLE_RATE / inputSampleRate));
            }

            float[] allAudio = new float[(int) monoSamples + READ_CHUNK_SIZE];
            int totalRead = 0;

            while (!reader.isEOF()) {
                float[] chunk = reader.readNextChunk();
                if (chunk == null || chunk.length == 0) break;

                if (channels == 2) chunk = stereoToMono(chunk);
                if (inputSampleRate != SAMPLE_RATE) chunk = resample(chunk, inputSampleRate, SAMPLE_RATE);

                if (totalRead + chunk.length > allAudio.length) {
                    float[] newBuffer = new float[allAudio.length * 2];
                    System.arraycopy(allAudio, 0, newBuffer, 0, totalRead);
                    allAudio = newBuffer;
                }
                System.arraycopy(chunk, 0, allAudio, totalRead, chunk.length);
                totalRead += chunk.length;

                if (callback != null) callback.onProgress(0.3f * totalRead / monoSamples);
            }
            reader.close();
            reader = null;

            float[] audio = new float[totalRead];
            System.arraycopy(allAudio, 0, audio, 0, totalRead);
            allAudio = null;

            if (callback != null) {
                callback.onStatusUpdate("Stretching audio...");
                callback.onProgress(0.4f);
            }

            SOLATimeStretcher stretcher = new SOLATimeStretcher();
            float[] stretched = stretcher.stretch(audio, stretchFactor);
            audio = null;

            if (callback != null) callback.onProgress(0.7f);

            // detect beat phase
            if (callback != null) callback.onStatusUpdate("Detecting beats...");
            float phase = 0.0f;
            try {
                SimpleBPMDetector bpmDetector = new SimpleBPMDetector();
                int phaseSamples = Math.min(10 * SAMPLE_RATE, stretched.length);
                float[] phaseAudio = new float[phaseSamples];
                System.arraycopy(stretched, 0, phaseAudio, 0, phaseSamples);

                SimpleBPMDetector.BeatGrid beatGrid = bpmDetector.detectBeatGrid(phaseAudio, SAMPLE_RATE);
                phase = beatGrid.phase;
            } catch (Exception e) {
                Log.w(TAG, "Phase detection failed", e);
            }

            if (callback != null) callback.onProgress(0.8f);

            if (callback != null) callback.onStatusUpdate("Saving...");

            writer = new StreamingWavWriter();
            writer.open(outputPath, SAMPLE_RATE, 1);
            writer.writeChunk(stretched);
            writer.close();
            writer = null;

            if (callback != null) callback.onProgress(1.0f);

            long durationMs = System.currentTimeMillis() - startTime;
            return PreprocessResult.success(outputPath, originalBpm, TARGET_BPM, durationMs, phase);

        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Out of memory", e);
            if (outputFile.exists()) outputFile.delete();
            return PreprocessResult.failure("Track too large for memory");

        } catch (Exception e) {
            Log.e(TAG, "Error preprocessing", e);
            if (outputFile.exists()) outputFile.delete();
            return PreprocessResult.failure("Preprocessing failed: " + e.getMessage());

        } finally {
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        }
    }

    private static PreprocessResult decodeToWav(String inputPath, String outputPath, ProgressCallback callback) {
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

                if (channels == 2) chunk = stereoToMono(chunk);
                if (inputSampleRate != SAMPLE_RATE) chunk = resample(chunk, inputSampleRate, SAMPLE_RATE);

                writer.writeChunk(chunk);
                samplesProcessed += chunk.length;

                if (callback != null && totalSamples > 0) {
                    callback.onProgress((float) samplesProcessed / totalSamples);
                }
            }

            writer.close();
            reader.close();

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
                Log.w(TAG, "Phase detection failed", e);
            }

            long durationMs = System.currentTimeMillis() - startTime;
            return PreprocessResult.success(outputPath, TARGET_BPM, TARGET_BPM, durationMs, phase);

        } catch (Exception e) {
            Log.e(TAG, "Error decoding", e);
            new File(outputPath).delete();
            return PreprocessResult.failure("Decode failed: " + e.getMessage());
        } finally {
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        }
    }

    private static float[] stereoToMono(float[] stereo) {
        if (stereo.length % 2 != 0) return stereo;

        float[] mono = new float[stereo.length / 2];
        for (int i = 0; i < mono.length; i++) {
            mono[i] = (stereo[i * 2] + stereo[i * 2 + 1]) / 2.0f;
        }
        return mono;
    }

    private static float[] resample(float[] input, int inputRate, int outputRate) {
        if (inputRate == outputRate) return input;

        double ratio = (double) outputRate / inputRate;
        int outputLength = (int) (input.length * ratio);
        float[] output = new float[outputLength];

        for (int i = 0; i < outputLength; i++) {
            double srcPos = i / ratio;
            int srcIdx = (int) srcPos;
            double frac = srcPos - srcIdx;

            if (srcIdx + 1 < input.length) {
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

    public static boolean hasPreprocessedFile(Context context, String inputPath) {
        File inputFile = new File(inputPath);
        String baseName = inputFile.getName().replaceFirst("[.][^.]+$", "");
        String outputFileName = baseName + "_175bpm.wav";
        File outputFile = new File(context.getFilesDir(), outputFileName);
        return outputFile.exists() && outputFile.length() > 44;
    }

    public static String getPreprocessedPath(Context context, String inputPath) {
        File inputFile = new File(inputPath);
        String baseName = inputFile.getName().replaceFirst("[.][^.]+$", "");
        String outputFileName = baseName + "_175bpm.wav";
        return new File(context.getFilesDir(), outputFileName).getAbsolutePath();
    }

    public static boolean deletePreprocessedFile(Context context, String inputPath) {
        String preprocessedPath = getPreprocessedPath(context, inputPath);
        File file = new File(preprocessedPath);
        return file.exists() && file.delete();
    }
}
