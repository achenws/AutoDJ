package com.ece420.lab1;

public class Track {
    private String name;
    private String filePath;
    private float bpm;

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
}