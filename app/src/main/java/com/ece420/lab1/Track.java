package com.ece420.lab1;

public class Track {
    private String name;
    private String filePath; // Original file path
    private String stretchedFilePath; // Pre-stretched file at 175 BPM (null if not yet processed)
    private float bpm; // Original BPM
    private float targetBpm = 175.0f; // Target BPM for all tracks
    private long cueInSample = 0; // Sample position for mix-in point (0 = not set)
    private long cueOutSample = 0; // Sample position for mix-out point (0 = not set)
    private long totalSamples = 0; // Total samples in track (for normalization)
    private float phase = 0.0f; // Beat phase offset in seconds (where first beat starts)
    private boolean isPreprocessed = false; // Whether time stretching has been completed

    // Visualization state (0.0 to 1.0) - saved for UI persistence
    private float displayMarkerStart = -1.0f;
    private float displayMarkerEnd = -1.0f;

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

    public float getPhase() {
        return phase;
    }

    public void setPhase(float phase) {
        this.phase = phase;
    }

    public boolean hasCuePoints() {
        return cueOutSample > 0;
    }

    // Get cue-out position normalized (0.0 to 1.0), returns -1 if not set
    public float getCueOutNormalized() {
        if (cueOutSample <= 0 || totalSamples <= 0)
            return -1;
        return (float) cueOutSample / totalSamples;
    }

    // Get cue-in position normalized (0.0 to 1.0), returns -1 if not set
    public float getCueInNormalized() {
        if (totalSamples <= 0)
            return -1;
        return (float) cueInSample / totalSamples;
    }

    // Get the 175 BPM stretched file path, null if not preprocessed
    public String getStretchedFilePath() {
        return stretchedFilePath;
    }

    public void setStretchedFilePath(String path) {
        this.stretchedFilePath = path;
        this.isPreprocessed = (path != null);
    }

    public boolean isPreprocessed() {
        return isPreprocessed;
    }

    public float getTargetBpm() {
        return targetBpm;
    }

    // Get display string for track list
    public String getDisplayString() {
        if (isPreprocessed) {
            return String.format("%s - %.0f BPM (DJ Ready)", name, targetBpm);
        } else {
            return String.format("%s - %.0f BPM (Original)", name, bpm);
        }
    }

    // Create original (unprocessed) track for Simple Fade/Overlap mixes
    public static Track createOriginalVersion(String name, String filePath, float bpm) {
        Track track = new Track(name, filePath, bpm);
        track.isPreprocessed = false;
        return track;
    }

    // Create DJ-ready (175 BPM) track for DJ Transition mixes
    public static Track createDJReadyVersion(String name, String stretchedPath, float originalBpm,
            long cueIn, long cueOut, long totalSamples, float phase) {
        Track track = new Track(name, stretchedPath, originalBpm);
        track.stretchedFilePath = stretchedPath;
        track.isPreprocessed = true;
        track.cueInSample = cueIn;
        track.cueOutSample = cueOut;
        track.totalSamples = totalSamples;
        track.phase = phase;
        return track;
    }

    public void setDisplayMarkers(float start, float end) {
        this.displayMarkerStart = start;
        this.displayMarkerEnd = end;
    }

    public boolean hasDisplayMarkers() {
        return displayMarkerStart >= 0 && displayMarkerEnd >= 0;
    }

    public float getDisplayMarkerStart() {
        return displayMarkerStart;
    }

    public float getDisplayMarkerEnd() {
        return displayMarkerEnd;
    }

    private boolean isMixed = false;

    public boolean isMixed() {
        return isMixed;
    }

    public void setMixed(boolean mixed) {
        isMixed = mixed;
    }
}