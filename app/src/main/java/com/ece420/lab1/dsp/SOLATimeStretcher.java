package com.ece420.lab1.dsp;

/**
 * Synchronized Overlap-Add (SOLA) time stretching implementation.
 * Changes audio tempo without affecting pitch.
 *
 * This is a manual implementation equivalent to the Python time_stretch_sola
 * function from the reference DJ application.
 *
 * Algorithm:
 * 1. Divide input into overlapping frames
 * 2. Find optimal alignment using cross-correlation
 * 3. Crossfade overlapping regions
 * 4. Reassemble with modified hop sizes
 */
public class SOLATimeStretcher {

    // Default parameters matching Python implementation
    private static final int DEFAULT_FRAME_LENGTH = 2048;
    private static final float DEFAULT_OVERLAP_RATIO = 0.5f;
    private static final float DEFAULT_SEARCH_RATIO = 0.1f;

    private int frameLength;
    private float overlapRatio;
    private float searchRatio;

    /**
     * Create a time stretcher with default parameters.
     */
    public SOLATimeStretcher() {
        this(DEFAULT_FRAME_LENGTH, DEFAULT_OVERLAP_RATIO);
    }

    /**
     * Create a time stretcher with custom parameters.
     *
     * @param frameLength  Samples per frame (default 2048)
     * @param overlapRatio Overlap ratio between frames (default 0.5 = 50%)
     */
    public SOLATimeStretcher(int frameLength, float overlapRatio) {
        this.frameLength = frameLength;
        this.overlapRatio = overlapRatio;
        this.searchRatio = DEFAULT_SEARCH_RATIO;
    }

    /**
     * Time-stretch audio by the given factor.
     *
     * @param input         Audio samples (normalized -1 to 1)
     * @param stretchFactor Factor > 1 = slower (longer output), < 1 = faster (shorter output)
     *                      Calculate as: originalBPM / targetBPM
     * @return Stretched audio
     */
    public float[] stretch(float[] input, float stretchFactor) {
        // Skip if no stretching needed
        if (Math.abs(stretchFactor - 1.0f) < 0.01f) {
            return input.clone();
        }

        // Calculate parameters
        int hopOutput = (int) (frameLength * overlapRatio);
        int hopInput = (int) (hopOutput / stretchFactor);
        int searchRange = (int) (frameLength * searchRatio);
        int overlapLength = frameLength - hopOutput;

        // Pre-allocate output buffer (avoid ArrayList boxing overhead)
        int estimatedLength = (int) (input.length * stretchFactor) + frameLength * 2;
        float[] output = new float[estimatedLength];
        int outputPos = 0;

        int inputPos = 0;

        while (inputPos + frameLength < input.length) {
            // Extract current frame
            float[] frame = extractFrame(input, inputPos, frameLength);

            if (outputPos > hopOutput) {
                // Find best correlation offset for alignment
                int overlapStart = outputPos - overlapLength;
                float[] overlapOut = new float[overlapLength];
                System.arraycopy(output, overlapStart, overlapOut, 0,
                        Math.min(overlapLength, outputPos - overlapStart));

                int bestOffset = findBestOffset(input, inputPos, overlapOut,
                        overlapLength, searchRange);

                // Re-extract frame with best offset
                int alignedPos = inputPos + bestOffset;
                if (alignedPos >= 0 && alignedPos + frameLength <= input.length) {
                    frame = extractFrame(input, alignedPos, frameLength);
                    inputPos = alignedPos;
                }

                // Crossfade overlap region
                for (int i = 0; i < overlapLength && i < frame.length; i++) {
                    float fadeIn = (float) i / overlapLength;
                    float fadeOut = 1.0f - fadeIn;
                    int outIdx = overlapStart + i;
                    if (outIdx < outputPos) {
                        output[outIdx] = output[outIdx] * fadeOut + frame[i] * fadeIn;
                    }
                }

                // Append non-overlapping portion
                for (int i = overlapLength; i < frame.length; i++) {
                    if (outputPos < output.length) {
                        output[outputPos++] = frame[i];
                    }
                }
            } else {
                // First frame - just add it
                for (float sample : frame) {
                    if (outputPos < output.length) {
                        output[outputPos++] = sample;
                    }
                }
            }

            inputPos += hopInput;
        }

        // Trim to actual size
        float[] result = new float[outputPos];
        System.arraycopy(output, 0, result, 0, outputPos);
        return result;
    }

    /**
     * Find the best alignment offset using FFT-based cross-correlation.
     * Uses O(n log n) FFT instead of O(n * searchRange) direct method.
     *
     * @param input         Source audio
     * @param inputPos      Current position in input
     * @param overlapOut    Reference overlap segment from output
     * @param overlapLength Length of overlap
     * @param searchRange   Search window (±samples)
     * @return Best offset for alignment
     */
    private int findBestOffset(float[] input, int inputPos, float[] overlapOut,
                               int overlapLength, int searchRange) {
        // Use FFT-based cross-correlation for O(n log n) performance
        return DSPUtils.findBestOffsetFFT(overlapOut, input, inputPos, searchRange);
    }

    /**
     * Extract a frame from input array.
     */
    private float[] extractFrame(float[] input, int start, int length) {
        float[] frame = new float[length];
        int end = Math.min(start + length, input.length);
        for (int i = start; i < end; i++) {
            frame[i - start] = input[i];
        }
        return frame;
    }

    /**
     * Get the frame length parameter.
     */
    public int getFrameLength() {
        return frameLength;
    }

    /**
     * Get the overlap ratio parameter.
     */
    public float getOverlapRatio() {
        return overlapRatio;
    }
}
