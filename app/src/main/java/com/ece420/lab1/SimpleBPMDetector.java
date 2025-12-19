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

// bpm detection for dnb tracks
public class SimpleBPMDetector {
    private static final String TAG = "SimpleBPMDetector";

    private static final int HOP_LENGTH = 512;
    private static final int MIN_BPM = 160;
    private static final int MAX_BPM = 190;
    private static final int WINDOW_SIZE = 16;
    private static final int MAX_HARMONICS = 8;
    private static final int BEAT_GRID_HOP_LENGTH = 128;
    private static final int PHASE_CANDIDATES = 256;

    // lag score pair for sorting
    private static class LagScore implements Comparable<LagScore> {
        int lag;
        float score;

        LagScore(int lag, float score) {
            this.lag = lag;
            this.score = score;
        }

        @Override
        public int compareTo(LagScore other) {
            return Float.compare(other.score, this.score);
        }
    }

    // cue points for dj transitions
    public static class CuePoints {
        public final long cueInSample;
        public final long cueOutSample;

        public CuePoints(long cueIn, long cueOut) {
            this.cueInSample = cueIn;
            this.cueOutSample = cueOut;
        }
    }

    // beat grid with timestamps and phase info
    public static class BeatGrid {
        public final float[] beats;
        public final float[] downbeats;
        public final float bpm;
        public final float phase;

        public BeatGrid(float[] beats, float[] downbeats, float bpm, float phase) {
            this.beats = beats;
            this.downbeats = downbeats;
            this.bpm = bpm;
            this.phase = phase;
        }
    }

    public SimpleBPMDetector() {}

    // holds decoded audio data
    private static class AudioData {
        final float[] samples;
        final int sampleRate;

