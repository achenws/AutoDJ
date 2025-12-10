package com.ece420.lab1.dsp;

/**
 * Utility functions for digital signal processing operations.
 */
public class DSPUtils {

    /**
     * Extract a frame (segment) from an audio array.
     *
     * @param input  Source audio array
     * @param start  Start index
     * @param length Number of samples to extract
     * @return Extracted frame (zero-padded if needed)
     */
    public static float[] extractFrame(float[] input, int start, int length) {
        float[] frame = new float[length];
        int end = Math.min(start + length, input.length);
        for (int i = start; i < end; i++) {
            frame[i - start] = input[i];
        }
        return frame;
    }
}
