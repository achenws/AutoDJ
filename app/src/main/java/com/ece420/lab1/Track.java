package com.ece420.lab1;

public class Track {
    private String name;
    private String filePath;             // Original file path
    private String stretchedFilePath;    // Pre-stretched file at 175 BPM (null if not yet processed)
    private float bpm;                   // Original BPM
    private float targetBpm = 175.0f;    // Target BPM for all tracks
    private long cueInSample = 0;        // Sample position for mix-in point (0 = not set)
    private long cueOutSample = 0;       // Sample position for mix-out point (0 = not set)
    private long totalSamples = 0;       // Total samples in track (for normalization)
    private boolean isPreprocessed = false;  // Whether time stretching has been completed

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

    /**
     * Get the stretched file path for mixing (175 BPM version).
     * Returns null if preprocessing hasn't been done yet.
     */
    public String getStretchedFilePath() {
        return stretchedFilePath;
    }

    /**
     * Set the stretched file path after preprocessing completes.
     */
    public void setStretchedFilePath(String path) {
        this.stretchedFilePath = path;
        this.isPreprocessed = (path != null);
    }

    /**
     * Check if the track has been preprocessed (time-stretched to 175 BPM).
     */
    public boolean isPreprocessed() {
        return isPreprocessed;
    }

    /**
     * Get the target BPM (always 175 for DNB).
     */
    public float getTargetBpm() {
        return targetBpm;
    }

    /**
     * Get display string for track list showing original and target BPM.
     * Format: "Track Name.mp3 - 168 BPM → 175 BPM"
     */
    public String getDisplayString() {
        if (isPreprocessed) {
            return String.format("%s - %.0f BPM → %.0f BPM", name, bpm, targetBpm);
        } else {
            return String.format("%s - %.0f BPM (processing...)", name, bpm);
        }
    }
}