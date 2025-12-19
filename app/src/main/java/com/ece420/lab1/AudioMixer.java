package com.ece420.lab1;

import android.util.Log;

import com.ece420.lab1.dsp.DSPUtils;
import com.ece420.lab1.dsp.EQCrossfader;
import com.ece420.lab1.dsp.SOLATimeStretcher;

import java.io.File;

// mixes two audio tracks with different transition types
public class AudioMixer {

    private static final String TAG = "AudioMixer";

    public static final int TRANSITION_DJ = 0;
    public static final int TRANSITION_SIMPLE_FADE = 1;
    public static final int TRANSITION_OVERLAP = 2;

    private static final int SAMPLE_RATE = 44100;
    private static final float TARGET_BPM = 175.0f;
    private static final int DJ_FADE_BARS = 48;
    private static final int SIMPLE_FADE_BARS = 48;
    private static final int OVERLAP_BARS = 48;
    private static final float FALLBACK_FADE_SECONDS = 48 * 4 * 60.0f / 175.0f;

    public static class MixResult {
        public final String outputPath;
        public final long track1DurationMs;

        public MixResult(String outputPath, long track1DurationMs) {
            this.outputPath = outputPath;
            this.track1DurationMs = track1DurationMs;
        }
    }

    private SimpleBPMDetector bpmDetector;
    private SOLATimeStretcher timeStretcher;
    private EQCrossfader crossfader;

    public AudioMixer() {
        bpmDetector = new SimpleBPMDetector();
        timeStretcher = new SOLATimeStretcher();
        crossfader = new EQCrossfader(SAMPLE_RATE);
    }

    private String generateOutputFilename(String track1Name, String track2Name, int transitionType) {
        String name1 = sanitizeFilename(removeExtension(track1Name));
        String name2 = sanitizeFilename(removeExtension(track2Name));

        String transitionStr;
        switch (transitionType) {
            case TRANSITION_DJ: transitionStr = "DJ"; break;
            case TRANSITION_SIMPLE_FADE: transitionStr = "Fade"; break;
            case TRANSITION_OVERLAP: transitionStr = "Overlap"; break;
            default: transitionStr = "Mix";
        }

        return name1 + "_x_" + name2 + "_" + transitionStr + ".wav";
    }

    private String removeExtension(String filename) {
        if (filename == null) return "track";
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(0, lastDot) : filename;
    }

    private String sanitizeFilename(String name) {
        if (name == null || name.isEmpty()) return "track";
        String sanitized = name.replaceAll("[^a-zA-Z0-9\\-_]", "_");
        return sanitized.length() > 30 ? sanitized.substring(0, 30) : sanitized;
    }

    private String getFilenameFromPath(String path) {
        return path == null ? "track" : new File(path).getName();
    }

    // main mix entry point
    public MixResult mix(Track track1, Track track2, int transitionType, File outputDir) {
        if (transitionType == TRANSITION_SIMPLE_FADE) {
            return mixSimpleFadeStreaming(track1.getFilePath(), track2.getFilePath(), outputDir);
        } else if (transitionType == TRANSITION_OVERLAP) {
            return mixOverlapStreaming(track1.getFilePath(), track2.getFilePath(), outputDir);
        }

        // dj mode
        if (track1.isPreprocessed() && track2.isPreprocessed()) {
            return new MixResult(mixDJWithPreprocessed(track1, track2, outputDir), 0);
        }

        Log.w(TAG, "Tracks not preprocessed, using runtime stretching");
        return new MixResult(mixDJOptimized(track1.getFilePath(), track2.getFilePath(),
                track1.getCueOutSample(), track2.getCueInSample(), outputDir), 0);
    }

