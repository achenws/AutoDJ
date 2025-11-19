package com.ece420.lab1;

import android.media.MediaPlayer;
import android.util.Log;

import java.io.IOException;

public class AudioPlayerManager {
    private static final String TAG = "AudioPlayerManager";
    private MediaPlayer mediaPlayer;
    private String currentPath;

    public AudioPlayerManager() {
        mediaPlayer = new MediaPlayer();
    }

    public void loadTrack(String path) throws IOException {
        try {
            mediaPlayer.reset();
            mediaPlayer.setDataSource(path);
            mediaPlayer.prepare();
            currentPath = path;
        } catch (IOException e) {
            Log.e(TAG, "Error loading track: " + path, e);
            throw e;
        }
    }

    public void play() {
        if (!mediaPlayer.isPlaying()) {
            mediaPlayer.start();
        }
    }

    public void pause() {
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }
    }

    public void stop() {
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.stop();
            try {
                mediaPlayer.prepare();
            } catch (IOException e) {
                Log.e(TAG, "Error preparing after stop", e);
            }
        }
    }

    public void release() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }
}