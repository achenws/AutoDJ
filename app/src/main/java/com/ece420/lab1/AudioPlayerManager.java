package com.ece420.lab1;

import android.media.MediaPlayer;
import android.util.Log;

import java.io.IOException;

public class AudioPlayerManager {
    private static final String TAG = "AudioPlayerManager";
    private MediaPlayer mediaPlayer;

    public AudioPlayerManager() {
        mediaPlayer = new MediaPlayer();
    }

    public void loadTrack(String path) throws IOException {
        try {
            mediaPlayer.reset();
            mediaPlayer.setDataSource(path);
            mediaPlayer.prepare();
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

    /**
     * Get current playback position in milliseconds.
     * @return Current position in ms, or 0 if no track loaded
     */
    public long getCurrentPosition() {
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.getCurrentPosition();
            } catch (IllegalStateException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Get total duration of current track in milliseconds.
     * @return Duration in ms, or 0 if no track loaded
     */
    public long getDuration() {
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.getDuration();
            } catch (IllegalStateException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Check if currently playing.
     * @return true if playing, false otherwise
     */
    public boolean isPlaying() {
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.isPlaying();
            } catch (IllegalStateException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * Seek to a specific position.
     * @param positionMs Position in milliseconds
     */
    public void seekTo(long positionMs) {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.seekTo((int) positionMs);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error seeking", e);
            }
        }
    }
}