    // dj transition using preprocessed 175bpm files
    private String mixDJWithPreprocessed(Track track1, Track track2, File outputDir) {
        if (!track1.isPreprocessed() || !track2.isPreprocessed()) {
            Log.e(TAG, "DJ mode requires preprocessed tracks");
            return null;
        }

        String stretchedPath1 = track1.getStretchedFilePath();
        String stretchedPath2 = track2.getStretchedFilePath();

        if (stretchedPath1 == null || stretchedPath2 == null) {
            Log.e(TAG, "Preprocessed file paths are null");
            return null;
        }

        AudioChunkReader reader1 = null;
        AudioChunkReader reader2 = null;
        StreamingWavWriter writer = null;

        try {
            reader1 = new AudioChunkReader(stretchedPath1);
            reader2 = new AudioChunkReader(stretchedPath2);

            int sampleRate = reader1.getSampleRate();
            int channels = reader1.getChannelCount();
            long track1Samples = reader1.getTotalSamples();

            int fadeSamples = crossfader.calculateFadeDuration(DJ_FADE_BARS, TARGET_BPM, 4);

            float phase1 = track1.getPhase();
            float track1DurationSec = track1Samples / (float) (sampleRate * channels);
            float[] downbeats175 = generateDownbeatGridWithPhase(TARGET_BPM, track1DurationSec, phase1);

            long cueOut1 = track1.getCueOutSample();
            long cueIn2 = track2.getCueInSample();

            // calculate transition point snapped to downbeat
            long transitionPoint;
            if (cueOut1 > 0) {
                long rawTransition = Math.max(0, cueOut1 - fadeSamples);
                transitionPoint = snapToNearestDownbeat(rawTransition, downbeats175, sampleRate, channels);
            } else {
                long rawTransition = (long) (track1Samples * 0.8f) - fadeSamples;
                transitionPoint = snapToNearestDownbeat(Math.max(0, rawTransition), downbeats175, sampleRate, channels);
            }

            // read fade regions
            reader1.skip(transitionPoint);
            int fadeRegionLen = fadeSamples + (int) (5 * sampleRate);
            float[] track1FadeRegion = new float[fadeRegionLen];
            int track1FadeRead = reader1.readSamples(track1FadeRegion, 0, fadeRegionLen);
            track1FadeRegion = trimArray(track1FadeRegion, track1FadeRead);

            // beat alignment calculation
            long track2Samples = reader2.getTotalSamples();
            float phase2 = track2.getPhase();
            float beatPeriod = 60.0f / TARGET_BPM;

            float transitionTimeSec = transitionPoint / (float) (sampleRate * channels);
            float track1BeatPos = (transitionTimeSec - phase1) % beatPeriod;
            if (track1BeatPos < 0) track1BeatPos += beatPeriod;

            float cueIn2TimeSec = (cueIn2 > 0 ? cueIn2 : 0) / (float) (sampleRate * channels);
            float track2BeatPos = (cueIn2TimeSec - phase2) % beatPeriod;
            if (track2BeatPos < 0) track2BeatPos += beatPeriod;

            float adjustment = track1BeatPos - track2BeatPos;
            while (adjustment > beatPeriod / 2) adjustment -= beatPeriod;
            while (adjustment < -beatPeriod / 2) adjustment += beatPeriod;

            long adjustmentSamples = (long) (adjustment * sampleRate * channels);
            long alignedCueIn2 = Math.max(0, (cueIn2 > 0 ? cueIn2 : 0) + adjustmentSamples);

            reader2.skip(alignedCueIn2);
            float[] track2FadeRegion = new float[fadeRegionLen];
            int track2FadeRead = reader2.readSamples(track2FadeRegion, 0, fadeRegionLen);
            track2FadeRegion = trimArray(track2FadeRegion, track2FadeRead);

            // fine tune with onset correlation
            long fineTuneOffset = verifyAlignmentWithOnsetCorrelation(
                    track1FadeRegion, track2FadeRegion, 0, sampleRate, TARGET_BPM);

            int track1StartOffset = 0;
            int track2StartOffset = 0;
            if (fineTuneOffset > 0) {
                track2StartOffset = (int) Math.min(fineTuneOffset, track2FadeRegion.length / 4);
            } else if (fineTuneOffset < 0) {
                track1StartOffset = (int) Math.min(-fineTuneOffset, track1FadeRegion.length / 4);
            }

            int actualFadeSamples = fadeSamples;
            int outgoingLen = Math.min(actualFadeSamples, track1FadeRegion.length - track1StartOffset);
            int incomingLen = Math.min(actualFadeSamples, track2FadeRegion.length - track2StartOffset);

            float[] outgoing = DSPUtils.extractFrame(track1FadeRegion, track1StartOffset, outgoingLen);
            float[] incoming = DSPUtils.extractFrame(track2FadeRegion, track2StartOffset, incomingLen);

            float[] crossfaded = crossfader.crossfade(outgoing, incoming, Math.min(outgoingLen, incomingLen));

            track1FadeRegion = null;
            track2FadeRegion = null;

            // write output
            String outputFilename = generateOutputFilename(track1.getName(), track2.getName(), TRANSITION_DJ);
            String outputPath = new File(outputDir, outputFilename).getAbsolutePath();
            writer = new StreamingWavWriter();
            writer.open(outputPath, sampleRate, channels);

            reader1.close();
            reader1 = new AudioChunkReader(stretchedPath1);
            long samplesWritten = 0;
            while (samplesWritten < transitionPoint) {
                float[] chunk = reader1.readNextChunk();
                if (chunk == null) break;

                long remaining = transitionPoint - samplesWritten;
                if (chunk.length <= remaining) {
                    writer.writeChunk(chunk);
                    samplesWritten += chunk.length;
                } else {
                    writer.writeChunk(chunk, 0, (int) remaining);
                    samplesWritten += remaining;
                }
            }

            crossfader.applyPeakLimiter(crossfaded, 0.95f);
            writer.writeChunk(crossfaded);

            reader2.close();
            reader2 = new AudioChunkReader(stretchedPath2);
            long track2SkipTo = alignedCueIn2 + actualFadeSamples;
            reader2.skip(track2SkipTo);
            float[] chunk;
            while ((chunk = reader2.readNextChunk()) != null) {
                writer.writeChunk(chunk);
            }

            writer.close();
            writer = null;

            return outputPath;

        } catch (Exception e) {
            Log.e(TAG, "Error during preprocessed DJ mix", e);
            return null;
        } finally {
            try { if (reader1 != null) reader1.close(); } catch (Exception ignored) {}
            try { if (reader2 != null) reader2.close(); } catch (Exception ignored) {}
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        }
    }

