package com.ece420.lab1.dsp;

/**
 * 2nd-order Butterworth IIR filter implementation.
 * Supports both high-pass and low-pass configurations.
 * Uses Direct Form II Transposed for numerical stability.
 *
 * This is a manual implementation equivalent to scipy.signal.butter
 * and scipy.signal.sosfilt from the Python reference.
 */
public class ButterworthFilter {

    public enum FilterType {
        LOW_PASS,
        HIGH_PASS
    }

    // Filter coefficients
    private double b0, b1, b2;  // Numerator (feedforward)
    private double a1, a2;       // Denominator (feedback), a0 = 1 (normalized)

    // Filter state (Direct Form II Transposed)
    private double s1, s2;

    // Configuration
    private int sampleRate;
    private double cutoffHz;
    private FilterType type;

    /**
     * Create a 2nd-order Butterworth filter.
     *
     * @param sampleRate Audio sample rate in Hz (e.g., 44100)
     * @param cutoffHz   Cutoff frequency in Hz
     * @param type       LOW_PASS or HIGH_PASS
     */
    public ButterworthFilter(int sampleRate, double cutoffHz, FilterType type) {
        this.sampleRate = sampleRate;
        this.type = type;
        this.s1 = 0;
        this.s2 = 0;
        calculateCoefficients(cutoffHz);
    }

    /**
     * Calculate filter coefficients using bilinear transform with pre-warping.
     *
     * @param cutoffHz Cutoff frequency in Hz
     */
    private void calculateCoefficients(double cutoffHz) {
        this.cutoffHz = cutoffHz;

        // Clamp cutoff to valid range (above 0, below Nyquist)
        double nyquist = sampleRate / 2.0;
        cutoffHz = Math.max(1.0, Math.min(cutoffHz, nyquist * 0.99));

        // Pre-warp the cutoff frequency (bilinear transform)
        double wc = 2.0 * Math.PI * cutoffHz / sampleRate;
        double w0 = Math.tan(wc / 2.0);

        // Butterworth Q factor for 2nd order = sqrt(2)/2
        double sqrt2 = Math.sqrt(2.0);

        // Common denominator
        double denom = 1.0 + sqrt2 * w0 + w0 * w0;

        if (type == FilterType.LOW_PASS) {
            // Low-pass coefficients
            b0 = (w0 * w0) / denom;
            b1 = 2.0 * b0;
            b2 = b0;
        } else {
            // High-pass coefficients
            b0 = 1.0 / denom;
            b1 = -2.0 / denom;
            b2 = 1.0 / denom;
        }

        // Feedback coefficients (same for both types)
        a1 = 2.0 * (w0 * w0 - 1.0) / denom;
        a2 = (1.0 - sqrt2 * w0 + w0 * w0) / denom;
    }

    /**
     * Reconfigure filter with new cutoff frequency.
     * Preserves filter state for smooth transitions during sweeps.
     *
     * @param cutoffHz New cutoff frequency in Hz
     */
    public void setCutoff(double cutoffHz) {
        calculateCoefficients(cutoffHz);
        // Note: s1, s2 are preserved for continuity
    }

    /**
     * Reset filter state. Call when starting a new audio segment.
     */
    public void reset() {
        s1 = 0;
        s2 = 0;
    }

    /**
     * Process a single sample using Direct Form II Transposed.
     *
     * @param input Input sample
     * @return Filtered output sample
     */
    public float processSample(float input) {
        double x = input;

        // Direct Form II Transposed
        double y = b0 * x + s1;
        s1 = b1 * x - a1 * y + s2;
        s2 = b2 * x - a2 * y;

        return (float) y;
    }

    /**
     * Process an array of samples in-place.
     *
     * @param samples Audio samples to filter
     */
    public void processBuffer(float[] samples) {
        for (int i = 0; i < samples.length; i++) {
            samples[i] = processSample(samples[i]);
        }
    }

    /**
     * Process a segment of an array in-place.
     *
     * @param samples Audio samples
     * @param offset  Start index
     * @param length  Number of samples to process
     */
    public void processBuffer(float[] samples, int offset, int length) {
        int end = Math.min(offset + length, samples.length);
        for (int i = offset; i < end; i++) {
            samples[i] = processSample(samples[i]);
        }
    }
}
