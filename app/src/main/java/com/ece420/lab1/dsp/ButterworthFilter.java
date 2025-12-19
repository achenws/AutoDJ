package com.ece420.lab1.dsp;

// 2nd order butterworth iir filter
public class ButterworthFilter {

    public enum FilterType {
        LOW_PASS,
        HIGH_PASS
    }

    private double b0, b1, b2;
    private double a1, a2;
    private double s1, s2;

    private int sampleRate;
    private FilterType type;

    public ButterworthFilter(int sampleRate, double cutoffHz, FilterType type) {
        this.sampleRate = sampleRate;
        this.type = type;
        this.s1 = 0;
        this.s2 = 0;
        calculateCoefficients(cutoffHz);
    }

    private void calculateCoefficients(double cutoffHz) {
        double nyquist = sampleRate / 2.0;
        cutoffHz = Math.max(1.0, Math.min(cutoffHz, nyquist * 0.99));

        double wc = 2.0 * Math.PI * cutoffHz / sampleRate;
        double w0 = Math.tan(wc / 2.0);
        double sqrt2 = Math.sqrt(2.0);
        double denom = 1.0 + sqrt2 * w0 + w0 * w0;

        if (type == FilterType.LOW_PASS) {
            b0 = (w0 * w0) / denom;
            b1 = 2.0 * b0;
            b2 = b0;
        } else {
            b0 = 1.0 / denom;
            b1 = -2.0 / denom;
            b2 = 1.0 / denom;
        }

        a1 = 2.0 * (w0 * w0 - 1.0) / denom;
        a2 = (1.0 - sqrt2 * w0 + w0 * w0) / denom;
    }

    public void setCutoff(double cutoffHz) {
        calculateCoefficients(cutoffHz);
    }

    public void reset() {
        s1 = 0;
        s2 = 0;
    }

    public float processSample(float input) {
        double x = input;
        double y = b0 * x + s1;
        s1 = b1 * x - a1 * y + s2;
        s2 = b2 * x - a2 * y;
        return (float) y;
    }

    public void processBuffer(float[] samples) {
        for (int i = 0; i < samples.length; i++) {
            samples[i] = processSample(samples[i]);
        }
    }

    public void processBuffer(float[] samples, int offset, int length) {
        int end = Math.min(offset + length, samples.length);
        for (int i = offset; i < end; i++) {
            samples[i] = processSample(samples[i]);
        }
    }
}