    // dj transition with runtime time stretching
    private String mixDJOptimized(String track1Path, String track2Path,
            long track1CueOut, long track2CueIn, File outputDir) {
        AudioChunkReader reader1 = null;
        AudioChunkReader reader2 = null;
        StreamingWavWriter writer = null;

        try {
            reader1 = new AudioChunkReader(track1Path);
            reader2 = new AudioChunkReader(track2Path);

            int sampleRate = reader1.getSampleRate();
            int channels = reader1.getChannelCount();
            long track1Samples = reader1.getTotalSamples();

            // bpm detection
            long skipSamples = 30L * sampleRate * channels;
            if (track1Samples > skipSamples * 2) {
                reader1.skip(skipSamples);
            }

            int bpmSampleCount = Math.min((int) (10 * sampleRate * channels), (int) track1Samples);
            float[] bpmSamples1 = new float[bpmSampleCount];
            reader1.readSamples(bpmSamples1, 0, bpmSampleCount);
            float bpm1 = bpmDetector.detectBPMFromSamples(bpmSamples1, sampleRate);

            reader1.close();
            reader1 = new AudioChunkReader(track1Path);

            long skipSamples2 = 30L * sampleRate * channels;
            long track2Samples = reader2.getTotalSamples();
            if (track2Samples > skipSamples2 * 2) {
                reader2.skip(skipSamples2);
            }

            int bpmSampleCount2 = Math.min((int) (10 * sampleRate * channels), (int) track2Samples);
            float[] bpmSamples2 = new float[bpmSampleCount2];
            reader2.readSamples(bpmSamples2, 0, bpmSampleCount2);
            float bpm2 = bpmDetector.detectBPMFromSamples(bpmSamples2, sampleRate);

            // beat grid for downbeat alignment
            SimpleBPMDetector.BeatGrid beatGrid1 = null;
            try {
                float track1DurationSec = track1Samples / (float) (sampleRate * channels);
                beatGrid1 = generateBeatGridForDuration(bpm1, track1DurationSec, bpmSamples1, sampleRate);
            } catch (Exception e) {
                Log.e(TAG, "Error generating beat grid", e);
            }

            bpmSamples1 = null;
            bpmSamples2 = null;

            int fadeSamples = crossfader.calculateFadeDuration(DJ_FADE_BARS, TARGET_BPM, 4);
            int fadeRegionLen = fadeSamples + (int) (10 * sampleRate * channels);

            long transitionPoint;
            if (track1CueOut > 0) {
                transitionPoint = track1CueOut;
                if (beatGrid1 != null && beatGrid1.downbeats != null && beatGrid1.downbeats.length > 0) {
                    transitionPoint = snapToNearestDownbeat(transitionPoint, beatGrid1.downbeats, sampleRate, channels);
                }
            } else {
                transitionPoint = (long) (track1Samples * 0.8f);
                if (beatGrid1 != null && beatGrid1.downbeats != null && beatGrid1.downbeats.length > 0) {
                    transitionPoint = snapToNearestDownbeat(transitionPoint, beatGrid1.downbeats, sampleRate, channels);
                }
            }
            transitionPoint = Math.max(0, transitionPoint - fadeSamples);

            reader1.skip(transitionPoint);
            float[] track1FadeRegion = new float[fadeRegionLen];
            int track1FadeRead = reader1.readSamples(track1FadeRegion, 0, fadeRegionLen);
            track1FadeRegion = trimArray(track1FadeRegion, track1FadeRead);

            reader2.close();
            reader2 = new AudioChunkReader(track2Path);
            if (track2CueIn > 0) {
                reader2.skip(track2CueIn);
            }
            float[] track2FadeRegion = new float[fadeRegionLen];
            int track2FadeRead = reader2.readSamples(track2FadeRegion, 0, fadeRegionLen);
            track2FadeRegion = trimArray(track2FadeRegion, track2FadeRead);

            // time stretch fade regions
            float stretch1 = bpm1 / TARGET_BPM;
            float stretch2 = bpm2 / TARGET_BPM;

            float[] stretched1 = timeStretcher.stretch(track1FadeRegion, stretch1);
            track1FadeRegion = null;

            float[] stretched2 = timeStretcher.stretch(track2FadeRegion, stretch2);
            track2FadeRegion = null;

            int actualFadeSamples = crossfader.calculateFadeDuration(DJ_FADE_BARS, TARGET_BPM, 4);
            int outgoingLen = Math.min(actualFadeSamples, stretched1.length);
            int incomingLen = Math.min(actualFadeSamples, stretched2.length);

            float[] outgoing = DSPUtils.extractFrame(stretched1, 0, outgoingLen);
            float[] incoming = DSPUtils.extractFrame(stretched2, 0, incomingLen);

            float[] crossfaded = crossfader.crossfade(outgoing, incoming, Math.min(outgoingLen, incomingLen));

            stretched1 = null;
            stretched2 = null;

            String outputFilename = generateOutputFilename(
                    getFilenameFromPath(track1Path), getFilenameFromPath(track2Path), TRANSITION_DJ);
            String outputPath = new File(outputDir, outputFilename).getAbsolutePath();
            writer = new StreamingWavWriter();
            writer.open(outputPath, sampleRate, channels);

            reader1.close();
            reader1 = new AudioChunkReader(track1Path);
            long samplesWritten = 0;
            while (samplesWritten < transitionPoint) {
                float[] chunk = reader1.readNextChunk();
                if (chunk == null) break;

                long remaining = transitionPoint - samplesWritten;
                if (chunk.length <= remaining) {
                    crossfader.applyPeakLimiter(chunk, 0.95f);
                    writer.writeChunk(chunk);
                    samplesWritten += chunk.length;
                } else {
                    float[] partial = new float[(int) remaining];
                    System.arraycopy(chunk, 0, partial, 0, (int) remaining);
                    crossfader.applyPeakLimiter(partial, 0.95f);
                    writer.writeChunk(partial);
                    samplesWritten += remaining;
                }
            }

            crossfader.applyPeakLimiter(crossfaded, 0.95f);
            writer.writeChunk(crossfaded);

            reader2.close();
            reader2 = new AudioChunkReader(track2Path);
            long track2SkipTo = track2CueIn + actualFadeSamples;
            reader2.skip(track2SkipTo);
            float[] chunk;
            while ((chunk = reader2.readNextChunk()) != null) {
                crossfader.applyPeakLimiter(chunk, 0.95f);
                writer.writeChunk(chunk);
            }

            writer.close();
            writer = null;

            return outputPath;

        } catch (Exception e) {
            Log.e(TAG, "Error during optimized DJ mix", e);
            return null;
        } finally {
            try { if (reader1 != null) reader1.close(); } catch (Exception ignored) {}
            try { if (reader2 != null) reader2.close(); } catch (Exception ignored) {}
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        }
    }

