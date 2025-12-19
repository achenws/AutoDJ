package com.ece420.lab1.dsp;

// sola time stretching for tempo change without pitch shift
public class SOLATimeStretcher {

    private static final int FRAME_LENGTH = 2048;
    private static final float OVERLAP_RATIO = 0.5f;
    private static final float SEARCH_RATIO = 0.05f;

    private final int frameLength;
    private final int hopOutput;
    private final int searchRange;
    private final int overlapLen;

    public SOLATimeStretcher() {
        this.frameLength = FRAME_LENGTH;
        this.hopOutput = (int) (frameLength * OVERLAP_RATIO);
        this.searchRange = (int) (frameLength * SEARCH_RATIO);
        this.overlapLen = frameLength - hopOutput;
    }

    public float[] stretch(float[] input, float stretchFactor) {
        if (Math.abs(stretchFactor - 1.0f) < 0.01f) return input.clone();

        float hopInputFloat = hopOutput / stretchFactor;
        int estimatedLength = (int) (input.length * stretchFactor * 1.2f) + frameLength;
        float[] output = new float[estimatedLength];
        int outputLen = 0;

        float inputPosFloat = 0.0f;

        while ((int) inputPosFloat + frameLength < input.length) {
            int inputPos = (int) inputPosFloat;

            float[] frame = new float[frameLength];
            System.arraycopy(input, inputPos, frame, 0, frameLength);

            if (outputLen > hopOutput) {
                float[] overlapOut = new float[overlapLen];
                System.arraycopy(output, outputLen - overlapLen, overlapOut, 0, overlapLen);

                // find best correlation offset
                float bestCorr = Float.NEGATIVE_INFINITY;
                int bestOffset = 0;

                for (int offset = -searchRange; offset <= searchRange; offset++) {
                    int testPos = inputPos + offset;
                    if (testPos >= 0 && testPos + frameLength <= input.length) {
                        float[] testFrame = new float[overlapLen];
                        System.arraycopy(input, testPos, testFrame, 0, overlapLen);

                        float corr = normalizedCorrelation(overlapOut, testFrame);
                        if (corr > bestCorr) {
                            bestCorr = corr;
                            bestOffset = offset;
                        }
                    }
                }

                int alignedPos = inputPos + bestOffset;
                if (alignedPos >= 0 && alignedPos + frameLength <= input.length) {
                    System.arraycopy(input, alignedPos, frame, 0, frameLength);
                }

                // crossfade overlap
                for (int i = 0; i < overlapLen; i++) {
                    float fadeIn = (float) i / overlapLen;
                    float fadeOut = 1.0f - fadeIn;
                    int outIdx = outputLen - overlapLen + i;
                    output[outIdx] = output[outIdx] * fadeOut + frame[i] * fadeIn;
                }

                int appendLen = frameLength - overlapLen;
                if (outputLen + appendLen > output.length) {
                    float[] newOutput = new float[output.length * 2];
                    System.arraycopy(output, 0, newOutput, 0, outputLen);
                    output = newOutput;
                }
                System.arraycopy(frame, overlapLen, output, outputLen, appendLen);
                outputLen += appendLen;

            } else {
                if (outputLen + frameLength > output.length) {
                    float[] newOutput = new float[output.length * 2];
                    System.arraycopy(output, 0, newOutput, 0, outputLen);
                    output = newOutput;
                }
                System.arraycopy(frame, 0, output, outputLen, frameLength);
                outputLen += frameLength;
            }

            inputPosFloat += hopInputFloat;
        }

        float[] result = new float[outputLen];
        System.arraycopy(output, 0, result, 0, outputLen);
        return result;
    }

    private float normalizedCorrelation(float[] a, float[] b) {
        if (a.length != b.length) return 0;

        float dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        return dot / ((float) Math.sqrt(normA) * (float) Math.sqrt(normB) + 1e-10f);
    }
}
