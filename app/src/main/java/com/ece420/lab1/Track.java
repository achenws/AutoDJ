package com.ece420.lab1;

// audio track with bpm and mixing data
public class Track {
    private String name;
    private String filePath;
    private String stretchedFilePath;
    private float bpm;
    private float targetBpm = 175.0f;
    private long cueInSample = 0;
    private long cueOutSample = 0;
    private long totalSamples = 0;
    private float phase = 0.0f;
    private boolean isPreprocessed = false;
    private boolean isMixed = false;
    private float displayMarkerStart = -1.0f;
    private float displayMarkerEnd = -1.0f;

    public Track(String name, String filePath, float bpm) {
        this.name = name;
        this.filePath = filePath;
        this.bpm = bpm;
    }

    // getters
    public String getName() { return name; }
    public String getFilePath() { return filePath; }
    public float getBpm() { return bpm; }
    public long getCueInSample() { return cueInSample; }
    public long getCueOutSample() { return cueOutSample; }
    public long getTotalSamples() { return totalSamples; }
    public float getPhase() { return phase; }
    public String getStretchedFilePath() { return stretchedFilePath; }
    public boolean isPreprocessed() { return isPreprocessed; }
    public float getTargetBpm() { return targetBpm; }
    public boolean isMixed() { return isMixed; }
    public boolean hasCuePoints() { return cueOutSample > 0; }
    public boolean hasDisplayMarkers() { return displayMarkerStart >= 0 && displayMarkerEnd >= 0; }
    public float getDisplayMarkerStart() { return displayMarkerStart; }
    public float getDisplayMarkerEnd() { return displayMarkerEnd; }

    // setters
    public void setCueInSample(long sample) { this.cueInSample = sample; }
    public void setCueOutSample(long sample) { this.cueOutSample = sample; }
    public void setTotalSamples(long samples) { this.totalSamples = samples; }
    public void setPhase(float phase) { this.phase = phase; }
    public void setMixed(boolean mixed) { this.isMixed = mixed; }
    public void setDisplayMarkers(float start, float end) {
        this.displayMarkerStart = start;
        this.displayMarkerEnd = end;
    }

    public void setStretchedFilePath(String path) {
        this.stretchedFilePath = path;
        this.isPreprocessed = (path != null);
    }

    public String getDisplayString() {
        if (isPreprocessed) {
            return String.format("%s - %.0f BPM (Ready)", name, targetBpm);
        }
        return String.format("%s - %.0f BPM", name, bpm);
    }

    // factory methods
    public static Track createOriginalVersion(String name, String filePath, float bpm) {
        return new Track(name, filePath, bpm);
    }

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
}