    private float[] trimArray(float[] array, int length) {
        if (length >= array.length) return array;
        float[] result = new float[length];
        System.arraycopy(array, 0, result, 0, length);
        return result;
    }

    private float[] generateDownbeatGridWithPhase(float bpm, float durationSec, float phase) {
        float barDuration = 4 * 60.0f / bpm;
        java.util.List<Float> downbeatList = new java.util.ArrayList<>();
        float time = phase;

        while (time < durationSec) {
            downbeatList.add(time);
            time += barDuration;
        }

        float[] downbeats = new float[downbeatList.size()];
        for (int i = 0; i < downbeatList.size(); i++) {
            downbeats[i] = downbeatList.get(i);
        }
        return downbeats;
    }

    private SimpleBPMDetector.BeatGrid generateBeatGridForDuration(
            float bpm, float durationSec, float[] sampleAudio, int sampleRate) {
        SimpleBPMDetector.BeatGrid sampleGrid = bpmDetector.detectBeatGrid(sampleAudio, sampleRate);
        float phase = sampleGrid.phase;

        float period = 60.0f / bpm;
        java.util.List<Float> beatList = new java.util.ArrayList<>();
        float beatTime = phase;

        while (beatTime < durationSec) {
            beatList.add(beatTime);
            beatTime += period;
        }

        float[] beats = new float[beatList.size()];
        for (int i = 0; i < beatList.size(); i++) {
            beats[i] = beatList.get(i);
        }

        int numDownbeats = beats.length / 4;
        float[] downbeats = new float[numDownbeats];
        for (int i = 0; i < numDownbeats; i++) {
            downbeats[i] = beats[i * 4];
        }

        return new SimpleBPMDetector.BeatGrid(beats, downbeats, bpm, phase);
    }

