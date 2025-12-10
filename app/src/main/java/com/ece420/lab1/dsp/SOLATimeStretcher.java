package com.ece420.lab1.dsp;

// SOLA time stretching - changes tempo without affecting pitch
public class SOLATimeStretcher {

    // SOLA parameters
    private static final int FRAME_LENGTH = 2048;
    private static final float OVERLAP_RATIO = 0.5f;
    private static final float SEARCH_RATIO = 0.05f; // Reduced from 0.1 to minimize phase drift

    private final int frameLength;
    private final int hopOutput;
    private final int searchRange;
    private final int overlapLen;

    public SOLATimeStretcher() {
        this.frameLength = FRAME_LENGTH;
        this.hopOutput = (int) (frameLength * OVERLAP_RATIO); // 1024
        this.searchRange = (int) (frameLength * SEARCH_RATIO); // 204
        this.overlapLen = frameLength - hopOutput; // 1024
    }

    // Time-stretch audio by given factor (>1 = slower, <1 = faster)
    public float[] stretch(float[] input, float stretchFactor) {
        // Skip if no stretching needed
        if (Math.abs(stretchFactor - 1.0f) < 0.01f) {
            return input.clone();
        }

        // Calculate input hop (how much we advance in input per frame)
        // Use float for precise drift-free calculation
        float hopInputFloat = hopOutput / stretchFactor;

        // Dynamically grow output buffer as needed
        // Estimate output size
        int estimatedLength = (int) (input.length * stretchFactor * 1.2f) + frameLength;
        float[] output = new float[estimatedLength];
        int outputLen = 0;

        float inputPosFloat = 0.0f;
        int frameCount = 0;

        while ((int) inputPosFloat + frameLength < input.length) {
            int inputPos = (int) inputPosFloat;

            // Get current frame
            float[] frame = new float[frameLength];
            System.arraycopy(input, inputPos, frame, 0, frameLength);

            if (outputLen > hopOutput) {
                // Get the overlap region from output (last overlapLen samples)
                float[] overlapOut = new float[overlapLen];
                System.arraycopy(output, outputLen - overlapLen, overlapOut, 0, overlapLen);

                // Find best correlation offset
                float bestCorr = Float.NEGATIVE_INFINITY;
                int bestOffset = 0;

                for (int offset = -searchRange; offset <= searchRange; offset++) {
                    int testPos = inputPos + offset;
                    if (testPos >= 0 && testPos + frameLength <= input.length) {
                        // Get test frame's overlap region
                        float[] testFrame = new float[overlapLen];
                        System.arraycopy(input, testPos, testFrame, 0, overlapLen);

                        // Normalized cross-correlation
                        float corr = normalizedCorrelation(overlapOut, testFrame);
                        if (corr > bestCorr) {
                            bestCorr = corr;
                            bestOffset = offset;
                        }
                    }
                }

                // Re-extract frame at best position
                int alignedPos = inputPos + bestOffset;
                if (alignedPos >= 0 && alignedPos + frameLength <= input.length) {
                    System.arraycopy(input, alignedPos, frame, 0, frameLength);
                }

                // Crossfade overlap region
                for (int i = 0; i < overlapLen; i++) {
                    float fadeIn = (float) i / overlapLen;
                    float fadeOut = 1.0f - fadeIn;
                    int outIdx = outputLen - overlapLen + i;
                    output[outIdx] = output[outIdx] * fadeOut + frame[i] * fadeIn;
                }

                // Append non-overlapping part
                int appendLen = frameLength - overlapLen;
                if (outputLen + appendLen > output.length) {
                    // Resize if needed
                    float[] newOutput = new float[output.length * 2];
                    System.arraycopy(output, 0, newOutput, 0, outputLen);
                    output = newOutput;
                }
                System.arraycopy(frame, overlapLen, output, outputLen, appendLen);
                outputLen += appendLen;

            } else {
                // First frame - just add it entirely
                if (outputLen + frameLength > output.length) {
                    float[] newOutput = new float[output.length * 2];
                    System.arraycopy(output, 0, newOutput, 0, outputLen);
                    output = newOutput;
                }
                System.arraycopy(frame, 0, output, outputLen, frameLength);
                outputLen += frameLength;
            }

            inputPosFloat += hopInputFloat;
            frameCount++;
        }

        // Trim to actual size
        float[] result = new float[outputLen];
        System.arraycopy(output, 0, result, 0, outputLen);
        return result;
    }

    // Normalized cross-correlation between two signals
    private float normalizedCorrelation(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0;
        }

        float dot = 0;
        float normA = 0;
        float normB = 0;

        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        normA = (float) Math.sqrt(normA);
        normB = (float) Math.sqrt(normB);

        return dot / (normA * normB + 1e-10f);
    }
}
