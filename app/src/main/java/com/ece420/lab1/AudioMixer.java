package com.ece420.lab1;

import android.util.Log;

import com.ece420.lab1.dsp.DSPUtils;
import com.ece420.lab1.dsp.EQCrossfader;
import com.ece420.lab1.dsp.SOLATimeStretcher;

import java.io.File;

// Mixes two audio tracks with different transition types (DJ, fade, overlap)
public class AudioMixer {

    private static final String TAG = "AudioMixer";

    // Transition types
    public static final int TRANSITION_DJ = 0;           // BPM matched + EQ crossfade
    public static final int TRANSITION_SIMPLE_FADE = 1;  // Volume only, no BPM match
    public static final int TRANSITION_OVERLAP = 2;      // Both tracks together

    // Configuration
    private static final int SAMPLE_RATE = 44100;
    private static final float TARGET_BPM = 175.0f;      // D&B standard
    private static final int DJ_FADE_BARS = 48;          // 48 bars for DJ transition (~66 sec at 175 BPM)
    private static final int SIMPLE_FADE_BARS = 48;      // 48 bars for simple fade (~66 sec at 175 BPM)
    private static final int OVERLAP_BARS = 48;          // 48 bars for overlap (~66 sec at 175 BPM)

    // Fallback duration when BPM unknown (48 bars at 175 BPM)
    private static final float FALLBACK_FADE_SECONDS = 48 * 4 * 60.0f / 175.0f;  // ~65.8 seconds

    private SimpleBPMDetector bpmDetector;
    private SOLATimeStretcher timeStretcher;
    private EQCrossfader crossfader;

    public AudioMixer() {
        bpmDetector = new SimpleBPMDetector();
        timeStretcher = new SOLATimeStretcher();
        crossfader = new EQCrossfader(SAMPLE_RATE);
    }

    // Generate output filename like "Track1_x_Track2_DJ.wav"
    private String generateOutputFilename(String track1Name, String track2Name, int transitionType) {
        // Clean track names (remove extension, sanitize for filename)
        String name1 = sanitizeFilename(removeExtension(track1Name));
        String name2 = sanitizeFilename(removeExtension(track2Name));

        // Get transition type string
        String transitionStr;
        switch (transitionType) {
            case TRANSITION_DJ:
                transitionStr = "DJ";
                break;
            case TRANSITION_SIMPLE_FADE:
                transitionStr = "Fade";
                break;
            case TRANSITION_OVERLAP:
                transitionStr = "Overlap";
                break;
            default:
                transitionStr = "Mix";
        }

        return name1 + "_x_" + name2 + "_" + transitionStr + ".wav";
    }

