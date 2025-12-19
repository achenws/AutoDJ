package com.ece420.lab1;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.Manifest;
import android.app.ProgressDialog;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DJActivity extends Activity {
    private static final String TAG = "DJActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int FILE_SELECT_CODE = 101;

    // ui components
    private Button btnSelectFile;
    private Button btnPlay;
    private Button btnPause;
    private Button btnStop;
    private TextView tvBPMValue;
    private TextView tvCurrentTrack;
    private ListView lvTrackList;
    private WaveformView waveformView;

    // mixing ui
    private TextView tvTrack1Selection;
    private TextView tvTrack2Selection;
    private Spinner spinnerTransitionType;
    private Button btnMix;
    private TextView tvMixStatus;

    // audio
    private AudioPlayerManager audioPlayerManager;
    private SimpleBPMDetector bpmDetector;
    private AudioMixer audioMixer;

    // data
    private List<Track> trackList;
    private ArrayAdapter<String> trackAdapter;
    private Track currentTrack;

    // mixing state
    private Track track1ForMix = null;
    private Track track2ForMix = null;
    private boolean selectingTrack1 = true;

    // threading
    private ExecutorService executorService;
    private Handler mainHandler;

    // playhead update handler
    private Handler playheadHandler;
    private Runnable playheadUpdater = new Runnable() {
        @Override
        public void run() {
            if (audioPlayerManager.isPlaying()) {
                long pos = audioPlayerManager.getCurrentPosition();
                long dur = audioPlayerManager.getDuration();
                if (dur > 0) {
                    float normalized = (float) pos / dur;
                    waveformView.setPlayheadPosition(normalized);
                }
                playheadHandler.postDelayed(this, 50); // 20 FPS update
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dj);

        // init
        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        playheadHandler = new Handler(Looper.getMainLooper());
        trackList = new ArrayList<>();
        audioPlayerManager = new AudioPlayerManager();
        bpmDetector = new SimpleBPMDetector();
        audioMixer = new AudioMixer();

        initializeUI();

        checkPermissions();
    }

    private void initializeUI() {
        btnSelectFile = findViewById(R.id.btnSelectFile);
        btnPlay = findViewById(R.id.btnPlay);
        btnPause = findViewById(R.id.btnPause);
        btnStop = findViewById(R.id.btnStop);
        tvBPMValue = findViewById(R.id.tvBPMValue);
        tvCurrentTrack = findViewById(R.id.tvCurrentTrack);
        lvTrackList = findViewById(R.id.lvTrackList);
        waveformView = findViewById(R.id.waveformView);

        // waveform seek
        waveformView.setSeekListener(normalizedPosition -> {
            long duration = audioPlayerManager.getDuration();
            if (duration > 0) {
                long seekPosition = (long) (normalizedPosition * duration);
                audioPlayerManager.seekTo(seekPosition);
                waveformView.setPlayheadPosition(normalizedPosition);
            }
        });

        // track list
        trackAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1,
                new ArrayList<String>());
        lvTrackList.setAdapter(trackAdapter);

        // buttons
        btnSelectFile.setOnClickListener(v -> openFilePicker());
        btnPlay.setOnClickListener(v -> playCurrentTrack());
        btnPause.setOnClickListener(v -> pausePlayback());
        btnStop.setOnClickListener(v -> stopPlayback());

        // track click
        lvTrackList.setOnItemClickListener((parent, view, position, id) -> {
            selectTrack(position);
        });

        btnPlay.setEnabled(false);
        btnPause.setEnabled(false);
        btnStop.setEnabled(false);

        // mixing ui
        tvTrack1Selection = findViewById(R.id.tvTrack1Selection);
        tvTrack2Selection = findViewById(R.id.tvTrack2Selection);
        spinnerTransitionType = findViewById(R.id.spinnerTransitionType);
        btnMix = findViewById(R.id.btnMix);
        tvMixStatus = findViewById(R.id.tvMixStatus);

        // transition spinner
        String[] transitionTypes = { "DJ Transition", "Simple Crossfade", "Overlap" };
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, transitionTypes);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTransitionType.setAdapter(spinnerAdapter);

        btnMix.setOnClickListener(v -> startMixing());

        // track selection
        tvTrack1Selection.setOnClickListener(v -> {
            selectingTrack1 = true;
            tvTrack1Selection.setBackgroundColor(0xFF90CAF9);
            tvTrack2Selection.setBackgroundColor(0xFFE0E0E0);
            Toast.makeText(this, "Tap a track to set as Track 1", Toast.LENGTH_SHORT).show();
        });

        tvTrack2Selection.setOnClickListener(v -> {
            selectingTrack1 = false;
            tvTrack2Selection.setBackgroundColor(0xFF90CAF9);
            tvTrack1Selection.setBackgroundColor(0xFFE0E0E0);
            Toast.makeText(this, "Tap a track to set as Track 2", Toast.LENGTH_SHORT).show();
        });
    }

    private void checkPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // android 13+
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[] { Manifest.permission.READ_MEDIA_AUDIO },
                        PERMISSION_REQUEST_CODE);
            }
        } else {
            // older versions
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[] { Manifest.permission.READ_EXTERNAL_STORAGE },
                        PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Permission denied. Cannot access audio files.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        try {
            startActivityForResult(
                    Intent.createChooser(intent, "Select an MP3 file"),
                    FILE_SELECT_CODE);
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(this, "Please install a file manager", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_SELECT_CODE && resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                Uri uri = data.getData();
                handleSelectedFile(uri);
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void handleSelectedFile(Uri uri) {
        tvBPMValue.setText("Analyzing...");
        tvCurrentTrack.setText("Loading...");
        String fileName = getFileName(uri);

        executorService.execute(() -> {
            try {
                File tempFile = copyUriToTempFile(uri);

                if (tempFile != null) {
                    float bpm = bpmDetector.detectBPM(tempFile.getAbsolutePath());
                    Track track = new Track(fileName, tempFile.getAbsolutePath(), bpm);

                    mainHandler.post(() -> {
                        ProgressDialog progressDialog = new ProgressDialog(DJActivity.this);
                        progressDialog.setTitle("Preprocessing Track");
                        progressDialog.setMessage(String.format("Stretching %.0f BPM → 175 BPM...", bpm));
                        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                        progressDialog.setProgress(0);
                        progressDialog.setMax(100);
                        progressDialog.setCancelable(false);
                        progressDialog.show();

                        executorService.execute(() -> {
                            try {
                                TrackPreprocessor.PreprocessResult result = TrackPreprocessor.preprocessTrack(
                                        DJActivity.this,
                                        tempFile.getAbsolutePath(),
                                        bpm,
                                        new TrackPreprocessor.ProgressCallback() {
                                            @Override
                                            public void onProgress(float progress) {
                                                mainHandler.post(() -> {
                                                    progressDialog.setProgress((int) (progress * 100));
                                                });
                                            }

                                            @Override
                                            public void onStatusUpdate(String status) {
                                                mainHandler.post(() -> {
                                                    progressDialog.setMessage(status);
                                                });
                                            }
                                        });

                                if (result.success) {
                                    track.setStretchedFilePath(result.stretchedFilePath);
                                    track.setPhase(result.phase);

                                    try {
                                        AudioChunkReader reader = new AudioChunkReader(result.stretchedFilePath);
                                        long totalSamples = reader.getTotalSamples();
                                        int channels = reader.getChannelCount();
                                        int sampleRate = reader.getSampleRate();

                                        List<float[]> chunks = new ArrayList<>();
                                        float[] chunk;
                                        while ((chunk = reader.readNextChunk()) != null) {
                                            chunks.add(chunk.clone());
                                        }
                                        reader.close();

                                        int totalLen = 0;
                                        for (float[] c : chunks) totalLen += c.length;
                                        float[] audioSamples = new float[totalLen];
                                        int pos = 0;
                                        for (float[] c : chunks) {
                                            System.arraycopy(c, 0, audioSamples, pos, c.length);
                                            pos += c.length;
                                        }

                                        // find cue points using onset detection at 175 bpm
                                        SimpleBPMDetector.CuePoints cues = bpmDetector.findCuePoints(
                                                audioSamples, sampleRate, 175.0f);

                                        // scale by channel count
                                        track.setCueInSample(cues.cueInSample * channels);
                                        track.setCueOutSample(cues.cueOutSample * channels);
                                        track.setTotalSamples(totalSamples);
                                    } catch (Exception e) {
                                        Log.w(TAG, "Cue point detection failed", e);
                                    }

                                    final Track originalTrack = Track.createOriginalVersion(
                                            fileName, tempFile.getAbsolutePath(), bpm);

                                    final Track djReadyTrack = Track.createDJReadyVersion(
                                            fileName, result.stretchedFilePath, bpm,
                                            track.getCueInSample(), track.getCueOutSample(), track.getTotalSamples(),
                                            result.phase);

                                    mainHandler.post(() -> {
                                        progressDialog.dismiss();

                                        // original version
                                        trackList.add(originalTrack);
                                        trackAdapter.add(originalTrack.getDisplayString());

                                        // dj ready version
                                        trackList.add(djReadyTrack);
                                        trackAdapter.add(djReadyTrack.getDisplayString());

                                        trackAdapter.notifyDataSetChanged();

                                        currentTrack = originalTrack;
                                        tvCurrentTrack.setText(originalTrack.getName());
                                        tvBPMValue.setText(String.format("%.0f BPM", originalTrack.getBpm()));

                                        btnPlay.setEnabled(true);
                                        btnStop.setEnabled(true);

                                        try {
                                            audioPlayerManager.loadTrack(originalTrack.getFilePath());
                                            displayWaveform(tempFile, null);
                                        } catch (IOException e) {
                                            Log.e(TAG, "Error loading track", e);
                                            Toast.makeText(DJActivity.this, "Error loading track", Toast.LENGTH_SHORT)
                                                    .show();
                                        }

                                        Toast.makeText(DJActivity.this,
                                                String.format("Added: Original (%.0f BPM) + DJ Ready (175 BPM)", bpm),
                                                Toast.LENGTH_SHORT).show();
                                    });
                                } else {
                                    mainHandler.post(() -> {
                                        progressDialog.dismiss();
                                        Toast.makeText(DJActivity.this,
                                                "Preprocessing failed: " + result.errorMessage,
                                                Toast.LENGTH_LONG).show();
                                        tvBPMValue.setText("Error");
                                    });
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error during preprocessing", e);
                                mainHandler.post(() -> {
                                    progressDialog.dismiss();
                                    Toast.makeText(DJActivity.this,
                                            "Error: " + e.getMessage(),
                                            Toast.LENGTH_LONG).show();
                                    tvBPMValue.setText("Error");
                                });
                            }
                        });
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing file", e);
                e.printStackTrace();
                mainHandler.post(() -> {
                    tvBPMValue.setText("Error");
                    Toast.makeText(DJActivity.this, "Error processing file: " + e.getMessage(), Toast.LENGTH_LONG)
                            .show();
                });
            }
        });
    }

    private File copyUriToTempFile(Uri uri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream != null) {
                File tempFile = new File(getCacheDir(), "temp_audio_" + System.currentTimeMillis() + ".mp3");
                FileOutputStream outputStream = new FileOutputStream(tempFile);

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }

                inputStream.close();
                outputStream.close();

                return tempFile;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error copying file", e);
        }
        return null;
    }

    private String getFileName(Uri uri) {
        String fileName = "Unknown";
        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (nameIndex >= 0) {
                fileName = cursor.getString(nameIndex);
            }
            cursor.close();
        }
        return fileName;
    }

    private void selectTrack(int position) {
        if (position >= 0 && position < trackList.size()) {
            Track selectedTrack = trackList.get(position);

            // mixing track selection
            if (selectingTrack1) {
                track1ForMix = selectedTrack;
                tvTrack1Selection.setText(selectedTrack.getName());
                selectingTrack1 = false; // switch to track 2
                tvTrack1Selection.setBackgroundColor(0xFFE0E0E0);
                tvTrack2Selection.setBackgroundColor(0xFF90CAF9);
            } else {
                track2ForMix = selectedTrack;
                tvTrack2Selection.setText(selectedTrack.getName());
                selectingTrack1 = true;
                tvTrack1Selection.setBackgroundColor(0xFFE0E0E0);
                tvTrack2Selection.setBackgroundColor(0xFFE0E0E0);
            }

            updateMixButtonState();

            currentTrack = selectedTrack;
            tvCurrentTrack.setText(currentTrack.getName());

            if (currentTrack.isMixed()) {
                tvBPMValue.setText("");
            } else {
                tvBPMValue.setText(String.format("%.1f", currentTrack.getBpm()));
            }

            try {
                audioPlayerManager.loadTrack(currentTrack.getFilePath());
                btnPlay.setEnabled(true);
                btnStop.setEnabled(true);
                displayWaveform(new File(currentTrack.getFilePath()), () -> {
                    if (currentTrack.hasDisplayMarkers()) {
                        waveformView.setCueMarkers(
                                currentTrack.getDisplayMarkerStart(),
                                currentTrack.getDisplayMarkerEnd());
                    }
                });
            } catch (IOException e) {
                Log.e(TAG, "Error loading track", e);
                Toast.makeText(this, "Error loading track", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void updateMixButtonState() {
        btnMix.setEnabled(track1ForMix != null && track2ForMix != null);
    }

    private void startMixing() {
        if (track1ForMix == null || track2ForMix == null) {
            Toast.makeText(this, "Please select two tracks", Toast.LENGTH_SHORT).show();
            return;
        }

        int transitionType = spinnerTransitionType.getSelectedItemPosition();
        String transitionName = (String) spinnerTransitionType.getSelectedItem();

        // validate track types
        if (transitionType == 0) {
            if (!track1ForMix.isPreprocessed() || !track2ForMix.isPreprocessed()) {
                Toast.makeText(this, "DJ Transition requires 'DJ Ready' tracks (175 BPM versions)",
                        Toast.LENGTH_LONG).show();
                return;
            }
        } else {
            if (track1ForMix.isPreprocessed() || track2ForMix.isPreprocessed()) {
                Toast.makeText(this, transitionName + " requires 'Original' tracks (not DJ Ready versions)",
                        Toast.LENGTH_LONG).show();
                return;
            }
        }

        tvMixStatus.setVisibility(View.VISIBLE);
        tvMixStatus.setText("Mixing: " + transitionName + "...");
        btnMix.setEnabled(false);
        audioPlayerManager.stop();

        executorService.execute(() -> {
            try {
                // mix using cue points
                AudioMixer.MixResult mixResult = audioMixer.mix(
                        track1ForMix,
                        track2ForMix,
                        transitionType,
                        getCacheDir());

                final long track1DurationMs = mixResult.track1DurationMs;

                mainHandler.post(() -> {
                    if (mixResult.outputPath != null) {
                        tvMixStatus.setText("Mix complete! Tap to play.");

                        float avgBpm = (track1ForMix.getBpm() + track2ForMix.getBpm()) / 2;
                        String mixedName = new File(mixResult.outputPath).getName();
                        if (mixedName.endsWith(".wav")) {
                            mixedName = mixedName.substring(0, mixedName.length() - 4);
                        }
                        Track mixedTrack = new Track(mixedName, mixResult.outputPath, avgBpm);
                        mixedTrack.setMixed(true);

                        trackList.add(mixedTrack);
                        trackAdapter.add(mixedTrack.getName());
                        trackAdapter.notifyDataSetChanged();

                        currentTrack = mixedTrack;
                        tvCurrentTrack.setText(mixedTrack.getName());
                        tvBPMValue.setText("");

                        try {
                            audioPlayerManager.loadTrack(mixResult.outputPath);
                            btnPlay.setEnabled(true);
                            btnStop.setEnabled(true);

                            displayWaveform(new File(mixResult.outputPath), () -> {
                                float startMarkerNorm = -1;
                                float endMarkerNorm = -1;

                                long totalMixDurationMs = audioPlayerManager.getDuration();
                                if (totalMixDurationMs > 0) {
                                    long startMs = 0;
                                    long endMs = 0;

                                    if (transitionType == 0) {
                                        long fadeMs = (long) (65.828f * 1000);
                                        long cueOutSamples = track1ForMix.getCueOutSample();
                                        long cueOutMs = cueOutSamples * 1000 / 44100;

                                        endMs = cueOutMs;
                                        startMs = Math.max(0, endMs - fadeMs);
                                    } else {
                                        if (track1DurationMs > 0) {
                                            startMs = (long) (track1DurationMs * 0.8f);
                                            endMs = track1DurationMs;
                                        } else {
                                            startMs = 0;
                                            endMs = 0;
                                        }
                                    }

                                    startMarkerNorm = (float) startMs / totalMixDurationMs;
                                    endMarkerNorm = (float) endMs / totalMixDurationMs;
                                }

                                if (startMarkerNorm > 0 && startMarkerNorm < 1.0f) {
                                    if (endMarkerNorm > 1.0f) endMarkerNorm = 1.0f;
                                    if (endMarkerNorm <= startMarkerNorm) {
                                        endMarkerNorm = Math.min(startMarkerNorm + 0.05f, 1.0f);
                                    }
                                    mixedTrack.setDisplayMarkers(startMarkerNorm, endMarkerNorm);

                                    waveformView.setCueMarkers(startMarkerNorm, endMarkerNorm);
                                }
                            });

                            Toast.makeText(DJActivity.this, "Mix complete! Press Play",
                                    Toast.LENGTH_SHORT).show();
                        } catch (IOException e) {
                            Log.e(TAG, "Error loading mixed track", e);
                        }
                    } else {
                        tvMixStatus.setText("Mixing failed");
                        Toast.makeText(DJActivity.this, "Mixing failed",
                                Toast.LENGTH_SHORT).show();
                    }
                    btnMix.setEnabled(true);
                });
            } catch (Exception e) {
                Log.e(TAG, "Error during mixing", e);
                mainHandler.post(() -> {
                    tvMixStatus.setText("Error: " + e.getMessage());
                    btnMix.setEnabled(true);
                    Toast.makeText(DJActivity.this, "Error: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void playCurrentTrack() {
        if (currentTrack != null) {
            audioPlayerManager.play();
            btnPause.setEnabled(true);
            btnPlay.setEnabled(false);
            playheadHandler.post(playheadUpdater);
        }
    }

    private void pausePlayback() {
        audioPlayerManager.pause();
        btnPlay.setEnabled(true);
        btnPause.setEnabled(false);
        playheadHandler.removeCallbacks(playheadUpdater);
    }

    private void stopPlayback() {
        audioPlayerManager.stop();
        btnPlay.setEnabled(true);
        btnPause.setEnabled(false);
        playheadHandler.removeCallbacks(playheadUpdater);
        waveformView.setPlayheadPosition(-1);
    }

    private void displayWaveform(File audioFile, Runnable onComplete) {
        executorService.execute(() -> {
            try {
                float[] waveformData = WaveformExtractor.extractWaveform(
                        audioFile.getAbsolutePath(),
                        1500);

                if (waveformData == null || waveformData.length == 0) {
                    Log.e(TAG, "Failed to extract waveform");
                    mainHandler.post(() -> {
                        Toast.makeText(DJActivity.this, "Failed to extract waveform", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // detect cue points, skip for mixed tracks
                long durationMs = audioPlayerManager.getDuration();
                if (currentTrack != null && currentTrack.getBpm() > 0 && durationMs > 0 && !currentTrack.isMixed()) {
                    try {
                        AudioChunkReader cueReader = new AudioChunkReader(audioFile.getAbsolutePath());
                        List<float[]> cueChunks = new ArrayList<>();
                        float[] cueChunk;
                        while ((cueChunk = cueReader.readNextChunk()) != null) {
                            cueChunks.add(cueChunk.clone());
                        }
                        int sampleRate = cueReader.getSampleRate();
                        int channels = cueReader.getChannelCount();
                        cueReader.close();

                        int totalLen = 0;
                        for (float[] c : cueChunks) totalLen += c.length;
                        float[] audioSamples = new float[totalLen];
                        int pos = 0;
                        for (float[] c : cueChunks) {
                            System.arraycopy(c, 0, audioSamples, pos, c.length);
                            pos += c.length;
                        }

                        SimpleBPMDetector.CuePoints cues = bpmDetector.findCuePoints(
                                audioSamples, sampleRate, currentTrack.getBpm());
                        currentTrack.setCueInSample(cues.cueInSample * channels);
                        currentTrack.setCueOutSample(cues.cueOutSample * channels);
                        currentTrack.setTotalSamples((long) (durationMs * 44.1f));
                    } catch (Exception e) {
                        Log.w(TAG, "Cue point detection failed", e);
                    }
                }

                mainHandler.post(() -> {
                    waveformView.setWaveformData(waveformData);
                    waveformView.clearMarkers();
                    if (onComplete != null) {
                        onComplete.run();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error displaying waveform", e);
                mainHandler.post(() -> {
                    Toast.makeText(DJActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (playheadHandler != null) {
            playheadHandler.removeCallbacks(playheadUpdater);
        }
        audioPlayerManager.release();
        executorService.shutdown();
    }
}