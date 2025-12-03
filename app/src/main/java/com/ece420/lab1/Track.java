package com.ece420.lab1;

public class Track {
    private String name;
    private String filePath;
    private float bpm;
    private long cueInSample = 0;   // Sample position for mix-in point (0 = not set)
    private long cueOutSample = 0;  // Sample position for mix-out point (0 = not set)
    private long totalSamples = 0;  // Total samples in track (for normalization)

    public Track(String name, String filePath, float bpm) {
        this.name = name;
        this.filePath = filePath;
        this.bpm = bpm;
    }

    public String getName() {
        return name;
    }

    public String getFilePath() {
        return filePath;
    }

    public float getBpm() {
        return bpm;
    }

    public long getCueInSample() {
        return cueInSample;
    }

    public void setCueInSample(long sample) {
        this.cueInSample = sample;
    }

    public long getCueOutSample() {
        return cueOutSample;
    }

    public void setCueOutSample(long sample) {
        this.cueOutSample = sample;
    }

    public long getTotalSamples() {
        return totalSamples;
    }

    public void setTotalSamples(long samples) {
        this.totalSamples = samples;
    }

    public boolean hasCuePoints() {
        return cueOutSample > 0;
    }

    /**
     * Get cue-out position as normalized value (0.0 to 1.0).
     * Returns -1 if cue points not set or totalSamples unknown.
     */
    public float getCueOutNormalized() {
        if (cueOutSample <= 0 || totalSamples <= 0) return -1;
        return (float) cueOutSample / totalSamples;
    }

    /**
     * Get cue-in position as normalized value (0.0 to 1.0).
     * Returns -1 if totalSamples unknown.
     */
    public float getCueInNormalized() {
        if (totalSamples <= 0) return -1;
        return (float) cueInSample / totalSamples;
    }
}