    private String removeExtension(String filename) {
        if (filename == null) return "track";
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(0, lastDot);
        }
        return filename;
    }

    // Remove invalid filename chars
    private String sanitizeFilename(String name) {
        if (name == null || name.isEmpty()) return "track";
        // Remove invalid filename characters, keep alphanumeric, dash, underscore
        String sanitized = name.replaceAll("[^a-zA-Z0-9\\-_]", "_");
        // Limit length to avoid overly long filenames
        if (sanitized.length() > 30) {
            sanitized = sanitized.substring(0, 30);
        }
        return sanitized;
    }

    private String getFilenameFromPath(String path) {
        if (path == null) return "track";
        File file = new File(path);
        return file.getName();
    }

    // Mix two tracks with the specified transition type, returns output path
    public String mix(String track1Path, String track2Path, int transitionType, File outputDir) {
        Log.d(TAG, "Starting mix: " + track1Path + " -> " + track2Path);
        Log.d(TAG, "Transition type: " + transitionType);

        // Use streaming for simple fade and overlap
        if (transitionType == TRANSITION_SIMPLE_FADE) {
            return mixSimpleFadeStreaming(track1Path, track2Path, outputDir);
        } else if (transitionType == TRANSITION_OVERLAP) {
            return mixOverlapStreaming(track1Path, track2Path, outputDir);
        }

        // DJ mode uses optimized fade-region-only processing
        return mixDJOptimized(track1Path, track2Path, 0, 0, outputDir);
    }

    // Mix two tracks using detected cue points
    public String mix(Track track1, Track track2, int transitionType, File outputDir) {
        Log.d(TAG, "Starting mix with cue points: " + track1.getName() + " -> " + track2.getName());
        Log.d(TAG, "Transition type: " + transitionType);
        Log.d(TAG, "Cue points: track1 out=" + track1.getCueOutSample() +
                   ", track2 in=" + track2.getCueInSample());
        Log.d(TAG, "Preprocessed: track1=" + track1.isPreprocessed() +
                   ", track2=" + track2.isPreprocessed());

        // Use streaming for simple fade and overlap
        if (transitionType == TRANSITION_SIMPLE_FADE) {
            return mixSimpleFadeStreaming(track1.getFilePath(), track2.getFilePath(), outputDir);
        } else if (transitionType == TRANSITION_OVERLAP) {
            return mixOverlapStreaming(track1.getFilePath(), track2.getFilePath(), outputDir);
        }

        // DJ mode: use preprocessed files if available
        if (track1.isPreprocessed() && track2.isPreprocessed()) {
            Log.d(TAG, "Using preprocessed files at 175 BPM");
            return mixDJWithPreprocessed(track1, track2, outputDir);
        }

        // Fallback to runtime time stretching
        Log.w(TAG, "Tracks not preprocessed, using runtime stretching");
        return mixDJOptimized(track1.getFilePath(), track2.getFilePath(),
                              track1.getCueOutSample(), track2.getCueInSample(), outputDir);
    }

    // DJ transition using preprocessed files (already at 175 BPM)
    private String mixDJWithPreprocessed(Track track1, Track track2, File outputDir) {
        Log.d(TAG, "DJ Mix: Using pre-stretched files at 175 BPM");

        // Validate tracks are preprocessed
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

        Log.d(TAG, "Stretched file 1: " + stretchedPath1);
        Log.d(TAG, "Stretched file 2: " + stretchedPath2);

        AudioChunkReader reader1 = null;
        AudioChunkReader reader2 = null;
        StreamingWavWriter writer = null;

        try {
            // Open preprocessed files
            reader1 = new AudioChunkReader(stretchedPath1);
            reader2 = new AudioChunkReader(stretchedPath2);

            int sampleRate = reader1.getSampleRate();
            int channels = reader1.getChannelCount();
            long track1Samples = reader1.getTotalSamples();

            Log.d(TAG, "Track 1 (stretched): " + track1Samples + " samples, " + sampleRate + " Hz");

            // Calculate fade duration (48 bars at 175 BPM)
            int fadeSamples = crossfader.calculateFadeDuration(DJ_FADE_BARS, TARGET_BPM, 4);
            Log.d(TAG, "Fade duration: " + fadeSamples + " samples");

            // Use pre-computed phase from preprocessing
            float phase1 = track1.getPhase();
            Log.d(TAG, "Using stored phase for track 1: " + String.format("%.3fs", phase1));

            // Generate downbeat grid at 175 BPM with stored phase
            float track1DurationSec = track1Samples / (float)(sampleRate * channels);
            float[] downbeats175 = generateDownbeatGridWithPhase(TARGET_BPM, track1DurationSec, phase1);
            Log.d(TAG, "Generated " + downbeats175.length + " downbeats at 175 BPM");

            long cueOut1 = track1.getCueOutSample();
            long cueIn2 = track2.getCueInSample();

            // Calculate transition point, snapped to downbeat
            long transitionPoint;
            if (cueOut1 > 0) {
                // Calculate raw transition point
                long rawTransition = Math.max(0, cueOut1 - fadeSamples);
                // Snap to nearest downbeat for beat-matched mixing
                transitionPoint = snapToNearestDownbeat(rawTransition, downbeats175, sampleRate, channels);
                Log.d(TAG, "Using preprocessed cue-out: " + cueOut1 +
                          ", raw transition: " + rawTransition +
                          ", snapped to downbeat: " + transitionPoint);
            } else {
                long rawTransition = (long)(track1Samples * 0.8f) - fadeSamples;
                rawTransition = Math.max(0, rawTransition);
                transitionPoint = snapToNearestDownbeat(rawTransition, downbeats175, sampleRate, channels);
                Log.d(TAG, "Using default 80% transition point, snapped to downbeat: " + transitionPoint);
            }

            // Read fade region from Track 1
            Log.d(TAG, "Reading fade region from track 1...");
            reader1.skip(transitionPoint);
            int fadeRegionLen = fadeSamples + (int)(5 * sampleRate); // Extra buffer
            float[] track1FadeRegion = new float[fadeRegionLen];
            int track1FadeRead = reader1.readSamples(track1FadeRegion, 0, fadeRegionLen);
            track1FadeRegion = trimArray(track1FadeRegion, track1FadeRead);

            // Read fade region from Track 2
            Log.d(TAG, "Reading fade region from track 2...");

            // Use pre-computed phase
            long track2Samples = reader2.getTotalSamples();
            float phase2 = track2.getPhase();
            Log.d(TAG, "Track 1 phase: " + String.format("%.3fs", phase1) +
                       ", Track 2 phase: " + String.format("%.3fs", phase2));

            // DIRECT BEAT CYCLE ALIGNMENT
            // Calculate where each track is within its beat cycle at the actual mix points,
            // then adjust track 2 so both are at the same beat position when mixed.
            // This ensures beats align regardless of where each track's phase falls within a bar.

            float beatPeriod = 60.0f / TARGET_BPM;  // ~0.343s at 175 BPM

            // Get transition point time in seconds
            float transitionTimeSec = transitionPoint / (float)(sampleRate * channels);

            // Where is track 1 within its beat cycle at the transition?
            float track1BeatPos = (transitionTimeSec - phase1) % beatPeriod;
            if (track1BeatPos < 0) track1BeatPos += beatPeriod;  // Handle negative modulo

            // Get cue-in time in seconds (use 0 if no cue point set)
            float cueIn2TimeSec = (cueIn2 > 0 ? cueIn2 : 0) / (float)(sampleRate * channels);

            // Where is track 2 within its beat cycle at its cue-in?
            float track2BeatPos = (cueIn2TimeSec - phase2) % beatPeriod;
            if (track2BeatPos < 0) track2BeatPos += beatPeriod;  // Handle negative modulo

            // Calculate adjustment to align beat positions
            float adjustment = track1BeatPos - track2BeatPos;

            // Normalize to smallest adjustment [-beatPeriod/2, beatPeriod/2]
            while (adjustment > beatPeriod / 2) adjustment -= beatPeriod;
            while (adjustment < -beatPeriod / 2) adjustment += beatPeriod;

            // Apply adjustment to cue-in point
            long adjustmentSamples = (long)(adjustment * sampleRate * channels);
            long alignedCueIn2 = (cueIn2 > 0 ? cueIn2 : 0) + adjustmentSamples;
            alignedCueIn2 = Math.max(0, alignedCueIn2);  // Don't go negative

            Log.d(TAG, String.format("Beat alignment: transition=%.3fs (beatPos=%.3fs), " +
                       "cueIn=%.3fs (beatPos=%.3fs), adjustment=%.4fs (%.2f beats)",
                       transitionTimeSec, track1BeatPos, cueIn2TimeSec, track2BeatPos,
                       adjustment, adjustment / beatPeriod));
            reader2.skip(alignedCueIn2);
            float[] track2FadeRegion = new float[fadeRegionLen];
            int track2FadeRead = reader2.readSamples(track2FadeRegion, 0, fadeRegionLen);
            track2FadeRegion = trimArray(track2FadeRegion, track2FadeRead);

            // Fine-tune alignment using onset correlation
            long fineTuneOffset = verifyAlignmentWithOnsetCorrelation(
                    track1FadeRegion, track2FadeRegion, 0, sampleRate, TARGET_BPM);

            // Apply offset to either track depending on direction
            int track1StartOffset = 0;
            int track2StartOffset = 0;
            if (fineTuneOffset > 0) {
                // Track2 needs to be delayed - start extraction later
                track2StartOffset = (int) Math.min(fineTuneOffset, track2FadeRegion.length / 4);
            } else if (fineTuneOffset < 0) {
                // Track1 needs to be delayed - start extraction later
                track1StartOffset = (int) Math.min(-fineTuneOffset, track1FadeRegion.length / 4);
            }
            Log.d(TAG, String.format("Fine-tune offsets: track1=%d, track2=%d", track1StartOffset, track2StartOffset));

            // Extract crossfade segments (no stretching needed)
            int actualFadeSamples = fadeSamples;
            int outgoingLen = Math.min(actualFadeSamples, track1FadeRegion.length - track1StartOffset);
            int incomingLen = Math.min(actualFadeSamples, track2FadeRegion.length - track2StartOffset);

            float[] outgoing = DSPUtils.extractFrame(track1FadeRegion, track1StartOffset, outgoingLen);
            float[] incoming = DSPUtils.extractFrame(track2FadeRegion, track2StartOffset, incomingLen);

            // Perform EQ crossfade
            Log.d(TAG, "Performing EQ crossfade...");
            float[] crossfaded = crossfader.crossfade(outgoing, incoming, Math.min(outgoingLen, incomingLen));

            // Free fade region arrays
            track1FadeRegion = null;
            track2FadeRegion = null;

            // Assemble output
            Log.d(TAG, "Assembling output...");
            String outputFilename = generateOutputFilename(track1.getName(), track2.getName(), TRANSITION_DJ);
            String outputPath = new File(outputDir, outputFilename).getAbsolutePath();
            writer = new StreamingWavWriter();
            writer.open(outputPath, sampleRate, channels);

            // Write pre-transition from track 1
            Log.d(TAG, "Writing pre-transition from track 1...");
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
                    writer.writeChunk(chunk, 0, (int)remaining);
                    samplesWritten += remaining;
                }
            }

            // Write crossfaded region
            Log.d(TAG, "Writing crossfaded region...");
            crossfader.applyPeakLimiter(crossfaded, 0.95f);
            writer.writeChunk(crossfaded);

            // Write rest of track 2
            Log.d(TAG, "Writing rest of track 2...");
            reader2.close();
            reader2 = new AudioChunkReader(stretchedPath2);
            // Skip to position after fade: aligned cue-in + fade samples
            long track2SkipTo = alignedCueIn2 + actualFadeSamples;
            reader2.skip(track2SkipTo);
            float[] chunk;
            while ((chunk = reader2.readNextChunk()) != null) {
                writer.writeChunk(chunk);
            }

            writer.close();
            writer = null;

            Log.d(TAG, "DJ mix complete: " + outputPath);
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

    // DJ transition that processes only fade regions for efficiency
    private String mixDJOptimized(String track1Path, String track2Path,
                                   long track1CueOut, long track2CueIn, File outputDir) {
        Log.d(TAG, "DJ Mix (Optimized): Processing fade regions only");
        Log.d(TAG, "Cue points: track1Out=" + track1CueOut + ", track2In=" + track2CueIn);

        AudioChunkReader reader1 = null;
        AudioChunkReader reader2 = null;
        StreamingWavWriter writer = null;

        try {
            // Open readers for metadata
            reader1 = new AudioChunkReader(track1Path);
            reader2 = new AudioChunkReader(track2Path);

            int sampleRate = reader1.getSampleRate();
            int channels = reader1.getChannelCount();
            long track1Samples = reader1.getTotalSamples();

            Log.d(TAG, "Track 1: " + track1Samples + " samples, " + sampleRate + " Hz, " + channels + " ch");

            // BPM detection from first 30 seconds
            Log.d(TAG, "Detecting BPM...");
            int bpmSampleCount = Math.min((int)(30 * sampleRate * channels), (int)track1Samples);
            float[] bpmSamples1 = new float[bpmSampleCount];
            reader1.readSamples(bpmSamples1, 0, bpmSampleCount);
            float bpm1 = bpmDetector.detectBPMFromSamples(bpmSamples1, sampleRate);

            // Reopen reader1 for later streaming
            reader1.close();
            reader1 = new AudioChunkReader(track1Path);

            // BPM for track 2
            int bpmSampleCount2 = Math.min((int)(30 * sampleRate * channels), (int)reader2.getTotalSamples());
            float[] bpmSamples2 = new float[bpmSampleCount2];
            reader2.readSamples(bpmSamples2, 0, bpmSampleCount2);
            float bpm2 = bpmDetector.detectBPMFromSamples(bpmSamples2, sampleRate);

            Log.d(TAG, "BPM: Track1=" + bpm1 + ", Track2=" + bpm2);

            // Generate beat grid for downbeat alignment
            SimpleBPMDetector.BeatGrid beatGrid1 = null;
            try {
                Log.d(TAG, "Generating beat grid for track 1 (for downbeat alignment)...");

                // Generate full beat grid using detected BPM and track duration
                float track1DurationSec = track1Samples / (float)(sampleRate * channels);
                beatGrid1 = generateBeatGridForDuration(
                        bpm1, track1DurationSec, bpmSamples1, sampleRate);

                Log.d(TAG, String.format("Beat grid generated: %d downbeats for %.1f second track",
                        beatGrid1.downbeats.length, track1DurationSec));
            } catch (Exception e) {
                Log.e(TAG, "Error generating beat grid, will skip downbeat alignment", e);
                beatGrid1 = null;
            }

            // Now allow GC of BPM samples after beat grid generation
            bpmSamples1 = null;
            bpmSamples2 = null;

            // Calculate fade region
            int fadeSamples = crossfader.calculateFadeDuration(DJ_FADE_BARS, TARGET_BPM, 4);
            int fadeRegionLen = fadeSamples + (int)(10 * sampleRate * channels);

            // Use detected cue point if available, otherwise default to 80% of track 1
            long transitionPoint;
            if (track1CueOut > 0) {
                transitionPoint = track1CueOut;
                Log.d(TAG, "Using detected cue-out point (before snap): " + transitionPoint);

                // **NEW: Snap to nearest downbeat for musical alignment (if available)**
                if (beatGrid1 != null && beatGrid1.downbeats != null && beatGrid1.downbeats.length > 0) {
                    transitionPoint = snapToNearestDownbeat(transitionPoint, beatGrid1.downbeats, sampleRate, channels);
                    Log.d(TAG, "Snapped to downbeat: " + transitionPoint);
                } else {
                    Log.w(TAG, "Beat grid not available, using unaligned cue point");
                }
            } else {
                transitionPoint = (long)(track1Samples * 0.8f);
                Log.d(TAG, "Using default 80% transition point: " + transitionPoint);

                // **NEW: Snap default position too (if available)**
                if (beatGrid1 != null && beatGrid1.downbeats != null && beatGrid1.downbeats.length > 0) {
                    transitionPoint = snapToNearestDownbeat(transitionPoint, beatGrid1.downbeats, sampleRate, channels);
                    Log.d(TAG, "Snapped default to downbeat: " + transitionPoint);
                } else {
                    Log.w(TAG, "Beat grid not available, using unaligned default point");
                }
            }
            transitionPoint = Math.max(0, transitionPoint - fadeSamples);

            Log.d(TAG, "Fade: " + fadeSamples + " samples, transition at: " + transitionPoint);
            Log.d(TAG, "Fade region length: " + fadeRegionLen + " samples (~" + 
                    String.format("%.1f", (float)fadeRegionLen / sampleRate / channels) + " seconds)");

            // Read fade region from Track 1
            Log.d(TAG, "Reading fade region from track 1...");
            reader1.skip(transitionPoint);
            float[] track1FadeRegion = new float[fadeRegionLen];
            int track1FadeRead = reader1.readSamples(track1FadeRegion, 0, fadeRegionLen);
            if (track1FadeRead < fadeSamples) {
                Log.w(TAG, "Track 1 fade region shorter than expected: " + track1FadeRead);
            }
            track1FadeRegion = trimArray(track1FadeRegion, track1FadeRead);

            // Read fade region from Track 2
            Log.d(TAG, "Reading fade region from track 2...");
            reader2.close();
            reader2 = new AudioChunkReader(track2Path);
            // Skip to cue-in point if available (skip intro section)
            if (track2CueIn > 0) {
                reader2.skip(track2CueIn);
                Log.d(TAG, "Skipped to cue-in point: " + track2CueIn);
            }
            float[] track2FadeRegion = new float[fadeRegionLen];
            int track2FadeRead = reader2.readSamples(track2FadeRegion, 0, fadeRegionLen);
            if (track2FadeRead < fadeSamples) {
                Log.w(TAG, "Track 2 fade region shorter than expected: " + track2FadeRead);
            }
            track2FadeRegion = trimArray(track2FadeRegion, track2FadeRead);

            // Time stretch fade regions
            Log.d(TAG, "Time stretching fade regions to " + TARGET_BPM + " BPM...");
            float stretch1 = bpm1 / TARGET_BPM;
            float stretch2 = bpm2 / TARGET_BPM;

            float[] stretched1 = timeStretcher.stretch(track1FadeRegion, stretch1);
            track1FadeRegion = null; // Allow GC

            float[] stretched2 = timeStretcher.stretch(track2FadeRegion, stretch2);
            track2FadeRegion = null; // Allow GC

            // Clear FFT cache to free memory
            DSPUtils.clearFFTCache();

            Log.d(TAG, "Stretched: Track1=" + stretched1.length + ", Track2=" + stretched2.length);

            // Extract crossfade segments and perform EQ crossfade
            int actualFadeSamples = crossfader.calculateFadeDuration(DJ_FADE_BARS, TARGET_BPM, 4);
            int outgoingLen = Math.min(actualFadeSamples, stretched1.length);
            int incomingLen = Math.min(actualFadeSamples, stretched2.length);

            float[] outgoing = DSPUtils.extractFrame(stretched1, 0, outgoingLen);
            float[] incoming = DSPUtils.extractFrame(stretched2, 0, incomingLen);

            Log.d(TAG, "Performing EQ crossfade...");
            float[] crossfaded = crossfader.crossfade(outgoing, incoming, Math.min(outgoingLen, incomingLen));

            // Free stretched arrays
            stretched1 = null;
            stretched2 = null;

            // Assemble the final mix
            Log.d(TAG, "Assembling output (streaming)...");
            String outputFilename = generateOutputFilename(
                    getFilenameFromPath(track1Path), getFilenameFromPath(track2Path), TRANSITION_DJ);
            String outputPath = new File(outputDir, outputFilename).getAbsolutePath();
            writer = new StreamingWavWriter();
            writer.open(outputPath, sampleRate, channels);

            // Write pre-transition from track 1
            Log.d(TAG, "Writing pre-transition...");
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
                    float[] partial = new float[(int)remaining];
                    System.arraycopy(chunk, 0, partial, 0, (int)remaining);
                    crossfader.applyPeakLimiter(partial, 0.95f);
                    writer.writeChunk(partial);
                    samplesWritten += remaining;
                }
            }

            // Write crossfaded region
            Log.d(TAG, "Writing crossfade...");
            crossfader.applyPeakLimiter(crossfaded, 0.95f);
            writer.writeChunk(crossfaded);

            // Write rest of track 2
            Log.d(TAG, "Writing track 2...");
            reader2.close();
            reader2 = new AudioChunkReader(track2Path);
            // Skip to position after fade: cue-in point + fade samples
            long track2SkipTo = track2CueIn + actualFadeSamples;
            reader2.skip(track2SkipTo);
            Log.d(TAG, "Skipping to sample " + track2SkipTo + " in track 2");
            float[] chunk;
            while ((chunk = reader2.readNextChunk()) != null) {
                crossfader.applyPeakLimiter(chunk, 0.95f);
                writer.writeChunk(chunk);
            }

            writer.close();
            writer = null;

            Log.d(TAG, "Optimized DJ mix complete: " + outputPath);
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

    /**
     * Trim array to actual length.
     */
    private float[] trimArray(float[] array, int length) {
        if (length >= array.length) return array;
        float[] result = new float[length];
        System.arraycopy(array, 0, result, 0, length);
        return result;
    }

    /**
     * Generate a downbeat grid at a known tempo with detected phase offset.
     * Matches the Python implementation which uses phase detection for beat alignment.
     *
     * @param bpm         Known BPM (175 for preprocessed tracks)
     * @param durationSec Track duration in seconds
     * @param phase       Phase offset (where first beat starts) in seconds
     * @return Array of downbeat timestamps in seconds
     */
    private float[] generateDownbeatGridWithPhase(float bpm, float durationSec, float phase) {
        // Calculate bar duration (4 beats per bar in 4/4 time)
        float barDuration = 4 * 60.0f / bpm;

        // Generate downbeats starting from phase offset
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

    /**
     * Generate a full beat grid for the entire track duration.
     * Detects phase from sample audio, then extrapolates beats to full duration.
     *
     * @param bpm              Detected BPM
     * @param durationSec      Full track duration in seconds
     * @param sampleAudio      Sample audio for phase detection (e.g., first 30s)
     * @param sampleRate       Sample rate in Hz
     * @return BeatGrid with beats and downbeats for full track
     */
    private SimpleBPMDetector.BeatGrid generateBeatGridForDuration(
            float bpm, float durationSec, float[] sampleAudio, int sampleRate) {

        // Detect phase from sample audio
        SimpleBPMDetector.BeatGrid sampleGrid = bpmDetector.detectBeatGrid(sampleAudio, sampleRate);
        float phase = sampleGrid.phase;

        // Generate beats for full duration
        float period = 60.0f / bpm;  // Seconds per beat
        java.util.List<Float> beatList = new java.util.ArrayList<>();
        float beatTime = phase;

        while (beatTime < durationSec) {
            beatList.add(beatTime);
            beatTime += period;
        }

        // Convert to array
        float[] beats = new float[beatList.size()];
        for (int i = 0; i < beatList.size(); i++) {
            beats[i] = beatList.get(i);
        }

        // Detect downbeats (every 4th beat)
        int numDownbeats = beats.length / 4;
        float[] downbeats = new float[numDownbeats];
        for (int i = 0; i < numDownbeats; i++) {
            downbeats[i] = beats[i * 4];
        }

        return new SimpleBPMDetector.BeatGrid(beats, downbeats, bpm, phase);
    }

    /**
     * Snap a sample position to the nearest downbeat for musical alignment.
     * This ensures transitions happen on bar boundaries (every 4 beats).
     *
     * @param samplePos   Sample position to snap
     * @param downbeats   Array of downbeat timestamps (in seconds)
     * @param sampleRate  Sample rate in Hz
     * @param channels    Number of audio channels
     * @return Snapped sample position on nearest downbeat
     */
    private long snapToNearestDownbeat(long samplePos, float[] downbeats, int sampleRate, int channels) {
        if (downbeats == null || downbeats.length == 0) {
            Log.w(TAG, "No downbeats available, using original position");
            return samplePos;
        }

        // Convert sample position to seconds
        float timeSec = samplePos / (float) (sampleRate * channels);

        // Find nearest downbeat
        float nearestDownbeat = downbeats[0];
        float minDiff = Math.abs(timeSec - downbeats[0]);

        for (float downbeat : downbeats) {
            float diff = Math.abs(timeSec - downbeat);
            if (diff < minDiff) {
                minDiff = diff;
                nearestDownbeat = downbeat;
            }
        }

        // Convert back to sample position
        long snappedPos = (long) (nearestDownbeat * sampleRate * channels);

        Log.d(TAG, String.format("Snap alignment: %.2fs → %.2fs (diff: %.3fs, %.1f beats at 175 BPM)",
                timeSec, nearestDownbeat, minDiff, minDiff * 175.0f / 60.0f));

        return snappedPos;
    }

    // Streaming simple fade mix
    private String mixSimpleFadeStreaming(String track1Path, String track2Path, File outputDir) {
        Log.d(TAG, "Simple Fade Mix (Streaming)");

        AudioChunkReader reader1 = null;
        AudioChunkReader reader2 = null;
        StreamingWavWriter writer = null;

        try {
            // Open readers
            reader1 = new AudioChunkReader(track1Path);
            reader2 = new AudioChunkReader(track2Path);

            // Use the actual sample rate and channel count from the source file
            int actualSampleRate = reader1.getSampleRate();
            int actualChannels = reader1.getChannelCount();
            Log.d(TAG, "Source: " + actualSampleRate + " Hz, " + actualChannels + " channels");

            long track1Samples = reader1.getTotalSamples();
            // Fade samples = 48 bars at 175 BPM (use fallback since no BPM detection for simple mode)
            int fadeSamples = (int) (FALLBACK_FADE_SECONDS * actualSampleRate * actualChannels);
            long transitionPoint = (long) (track1Samples * 0.8f);
            transitionPoint = Math.max(0, transitionPoint - fadeSamples);

            Log.d(TAG, "Track 1: " + track1Samples + " samples, transition at: " + transitionPoint);
            Log.d(TAG, "Fade duration: " + fadeSamples + " samples");

            // Open writer with actual sample rate and channel count from source
            String outputFilename = generateOutputFilename(
                    getFilenameFromPath(track1Path), getFilenameFromPath(track2Path), TRANSITION_SIMPLE_FADE);
            String outputPath = new File(outputDir, outputFilename).getAbsolutePath();
            writer = new StreamingWavWriter();
            writer.open(outputPath, actualSampleRate, actualChannels);

            // Read fade regions and crossfade
            Log.d(TAG, "Reading fade regions...");
            float[] fadeOut = new float[fadeSamples];
            float[] fadeIn = new float[fadeSamples];

            // Skip to transition point in track 1
            reader1.skip(transitionPoint);

            // Read fade-out from track 1
            int fadeOutRead = 0;
            while (fadeOutRead < fadeSamples) {
                float[] chunk = reader1.readNextChunk();
                if (chunk == null) break;
                int toCopy = Math.min(chunk.length, fadeSamples - fadeOutRead);
                System.arraycopy(chunk, 0, fadeOut, fadeOutRead, toCopy);
                fadeOutRead += toCopy;
            }

            // Read fade-in from track 2
            int fadeInRead = 0;
            while (fadeInRead < fadeSamples) {
                float[] chunk = reader2.readNextChunk();
                if (chunk == null) break;
                int toCopy = Math.min(chunk.length, fadeSamples - fadeInRead);
                System.arraycopy(chunk, 0, fadeIn, fadeInRead, toCopy);
                fadeInRead += toCopy;
            }

            // Perform crossfade (peak-based level matching applied internally)
            int actualFadeLen = Math.min(fadeOutRead, fadeInRead);
            float[] crossfaded = crossfader.simpleCrossfade(fadeOut, fadeIn, actualFadeLen);

            // Write pre-transition from track 1
            Log.d(TAG, "Writing pre-transition...");
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
                    // Partial write
                    float[] partial = new float[(int)remaining];
                    System.arraycopy(chunk, 0, partial, 0, (int)remaining);
                    crossfader.applyPeakLimiter(partial, 0.95f);
                    writer.writeChunk(partial);
                    samplesWritten += remaining;
                }
            }

            // Write crossfaded region
            crossfader.applyPeakLimiter(crossfaded, 0.95f);
            writer.writeChunk(crossfaded);

            // Write rest of track 2
            Log.d(TAG, "Writing track 2...");
            float[] chunk;
            while ((chunk = reader2.readNextChunk()) != null) {
                crossfader.applyPeakLimiter(chunk, 0.95f);
                writer.writeChunk(chunk);
            }

            writer.close();
            writer = null;

            Log.d(TAG, "Streaming mix complete: " + outputPath);
            return outputPath;

        } catch (Exception e) {
            Log.e(TAG, "Error during streaming mix", e);
            return null;
        } finally {
            try { if (reader1 != null) reader1.close(); } catch (Exception ignored) {}
            try { if (reader2 != null) reader2.close(); } catch (Exception ignored) {}
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Streaming version of overlap mix - memory efficient.
     * True overlap: plays all of track 1, starts track 2 before track 1 ends,
     * both play together during overlap, then track 2 continues.
     */
    private String mixOverlapStreaming(String track1Path, String track2Path, File outputDir) {
        Log.d(TAG, "Overlap Mix (Streaming)");

        AudioChunkReader reader1 = null;
        AudioChunkReader reader2 = null;
        StreamingWavWriter writer = null;

        try {
            // Open readers
            reader1 = new AudioChunkReader(track1Path);
            reader2 = new AudioChunkReader(track2Path);

            // Use the actual sample rate and channel count from the source file
            int actualSampleRate = reader1.getSampleRate();
            int actualChannels = reader1.getChannelCount();
            Log.d(TAG, "Source: " + actualSampleRate + " Hz, " + actualChannels + " channels");

            long track1Samples = reader1.getTotalSamples();
            // Overlap samples = 48 bars at 175 BPM (use fallback since no BPM detection for overlap mode)
            int overlapSamples = (int) (FALLBACK_FADE_SECONDS * actualSampleRate * actualChannels);

            // Overlap starts this many samples before end of track 1
            long overlapStart = Math.max(0, track1Samples - overlapSamples);

            Log.d(TAG, "Track 1: " + track1Samples + " samples");
            Log.d(TAG, "Overlap starts at: " + overlapStart + ", duration: " + overlapSamples + " samples");

            // Open writer with actual sample rate and channel count from source
            String outputFilename = generateOutputFilename(
                    getFilenameFromPath(track1Path), getFilenameFromPath(track2Path), TRANSITION_OVERLAP);
            String outputPath = new File(outputDir, outputFilename).getAbsolutePath();
            writer = new StreamingWavWriter();
            writer.open(outputPath, actualSampleRate, actualChannels);

            // Write track 1 until overlap starts
            Log.d(TAG, "Writing track 1 before overlap...");
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

            // Overlap end of track 1 with start of track 2
            Log.d(TAG, "Overlapping tracks...");

            // Read remaining of track 1 (the overlap portion)
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

            // Read beginning of track 2 (same length as overlap)
            float[] track2Head = new float[tailRead]; // Match what we got from track 1
            int headRead = 0;
            while (headRead < track2Head.length) {
                float[] chunk = reader2.readNextChunk();
                if (chunk == null) break;
                int toCopy = Math.min(chunk.length, track2Head.length - headRead);
                System.arraycopy(chunk, 0, track2Head, headRead, toCopy);
                headRead += toCopy;
            }

            // Mix them together (both at equal volume)
            int mixLen = Math.min(tailRead, headRead);
            float[] overlapped = new float[mixLen];
            for (int i = 0; i < mixLen; i++) {
                overlapped[i] = (track1Tail[i] + track2Head[i]) * 0.5f;
            }
            writer.writeChunk(overlapped);

            // Write rest of track 2
            Log.d(TAG, "Writing track 2...");
            float[] chunk;
            while ((chunk = reader2.readNextChunk()) != null) {
                writer.writeChunk(chunk);
            }

            writer.close();
            writer = null;

            Log.d(TAG, "Streaming mix complete: " + outputPath);
            return outputPath;

        } catch (Exception e) {
            Log.e(TAG, "Error during streaming mix", e);
            return null;
        } finally {
            try { if (reader1 != null) reader1.close(); } catch (Exception ignored) {}
            try { if (reader2 != null) reader2.close(); } catch (Exception ignored) {}
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        }
    }


    // Fine-tune beat alignment using onset correlation
    private long verifyAlignmentWithOnsetCorrelation(float[] track1Region, float[] track2Region,
                                                      long initialOffset, int sampleRate, float bpm) {
        // Search range: ±1 full beat period to handle significant phase drift
        int beatSamples = (int)(sampleRate * 60.0f / bpm);
        int hopSize = 256;  // Same as onset computation

        // Compute onset strength for both regions
        float[] onset1 = computeLocalOnsetStrength(track1Region, sampleRate);
        float[] onset2 = computeLocalOnsetStrength(track2Region, sampleRate);

        // Search in frame steps for efficiency (each frame = hopSize samples)
        int searchRangeFrames = beatSamples / hopSize;  // ~59 frames at 175 BPM
        float bestCorr = Float.NEGATIVE_INFINITY;
        int bestFrameOffset = 0;

        for (int deltaFrames = -searchRangeFrames; deltaFrames <= searchRangeFrames; deltaFrames++) {
            float corr = computeOnsetCorrelationByFrames(onset1, onset2, deltaFrames);
            if (corr > bestCorr) {
                bestCorr = corr;
                bestFrameOffset = deltaFrames;
            }
        }

        long bestOffset = (long) bestFrameOffset * hopSize;
        Log.d(TAG, String.format("Onset fine-tune: offset=%d samples (%d frames), corr=%.3f",
                                  bestOffset, bestFrameOffset, bestCorr));
        return bestOffset;
    }

    // Compute local onset strength for correlation-based alignment
    private float[] computeLocalOnsetStrength(float[] audio, int sampleRate) {
        int hopSize = 256;
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
            energy = (float)Math.sqrt(energy / frameSize);
            onset[i] = Math.max(0, energy - prevEnergy);
            prevEnergy = energy;
        }
        return onset;
    }

    // Compute normalized cross-correlation between onset arrays with frame offset
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
        return sum / (float)(Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}