    private long snapToNearestDownbeat(long samplePos, float[] downbeats, int sampleRate, int channels) {
        if (downbeats == null || downbeats.length == 0) return samplePos;

        float timeSec = samplePos / (float) (sampleRate * channels);
        float nearestDownbeat = downbeats[0];
        float minDiff = Math.abs(timeSec - downbeats[0]);

        for (float downbeat : downbeats) {
            float diff = Math.abs(timeSec - downbeat);
            if (diff < minDiff) {
                minDiff = diff;
                nearestDownbeat = downbeat;
            }
        }

        return (long) (nearestDownbeat * sampleRate * channels);
    }

    // simple volume crossfade
    private MixResult mixSimpleFadeStreaming(String track1Path, String track2Path, File outputDir) {
        AudioChunkReader reader1 = null;
        AudioChunkReader reader2 = null;
        StreamingWavWriter writer = null;
        long track1DurationMs = 0;

        try {
            reader1 = new AudioChunkReader(track1Path);
            reader2 = new AudioChunkReader(track2Path);

            int actualSampleRate = reader1.getSampleRate();
            int actualChannels = reader1.getChannelCount();

            long track1Samples = reader1.getTotalSamples();
            track1DurationMs = track1Samples * 1000 / (actualSampleRate * actualChannels);

            long transitionPoint = (long) (track1Samples * 0.8f);
            int fadeSamples = (int) (track1Samples - transitionPoint);

            if (fadeSamples % 2 != 0) fadeSamples--;
            if (transitionPoint % 2 != 0) transitionPoint++;

            String outputFilename = generateOutputFilename(
                    getFilenameFromPath(track1Path), getFilenameFromPath(track2Path), TRANSITION_SIMPLE_FADE);
            String outputPath = new File(outputDir, outputFilename).getAbsolutePath();
            writer = new StreamingWavWriter();
            writer.open(outputPath, actualSampleRate, actualChannels);

            float[] fadeOut = new float[fadeSamples];
            float[] fadeIn = new float[fadeSamples];

            reader1.skip(transitionPoint);

            int fadeOutRead = 0;
            while (fadeOutRead < fadeSamples) {
                float[] chunk = reader1.readNextChunk();
                if (chunk == null) break;
                int toCopy = Math.min(chunk.length, fadeSamples - fadeOutRead);
                System.arraycopy(chunk, 0, fadeOut, fadeOutRead, toCopy);
                fadeOutRead += toCopy;
            }

            int fadeInRead = 0;
            while (fadeInRead < fadeSamples) {
                float[] chunk = reader2.readNextChunk();
                if (chunk == null) break;
                int toCopy = Math.min(chunk.length, fadeSamples - fadeInRead);
                System.arraycopy(chunk, 0, fadeIn, fadeInRead, toCopy);
                fadeInRead += toCopy;
            }

            int actualFadeLen = Math.min(fadeOutRead, fadeInRead);
            float[] crossfaded = crossfader.simpleCrossfade(fadeOut, fadeIn, actualFadeLen);

            reader1.close();
            reader1 = new AudioChunkReader(track1Path);
            long samplesWritten = 0;
            while (samplesWritten < transitionPoint) {
                float[] chunk = reader1.readNextChunk();
                if (chunk == null) break;

                long remaining = transitionPoint - samplesWritten;
                if (chunk.length <= remaining) {
                    crossfader.applyPeakLimiter(chunk, 0.95f);
                    writer.writeChunk(chunk);
                    samplesWritten += chunk.length;
                } else {
                    float[] partial = new float[(int) remaining];
                    System.arraycopy(chunk, 0, partial, 0, (int) remaining);
                    crossfader.applyPeakLimiter(partial, 0.95f);
                    writer.writeChunk(partial);
                    samplesWritten += remaining;
                }
            }

            crossfader.applyPeakLimiter(crossfaded, 0.95f);
            writer.writeChunk(crossfaded);

            float[] chunk;
            while ((chunk = reader2.readNextChunk()) != null) {
                crossfader.applyPeakLimiter(chunk, 0.95f);
                writer.writeChunk(chunk);
            }

            writer.close();
            writer = null;

            return new MixResult(outputPath, track1DurationMs);

        } catch (Exception e) {
            Log.e(TAG, "Error during streaming mix", e);
            return new MixResult(null, 0);
        } finally {
            try { if (reader1 != null) reader1.close(); } catch (Exception ignored) {}
            try { if (reader2 != null) reader2.close(); } catch (Exception ignored) {}
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        }
    }