        AudioData(float[] samples, int sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    // detect bpm from file
    public float detectBPM(String filePath) {
        try {
            AudioData audioData = decodeAudioFile(filePath);
            if (audioData == null || audioData.samples == null || audioData.samples.length == 0) {
                Log.e(TAG, "Failed to decode audio");
                return 175.0f;
            }
            return detectBPMFromSamples(audioData.samples, audioData.sampleRate);
        } catch (Exception e) {
            Log.e(TAG, "Error detecting BPM", e);
            return 175.0f;
        }
    }

    // detect bpm from samples
    public float detectBPMFromSamples(float[] audioSamples, int sampleRate) {
        try {
            if (audioSamples == null || audioSamples.length == 0) {
                return 175.0f;
            }

            float[] samples = audioSamples;
            int analysisRate = sampleRate;

            if (sampleRate != 44100) {
                samples = resampleTo44100(audioSamples, sampleRate);
                analysisRate = 44100;
            }

            // limit to 30 seconds
            int maxSamples = analysisRate * 30 * 2;
            if (samples.length > maxSamples) {
                float[] truncated = new float[maxSamples];
                System.arraycopy(samples, 0, truncated, 0, maxSamples);
                samples = truncated;
            }

            float[] onsetStrength = computeOnsetStrength(samples, analysisRate);
            float[] onsetHWR = halfWaveRectify(onsetStrength);
            float[] autocorr = computeAutocorrelation(onsetHWR);
            return findBestTempo(autocorr, analysisRate);

        } catch (Exception e) {
            Log.e(TAG, "Error detecting BPM from samples", e);
            return 175.0f;
        }
    }

    // linear interpolation resampling to 44100hz
    private float[] resampleTo44100(float[] input, int inputRate) {
        if (inputRate == 44100) return input;

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

    // detect beat grid with phase alignment
    public BeatGrid detectBeatGrid(float[] audioSamples, int sampleRate) {
        try {
            float bpm = detectBPMFromSamples(audioSamples, sampleRate);
            float[] onsetStrength = computeOnsetStrength(audioSamples, sampleRate, BEAT_GRID_HOP_LENGTH);
            float[] onsetHWR = halfWaveRectify(onsetStrength);
            float period = 60.0f / bpm;
            float phase = findPhaseOffset(onsetHWR, period, sampleRate, BEAT_GRID_HOP_LENGTH);

            // generate beats
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

            float[] downbeats = detectDownbeats(beats);
            return new BeatGrid(beats, downbeats, bpm, phase);

        } catch (Exception e) {
            Log.e(TAG, "Error detecting beat grid", e);
            return new BeatGrid(new float[]{0.0f}, new float[]{0.0f}, 175.0f, 0.0f);
        }
    }

    // find phase offset by testing different phases
    private float findPhaseOffset(float[] onsetHWR, float period, int sampleRate, int hopSize) {
        int numPhases = PHASE_CANDIDATES;
        float bestPhase = 0.0f;
        float bestScore = 0.0f;
        float framesPerSecond = (float) sampleRate / hopSize;

        for (int p = 0; p < numPhases; p++) {
            float phase = (p / (float) numPhases) * period;
            float score = 0.0f;

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

    // interpolate onset strength
    private float interpolatedOnset(float[] onset, float framePos) {
        int idx = (int) framePos;
        if (idx >= onset.length - 1) return onset[onset.length - 1];
        float frac = framePos - idx;
        return onset[idx] * (1 - frac) + onset[idx + 1] * frac;
    }

    // every 4th beat is a downbeat
    private float[] detectDownbeats(float[] beats) {
        if (beats.length < 4) return beats;

        int numDownbeats = beats.length / 4;
        float[] downbeats = new float[numDownbeats];
        for (int i = 0; i < numDownbeats; i++) {
            downbeats[i] = beats[i * 4];
        }
        return downbeats;
    }

    // find cue points for dj transitions
    public CuePoints findCuePoints(float[] audioSamples, int sampleRate, float bpm) {
        long defaultCueIn = 0;
        long defaultCueOut = (long) (audioSamples.length * 0.8f);

        try {
            if (audioSamples == null || audioSamples.length == 0) {
                return new CuePoints(defaultCueIn, defaultCueOut);
            }

            float[] samples = audioSamples;
            int analysisRate = sampleRate;
            boolean wasResampled = false;

            if (sampleRate != 44100) {
                samples = resampleTo44100(audioSamples, sampleRate);
                analysisRate = 44100;
                wasResampled = true;
            }

            float[] onsetStrength = computeOnsetStrength(samples, analysisRate);
            if (onsetStrength.length < 10) {
                return new CuePoints(defaultCueIn, defaultCueOut);
            }

            // calculate bar dimensions
            float barDurationSec = 4 * 60.0f / bpm;
            int barDurationSamples = (int) (barDurationSec * analysisRate);
            int framesPerBar = Math.max(1, barDurationSamples / HOP_LENGTH);
            int numBars = onsetStrength.length / framesPerBar;

            if (numBars < 4) {
                return new CuePoints(defaultCueIn, defaultCueOut);
            }

            // compute per bar energy
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

            // find cue in
            int startSearchBar = Math.max(1, (int) (30.0f / barDurationSec));
            int endCueInSearch = numBars / 2;
            long cueInSample = 0;

            for (int bar = startSearchBar; bar < endCueInSearch; bar++) {
                if (barEnergies[bar] < 0.7f * meanEnergy) {
                    cueInSample = (long) (bar * barDurationSec * analysisRate);
                    break;
                }
            }

            // find cue out
            int cueOutStart = (int) (numBars * 0.60f);
            int cueOutEnd = Math.min((int) (numBars * 0.75f), numBars - 16);
            int cueInBar = (int) (cueInSample / (barDurationSec * analysisRate));
            cueOutStart = Math.max(cueOutStart, cueInBar + 8);

            long cueOutSample = (long) (samples.length * 0.8f);
            boolean foundCueOut = false;

            for (int bar = cueOutStart; bar < cueOutEnd; bar++) {
                if (barEnergies[bar] > meanEnergy) {
                    cueOutSample = (long) (bar * barDurationSec * analysisRate);
                    foundCueOut = true;
                    break;
                }
            }

            if (!foundCueOut) {
                cueOutSample = (long) (samples.length * 0.70f);
            }

            // ensure minimum 8 bars between cue points
            float minGapSamples = 8 * barDurationSec * analysisRate;
            if (cueOutSample - cueInSample < minGapSamples) {
                cueOutSample = Math.min((long) (cueInSample + minGapSamples), (long) (samples.length * 0.8f));
            }

            // convert back to original sample rate
            if (wasResampled) {
                double ratio = (double) sampleRate / 44100.0;
                cueInSample = (long) (cueInSample * ratio);
                cueOutSample = (long) (cueOutSample * ratio);
            }

            return new CuePoints(cueInSample, cueOutSample);

        } catch (Exception e) {
            Log.e(TAG, "Error finding cue points", e);
            return new CuePoints(defaultCueIn, defaultCueOut);
        }
    }

    // decode audio file using mediacodec
    private AudioData decodeAudioFile(String filePath) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;

        final int ESTIMATED_SAMPLES = 30 * 48000 * 2;
        float[] sampleBuffer = new float[ESTIMATED_SAMPLES];
        int sampleCount = 0;
        int detectedSampleRate = 44100;

        try {
            extractor.setDataSource(filePath);

            // find audio track
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

            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                detectedSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            }

            // seek to 30s to skip intro
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            long seekTimeUs = 30_000_000;
            if (durationUs > 0 && seekTimeUs > durationUs - 10_000_000) {
                seekTimeUs = 0;
            }
            extractor.seekTo(seekTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean isEOS = false;
            long timeoutUs = 10000;
            long maxDurationUs = seekTimeUs + 30_000_000;

            while (!isEOS) {
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
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            isEOS = true;
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0);
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = decoder.dequeueOutputBuffer(info, timeoutUs);
                while (outputIndex >= 0) {
                    ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);

                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.order(ByteOrder.LITTLE_ENDIAN);
                        outputBuffer.rewind();
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

        float[] samples = new float[sampleCount];
        System.arraycopy(sampleBuffer, 0, samples, 0, sampleCount);
        return new AudioData(samples, detectedSampleRate);
    }

    private float[] computeOnsetStrength(float[] audio, int sampleRate) {
        return computeOnsetStrength(audio, sampleRate, HOP_LENGTH);
    }

    // compute onset strength using rms energy
    private float[] computeOnsetStrength(float[] audio, int sampleRate, int hopSize) {
        int frameSize = 2048;
        int numFrames = (audio.length - frameSize) / hopSize + 1;
        float[] onsetStrength = new float[numFrames];

        for (int i = 0; i < numFrames; i++) {
            int start = i * hopSize;
            int end = Math.min(start + frameSize, audio.length);
            float sum = 0;
            for (int j = start; j < end; j++) {
                sum += audio[j] * audio[j];
            }
            onsetStrength[i] = (float) Math.sqrt(sum / (end - start));
        }
        return onsetStrength;
    }

    // half wave rectification
    private float[] halfWaveRectify(float[] onset) {
        float[] onsetMean = movingAverage(onset, WINDOW_SIZE);
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

    // compute autocorrelation using fft
    private float[] computeAutocorrelation(float[] signal) {
        int n = signal.length;
        int fftSize = 1;
        while (fftSize < n * 2) {
            fftSize *= 2;
        }

        float[] fftData = new float[fftSize * 2];
        System.arraycopy(signal, 0, fftData, 0, n);

        FloatFFT_1D fft = new FloatFFT_1D(fftSize);
        fft.realForwardFull(fftData);

        // compute power spectrum
        for (int i = 0; i < fftSize; i++) {
            float re = fftData[2 * i];
            float im = fftData[2 * i + 1];
            fftData[2 * i] = re * re + im * im;
            fftData[2 * i + 1] = 0;
        }

        fft.complexInverse(fftData, true);

        int maxLag = Math.min(n, 1000);
        float[] autocorr = new float[maxLag];
        float normFactor = fftData[0] > 0 ? 1.0f / fftData[0] : 1.0f;
        for (int i = 0; i < maxLag; i++) {
            autocorr[i] = fftData[2 * i] * normFactor;
        }
        return autocorr;
    }

    // find best tempo from autocorrelation
    private float findBestTempo(float[] autocorr, int sampleRate) {
        float[] weightedAutocorr = applyTempoPrior(autocorr, sampleRate);

        int minLag = (int) ((60.0f / MAX_BPM) * sampleRate / HOP_LENGTH);
        int maxLag = (int) ((60.0f / MIN_BPM) * sampleRate / HOP_LENGTH);
        minLag = Math.max(1, minLag);
        maxLag = Math.min(weightedAutocorr.length - 1, maxLag);

        List<LagScore> candidates = new ArrayList<>();

        // score each candidate using harmonics
        for (int lag = minLag; lag <= maxLag; lag++) {
            float score = 0;
            int count = 0;
            for (int i = 1; i <= MAX_HARMONICS; i++) {
                int harmonicLag = i * lag;
                if (harmonicLag < weightedAutocorr.length) {
                    score += weightedAutocorr[harmonicLag];
                    count++;
                }
            }
            if (count > 0) score = score / count;
            candidates.add(new LagScore(lag, score));
        }

        Collections.sort(candidates);
        if (candidates.isEmpty()) return 175.0f;

        int bestLag = candidates.get(0).lag;
        int originalLag = bestLag;
        float refinedBestLag = refineLag(autocorr, bestLag);
        float tempo = 60.0f / (refinedBestLag * HOP_LENGTH / (float) sampleRate);

        // half time correction
        if (tempo < 100) {
            float doubleTempo = tempo * 2;
            int halfLag = bestLag / 2;
            if (doubleTempo >= MIN_BPM && doubleTempo <= MAX_BPM && halfLag >= minLag) {
                float scoreHalf = computeHarmonicScore(weightedAutocorr, halfLag);
                float scoreOriginal = computeHarmonicScore(weightedAutocorr, originalLag);
                if (scoreHalf > scoreOriginal * 0.4f) {
                    tempo = doubleTempo;
                }
            }
        }
        // double time correction
        else if (tempo > 185) {
            float halfTempo = tempo / 2;
            int doubleLag = bestLag * 2;
            if (halfTempo >= 90 && halfTempo <= 95 && doubleLag <= maxLag) {
                float scoreDouble = computeHarmonicScore(weightedAutocorr, doubleLag);
                float scoreOriginal = computeHarmonicScore(weightedAutocorr, originalLag);
                if (scoreDouble > scoreOriginal * 0.8f) {
                    tempo = halfTempo;
                }
            }
        }

        return Math.max(MIN_BPM, Math.min(MAX_BPM, tempo));
    }

    // helper for computing harmonic score at a given lag
    private float computeHarmonicScore(float[] weightedAutocorr, int lag) {
        float score = 0;
        int count = 0;
        for (int i = 1; i <= MAX_HARMONICS; i++) {
            int harmonicLag = i * lag;
            if (harmonicLag < weightedAutocorr.length) {
                score += weightedAutocorr[harmonicLag];
                count++;
            }
        }
        return count > 0 ? score / count : 0;
    }

    // apply tempo prior
    private float[] applyTempoPrior(float[] autocorr, int sampleRate) {
        float[] weighted = new float[autocorr.length];
        float priorCenter = 175.0f;
        float sigma = 0.3f;

        for (int lag = 1; lag < autocorr.length; lag++) {
            float bpm = 60.0f / (lag * HOP_LENGTH / (float) sampleRate);
            float logRatio = (float) (Math.log(bpm / priorCenter) / Math.log(2));
            float weight = (float) Math.exp(-0.5 * (logRatio * logRatio) / (sigma * sigma));
            weighted[lag] = autocorr[lag] * weight;
        }
        weighted[0] = autocorr[0];
        return weighted;
    }

    // refine lag with interpolation
    private float refineLag(float[] autocorr, int peakLag) {
        if (peakLag <= 0 || peakLag >= autocorr.length - 1) return peakLag;

        float y1 = autocorr[peakLag - 1];
        float y2 = autocorr[peakLag];
        float y3 = autocorr[peakLag + 1];

        float a = (y1 + y3) / 2.0f - y2;
        float b = (y3 - y1) / 2.0f;

        if (Math.abs(a) < 1e-9) return peakLag;

        float offset = -b / (2 * a);
        offset = Math.max(-0.5f, Math.min(0.5f, offset));
        return peakLag + offset;
    }
}
