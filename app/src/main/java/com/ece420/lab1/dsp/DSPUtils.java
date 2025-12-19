package com.ece420.lab1.dsp;

// dsp utility functions
public class DSPUtils {

    public static float[] extractFrame(float[] input, int start, int length) {
        float[] frame = new float[length];
        int end = Math.min(start + length, input.length);
        for (int i = start; i < end; i++) {
            frame[i - start] = input[i];
        }
        return frame;
    }
}