    // overlap mix, both tracks play together during transition
    private MixResult mixOverlapStreaming(String track1Path, String track2Path, File outputDir) {
        AudioChunkReader reader1 = null;
        AudioChunkReader reader2 = null;
        StreamingWavWriter writer = null;
        long track1DurationMs = 0;

        try {
            reader1 = new AudioChunkReader(track1Path);
            reader2 = new AudioChunkReader(track2Path);

            int actualSampleRate = reader1.getSampleRate();
            int actualChannels = reader1.getChannelCount();

            long track1Samples = reader1.getTotalSamples();
            track1DurationMs = track1Samples * 1000 / (actualSampleRate * actualChannels);

            long overlapStart = (long) (track1Samples * 0.8f);
            int overlapSamples = (int) (track1Samples - overlapStart);

            if (overlapSamples % 2 != 0) overlapSamples--;
            if (overlapStart % 2 != 0) overlapStart++;

            String outputFilename = generateOutputFilename(
                    getFilenameFromPath(track1Path), getFilenameFromPath(track2Path), TRANSITION_OVERLAP);
            String outputPath = new File(outputDir, outputFilename).getAbsolutePath();
            writer = new StreamingWavWriter();
            writer.open(outputPath, actualSampleRate, actualChannels);

            long samplesWritten = 0;
            while (samplesWritten < overlapStart) {
                float[] chunk = reader1.readNextChunk();
                if (chunk == null) break;

                long remaining = overlapStart - samplesWritten;
                if (chunk.length <= remaining) {
                    writer.writeChunk(chunk);
                    samplesWritten += chunk.length;
                } else {
                    writer.writeChunk(chunk, 0, (int) remaining);
                    samplesWritten += remaining;
                }
            }

            int actualOverlapLen = (int) Math.min(overlapSamples, track1Samples - overlapStart);
            float[] track1Tail = new float[actualOverlapLen];
            int tailRead = 0;
            while (tailRead < actualOverlapLen) {
                float[] chunk = reader1.readNextChunk();
                if (chunk == null) break;
                int toCopy = Math.min(chunk.length, actualOverlapLen - tailRead);
                System.arraycopy(chunk, 0, track1Tail, tailRead, toCopy);
                tailRead += toCopy;
            }

            float[] track2Head = new float[tailRead];
            int headRead = 0;
            while (headRead < track2Head.length) {
                float[] chunk = reader2.readNextChunk();
                if (chunk == null) break;
                int toCopy = Math.min(chunk.length, track2Head.length - headRead);
                System.arraycopy(chunk, 0, track2Head, headRead, toCopy);
                headRead += toCopy;
            }

            int mixLen = Math.min(tailRead, headRead);
            float[] overlapped = new float[mixLen];
            for (int i = 0; i < mixLen; i++) {
                float sum = track1Tail[i] + track2Head[i];
                overlapped[i] = Math.max(-1.0f, Math.min(1.0f, sum));
            }
            writer.writeChunk(overlapped);

            float[] chunk;
            while ((chunk = reader2.readNextChunk()) != null) {
                writer.writeChunk(chunk);
            }

            writer.close();
            writer = null;

            return new MixResult(outputPath, track1DurationMs);

        } catch (Exception e) {
            Log.e(TAG, "Error during streaming mix", e);
            return new MixResult(null, 0);
        } finally {
            try { if (reader1 != null) reader1.close(); } catch (Exception ignored) {}
            try { if (reader2 != null) reader2.close(); } catch (Exception ignored) {}
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        }
    }

    // onset correlation for beat alignment
    private long verifyAlignmentWithOnsetCorrelation(float[] track1Region, float[] track2Region,
            long initialOffset, int sampleRate, float bpm) {
        int beatSamples = (int) (sampleRate * 60.0f / bpm);
        int hopSize = 64;

        float[] onset1 = computeLocalOnsetStrength(track1Region, sampleRate, hopSize);
        float[] onset2 = computeLocalOnsetStrength(track2Region, sampleRate, hopSize);

        int searchRangeFrames = beatSamples / hopSize;
        float bestCorr = Float.NEGATIVE_INFINITY;
        int bestFrameOffset = 0;

        for (int deltaFrames = -searchRangeFrames; deltaFrames <= searchRangeFrames; deltaFrames++) {
            float corr = computeOnsetCorrelationByFrames(onset1, onset2, deltaFrames);
            if (corr > bestCorr) {
                bestCorr = corr;
                bestFrameOffset = deltaFrames;
            }
        }

        return (long) bestFrameOffset * hopSize;
    }

    private float[] computeLocalOnsetStrength(float[] audio, int sampleRate, int hopSize) {
        int frameSize = 1024;
        int numFrames = Math.max(1, (audio.length - frameSize) / hopSize + 1);
        float[] onset = new float[numFrames];
        float prevEnergy = 0;

        for (int i = 0; i < numFrames; i++) {
            int start = i * hopSize;
            float energy = 0;
            for (int j = 0; j < frameSize && start + j < audio.length; j++) {
                energy += audio[start + j] * audio[start + j];
            }
            energy = (float) Math.sqrt(energy / frameSize);
            onset[i] = Math.max(0, energy - prevEnergy);
            prevEnergy = energy;
        }
        return onset;
    }

    private float computeOnsetCorrelationByFrames(float[] onset1, float[] onset2, int frameOffset) {
        float sum = 0;
        float norm1 = 0;
        float norm2 = 0;
        int count = 0;

        for (int i = 0; i < onset1.length; i++) {
            int j = i + frameOffset;
            if (j >= 0 && j < onset2.length) {
                sum += onset1[i] * onset2[j];
                norm1 += onset1[i] * onset1[i];
                norm2 += onset2[j] * onset2[j];
                count++;
            }
        }

        if (count == 0 || norm1 == 0 || norm2 == 0) return 0;
        return sum / (float) (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}
