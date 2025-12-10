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

    // UI Components
    private Button btnSelectFile;
    private Button btnPlay;
    private Button btnPause;
    private Button btnStop;
    private TextView tvBPMLabel;
    private TextView tvBPMValue;
    private TextView tvCurrentTrack;
    private ListView lvTrackList;
    private WaveformView waveformView;

    // Mixing UI Components
    private TextView tvTrack1Selection;
    private TextView tvTrack2Selection;
    private Spinner spinnerTransitionType;
    private Button btnMix;
    private TextView tvMixStatus;

    // Audio Components
    private AudioPlayerManager audioPlayerManager;
    private SimpleBPMDetector bpmDetector;
    private AudioMixer audioMixer;

    // Data
    private List<Track> trackList;
    private ArrayAdapter<String> trackAdapter;
    private Track currentTrack;

    // Mixing State
    private Track track1ForMix = null;
    private Track track2ForMix = null;
    private boolean selectingTrack1 = true;

    // Threading
    private ExecutorService executorService;
    private Handler mainHandler;

    // Playhead update handler for real-time position tracking
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

        // Initialize components
        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        playheadHandler = new Handler(Looper.getMainLooper());
        trackList = new ArrayList<>();
        audioPlayerManager = new AudioPlayerManager();
        bpmDetector = new SimpleBPMDetector();
        audioMixer = new AudioMixer();

        // Initialize UI
        initializeUI();

        // Check permissions
        checkPermissions();
    }

    private void initializeUI() {
        // Find UI elements
        btnSelectFile = findViewById(R.id.btnSelectFile);
        btnPlay = findViewById(R.id.btnPlay);
        btnPause = findViewById(R.id.btnPause);
        btnStop = findViewById(R.id.btnStop);
        tvBPMLabel = findViewById(R.id.tvBPMLabel);
        tvBPMValue = findViewById(R.id.tvBPMValue);
        tvCurrentTrack = findViewById(R.id.tvCurrentTrack);
        lvTrackList = findViewById(R.id.lvTrackList);
        waveformView = findViewById(R.id.waveformView);

        // Setup waveform seek listener for draggable playhead
        waveformView.setSeekListener(normalizedPosition -> {
            long duration = audioPlayerManager.getDuration();
            if (duration > 0) {
                long seekPosition = (long) (normalizedPosition * duration);
                audioPlayerManager.seekTo(seekPosition);
                waveformView.setPlayheadPosition(normalizedPosition);
            }
        });

        // Setup track list adapter
        trackAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1,
                new ArrayList<String>());
        lvTrackList.setAdapter(trackAdapter);

        // Setup button listeners
        btnSelectFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFilePicker();
            }
        });

        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playCurrentTrack();
            }
        });

        btnPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pausePlayback();
            }
        });

        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopPlayback();
            }
        });

        // Track list item click
        lvTrackList.setOnItemClickListener((parent, view, position, id) -> {
            selectTrack(position);
        });

        // Initially disable playback buttons
        btnPlay.setEnabled(false);
        btnPause.setEnabled(false);
        btnStop.setEnabled(false);

        // Initialize Mixing UI
        tvTrack1Selection = findViewById(R.id.tvTrack1Selection);
        tvTrack2Selection = findViewById(R.id.tvTrack2Selection);
        spinnerTransitionType = findViewById(R.id.spinnerTransitionType);
        btnMix = findViewById(R.id.btnMix);
        tvMixStatus = findViewById(R.id.tvMixStatus);

        // Setup transition type spinner
        String[] transitionTypes = { "DJ Transition", "Simple Crossfade", "Overlap" };
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, transitionTypes);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTransitionType.setAdapter(spinnerAdapter);

        // Mix button listener
        btnMix.setOnClickListener(v -> startMixing());

        // Track selection toggle
        tvTrack1Selection.setOnClickListener(v -> {
            selectingTrack1 = true;
            tvTrack1Selection.setBackgroundColor(0xFF90CAF9); // Light blue
            tvTrack2Selection.setBackgroundColor(0xFFE0E0E0); // Gray
            Toast.makeText(this, "Tap a track to set as Track 1", Toast.LENGTH_SHORT).show();
        });

        tvTrack2Selection.setOnClickListener(v -> {
            selectingTrack1 = false;
            tvTrack2Selection.setBackgroundColor(0xFF90CAF9); // Light blue
            tvTrack1Selection.setBackgroundColor(0xFFE0E0E0); // Gray
            Toast.makeText(this, "Tap a track to set as Track 2", Toast.LENGTH_SHORT).show();
        });
    }

    private void checkPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // Android 13+ uses READ_MEDIA_AUDIO
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[] { Manifest.permission.READ_MEDIA_AUDIO },
                        PERMISSION_REQUEST_CODE);
            }
        } else {
            // Older versions use READ_EXTERNAL_STORAGE
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
        // Show loading
        tvBPMValue.setText("Analyzing...");
        tvCurrentTrack.setText("Loading...");

        // Get file name
        String fileName = getFileName(uri);

        // Process in background
        executorService.execute(() -> {
            try {
                // Copy file to cache for processing
                File tempFile = copyUriToTempFile(uri);

                if (tempFile != null) {
                    Log.d(TAG, "Temp file created: " + tempFile.getAbsolutePath());

                    // Step 1: Detect BPM
                    Log.d(TAG, "Starting BPM detection...");
                    float bpm = bpmDetector.detectBPM(tempFile.getAbsolutePath());
                    Log.d(TAG, "BPM detection complete: " + bpm);

                    // Create track object with original BPM
                    Track track = new Track(fileName, tempFile.getAbsolutePath(), bpm);

                    // Step 2: Show progress dialog and preprocess (time stretch to 175 BPM)
                    mainHandler.post(() -> {
                        ProgressDialog progressDialog = new ProgressDialog(DJActivity.this);
                        progressDialog.setTitle("Preprocessing Track");
                        progressDialog.setMessage(String.format("Stretching %.0f BPM → 175 BPM...", bpm));
                        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                        progressDialog.setProgress(0);
                        progressDialog.setMax(100);
                        progressDialog.setCancelable(false);
                        progressDialog.show();

                        // Continue preprocessing in background
                        executorService.execute(() -> {
                            try {
                                // Preprocess with progress callback
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
                                    // Update track with preprocessed file path and phase
                                    track.setStretchedFilePath(result.stretchedFilePath);
                                    track.setPhase(result.phase);
                                    Log.d(TAG, "Preprocessing complete, phase=" + result.phase);

                                    // Step 3: Detect cue points on the STRETCHED audio using accurate method
                                    Log.d(TAG, "Detecting cue points on stretched audio (accurate mode)...");
                                    try {
                                        // Load all audio samples for accurate cue point detection
                                        AudioChunkReader reader = new AudioChunkReader(result.stretchedFilePath);
                                        long totalSamples = reader.getTotalSamples();
                                        int channels = reader.getChannelCount();
                                        int sampleRate = reader.getSampleRate();

                                        // Read all chunks into a list
                                        List<float[]> chunks = new ArrayList<>();
                                        float[] chunk;
                                        while ((chunk = reader.readNextChunk()) != null) {
                                            chunks.add(chunk.clone());  // Clone to avoid buffer reuse issues
                                        }
                                        reader.close();

                                        // Concatenate chunks into single array
                                        int totalLen = 0;
                                        for (float[] c : chunks) totalLen += c.length;
                                        float[] audioSamples = new float[totalLen];
                                        int pos = 0;
                                        for (float[] c : chunks) {
                                            System.arraycopy(c, 0, audioSamples, pos, c.length);
                                            pos += c.length;
                                        }

                                        Log.d(TAG, String.format("Loaded %d samples for cue detection (rate=%d, ch=%d)",
                                                audioSamples.length, sampleRate, channels));

                                        // Find cue points using accurate onset-based detection at 175 BPM
                                        SimpleBPMDetector.CuePoints cues = bpmDetector.findCuePoints(
                                                audioSamples, sampleRate, 175.0f);

                                        // IMPORTANT: cues returns Sample Indices as if Mono (based on time).
                                        // AudioMixer expects Interleaved Sample Indices (Time * Channels).
                                        // We must scale by channel count.
                                        track.setCueInSample(cues.cueInSample * channels);
                                        track.setCueOutSample(cues.cueOutSample * channels);
                                        track.setTotalSamples(totalSamples);

                                        Log.d(TAG, String.format(
                                                "Cue points detected (accurate) - In: %d, Out: %d, Total: %d (Channels: %d)",
                                                track.getCueInSample(), track.getCueOutSample(), totalSamples,
                                                channels));
                                    } catch (Exception e) {
                                        Log.w(TAG, "Cue point detection failed", e);
                                    }

                                    // Create both track versions
                                    final Track originalTrack = Track.createOriginalVersion(
                                            fileName, tempFile.getAbsolutePath(), bpm);

                                    final Track djReadyTrack = Track.createDJReadyVersion(
                                            fileName, result.stretchedFilePath, bpm,
                                            track.getCueInSample(), track.getCueOutSample(), track.getTotalSamples(),
                                            result.phase);

                                    // Update UI on main thread
                                    mainHandler.post(() -> {
                                        progressDialog.dismiss();

                                        // Add BOTH versions to list
                                        // 1. Original version (for Simple Fade and Overlap)
                                        trackList.add(originalTrack);
                                        trackAdapter.add(originalTrack.getDisplayString());

                                        // 2. DJ Ready version (for DJ Transition)
                                        trackList.add(djReadyTrack);
                                        trackAdapter.add(djReadyTrack.getDisplayString());

                                        trackAdapter.notifyDataSetChanged();

                                        // Set original as current track for playback
                                        currentTrack = originalTrack;
                                        tvCurrentTrack.setText(originalTrack.getName());
                                        tvBPMValue.setText(String.format("%.0f BPM", originalTrack.getBpm()));

                                        // Enable playback buttons
                                        btnPlay.setEnabled(true);
                                        btnStop.setEnabled(true);

                                        // Load ORIGINAL file for playback (user hears original tempo)
                                        try {
                                            audioPlayerManager.loadTrack(originalTrack.getFilePath());
                                            // Extract waveform from original for display
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
                                    // Preprocessing failed
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

            // Handle mixing track selection
            if (selectingTrack1) {
                track1ForMix = selectedTrack;
                tvTrack1Selection.setText(selectedTrack.getName());
                selectingTrack1 = false; // Auto-switch to Track 2
                tvTrack1Selection.setBackgroundColor(0xFFE0E0E0);
                tvTrack2Selection.setBackgroundColor(0xFF90CAF9);
            } else {
                track2ForMix = selectedTrack;
                tvTrack2Selection.setText(selectedTrack.getName());
                selectingTrack1 = true; // Reset
                tvTrack1Selection.setBackgroundColor(0xFFE0E0E0);
                tvTrack2Selection.setBackgroundColor(0xFFE0E0E0);
            }

            // Enable mix button if both tracks selected
            updateMixButtonState();

            // Keep existing playback logic
            currentTrack = selectedTrack;
            tvCurrentTrack.setText(currentTrack.getName());

            // Hide BPM for mixed tracks
            if (currentTrack.isMixed()) {
                tvBPMValue.setText("");
            } else {
                tvBPMValue.setText(String.format("%.1f", currentTrack.getBpm()));
            }

            // Load track
            try {
                audioPlayerManager.loadTrack(currentTrack.getFilePath());
                btnPlay.setEnabled(true);
                btnStop.setEnabled(true);
                // Display waveform with restored markers if they exist
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

        // Get transition type
        int transitionType = spinnerTransitionType.getSelectedItemPosition();
        String transitionName = (String) spinnerTransitionType.getSelectedItem();

        // Validate track types based on transition type
        if (transitionType == 0) {
            // DJ Transition requires DJ Ready tracks (preprocessed at 175 BPM)
            if (!track1ForMix.isPreprocessed() || !track2ForMix.isPreprocessed()) {
                Toast.makeText(this, "DJ Transition requires 'DJ Ready' tracks (175 BPM versions)",
                        Toast.LENGTH_LONG).show();
                return;
            }
        } else {
            // Simple Crossfade and Overlap require Original tracks (unprocessed)
            if (track1ForMix.isPreprocessed() || track2ForMix.isPreprocessed()) {
                Toast.makeText(this, transitionName + " requires 'Original' tracks (not DJ Ready versions)",
                        Toast.LENGTH_LONG).show();
                return;
            }
        }

        // Show status
        tvMixStatus.setVisibility(View.VISIBLE);
        tvMixStatus.setText("Mixing: " + transitionName + "...");
        btnMix.setEnabled(false);

        // Stop any current playback
        audioPlayerManager.stop();

        // Perform mixing in background
        executorService.execute(() -> {
            try {
                Log.d(TAG, "Starting mix operation...");
                Log.d(TAG, "Track1 cue points - In: " + track1ForMix.getCueInSample() +
                        ", Out: " + track1ForMix.getCueOutSample());
                Log.d(TAG, "Track2 cue points - In: " + track2ForMix.getCueInSample() +
                        ", Out: " + track2ForMix.getCueOutSample());

                // Use the Track-based mix method which uses cue points
                AudioMixer.MixResult mixResult = audioMixer.mix(
                        track1ForMix,
                        track2ForMix,
                        transitionType,
                        getCacheDir());

                // Capture track1 duration for marker calculation (computed on background thread!)
                final long track1DurationMs = mixResult.track1DurationMs;

                mainHandler.post(() -> {
                    if (mixResult.outputPath != null) {
                        tvMixStatus.setText("Mix complete! Tap to play.");

                        // Create a Track for the mixed result
                        float avgBpm = (track1ForMix.getBpm() + track2ForMix.getBpm()) / 2;
                        // Extract filename from output path for display
                        String mixedName = new File(mixResult.outputPath).getName();
                        if (mixedName.endsWith(".wav")) {
                            mixedName = mixedName.substring(0, mixedName.length() - 4);
                        }
                        Track mixedTrack = new Track(mixedName, mixResult.outputPath, avgBpm);
                        mixedTrack.setMixed(true);

                        // Add to track list (hide BPM for mixes)
                        trackList.add(mixedTrack);
                        trackAdapter.add(mixedTrack.getName());
                        trackAdapter.notifyDataSetChanged();

                        // Set as current and load for playback
                        currentTrack = mixedTrack;
                        tvCurrentTrack.setText(mixedTrack.getName());
                        tvBPMValue.setText(""); // Hide BPM for mix

                        try {
                            audioPlayerManager.loadTrack(mixResult.outputPath);
                            btnPlay.setEnabled(true);
                            btnStop.setEnabled(true);

                            // Extract and display waveform for mixed track with callback for markers
                            displayWaveform(new File(mixResult.outputPath), () -> {
                                // Calculate marker position based on transition type
                                float startMarkerNorm = -1;
                                float endMarkerNorm = -1;

                                long totalMixDurationMs = audioPlayerManager.getDuration();
                                if (totalMixDurationMs > 0) {
                                    long startMs = 0;
                                    long endMs = 0;

                                    if (transitionType == 0) { // DJ Transition
                                        // DJ Transition uses Preprocessed tracks (Interleaved Stereo samples)
                                        // DJ Fade Duration (~65.8s)
                                        long fadeMs = (long) (65.828f * 1000);

                                        // CueOut is in Interleaved Samples (44.1k Stereo) - BUT preprocessed files are
                                        // Mono (1 ch)
                                        // So we treat samples as frames directly for 44.1k
                                        long cueOutSamples = track1ForMix.getCueOutSample();
                                        long cueOutMs = cueOutSamples * 1000 / 44100;

                                        endMs = cueOutMs;
                                        startMs = Math.max(0, endMs - fadeMs);

                                        Log.d(TAG, "DJ Markers (Time): End=" + endMs + "ms, Start=" + startMs + "ms");

                                    } else {
                                        // Simple Fade / Overlap uses Original tracks
                                        // Use track1DurationMs from MixResult (computed on background thread - FAST!)
                                        if (track1DurationMs > 0) {
                                            // Markers: track2 starts at 80% of track1, track1 ends at 100%
                                            startMs = (long) (track1DurationMs * 0.8f);
                                            endMs = track1DurationMs;

                                            Log.d(TAG, "Simple/Overlap Markers: T1 duration=" + track1DurationMs +
                                                    "ms, Start=" + startMs + "ms, End=" + endMs + "ms");
                                        } else {
                                            Log.e(TAG, "track1DurationMs not available from mixer");
                                            startMs = 0;
                                            endMs = 0;
                                        }
                                    }

                                    startMarkerNorm = (float) startMs / totalMixDurationMs;
                                    endMarkerNorm = (float) endMs / totalMixDurationMs;
                                }

                                if (startMarkerNorm > 0 && startMarkerNorm < 1.0f) {
                                    // Clamp end marker
                                    if (endMarkerNorm > 1.0f)
                                        endMarkerNorm = 1.0f;

                                    // Ensure visible width
                                    if (endMarkerNorm <= startMarkerNorm) {
                                        endMarkerNorm = Math.min(startMarkerNorm + 0.05f, 1.0f);
                                    }

                                    // Save to mixed track for persistence
                                    mixedTrack.setDisplayMarkers(startMarkerNorm, endMarkerNorm);

                                    waveformView.setCueMarkers(startMarkerNorm, endMarkerNorm);
                                    Log.d(TAG, "Cue markers set after mix: start=" + startMarkerNorm + ", end="
                                            + endMarkerNorm);
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
            // Start playhead updates
            playheadHandler.post(playheadUpdater);
        }
    }

    private void pausePlayback() {
        audioPlayerManager.pause();
        btnPlay.setEnabled(true);
        btnPause.setEnabled(false);
        // Stop playhead updates (keep position visible)
        playheadHandler.removeCallbacks(playheadUpdater);
    }

    private void stopPlayback() {
        audioPlayerManager.stop();
        btnPlay.setEnabled(true);
        btnPause.setEnabled(false);
        // Stop playhead updates and reset position
        playheadHandler.removeCallbacks(playheadUpdater);
        waveformView.setPlayheadPosition(-1); // Hide playhead
    }

    private void displayWaveform(File audioFile, Runnable onComplete) {
        Log.d(TAG, "displayWaveform called for file: " + audioFile.getAbsolutePath());

        // Show loading indicator on UI thread
        mainHandler.post(() -> {
            Toast.makeText(this, "Extracting waveform...", Toast.LENGTH_SHORT).show();
        });

        // Extract real waveform from decoded audio
        executorService.execute(() -> {
            try {
                Log.d(TAG, "Starting waveform extraction...");

                // Extract waveform with target of 1500 points for good visualization
                float[] waveformData = WaveformExtractor.extractWaveform(
                        audioFile.getAbsolutePath(),
                        1500);

                Log.d(TAG, "Waveform extraction completed. Data: "
                        + (waveformData != null ? waveformData.length + " points" : "null"));

                if (waveformData == null || waveformData.length == 0) {
                    Log.e(TAG, "Failed to extract waveform");
                    mainHandler.post(() -> {
                        Toast.makeText(DJActivity.this, "Failed to extract waveform", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // Detect cue points using accurate onset-based detection
                // Skip for mixed tracks - they use pre-computed markers from track1DurationMs
                long durationMs = audioPlayerManager.getDuration();
                if (currentTrack != null && currentTrack.getBpm() > 0 && durationMs > 0 && !currentTrack.isMixed()) {
                    try {
                        // Load full audio for accurate cue detection
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
                        Log.d(TAG, "Accurate cue points detected - In: " + cues.cueInSample + ", Out: " + cues.cueOutSample);
                    } catch (Exception e) {
                        Log.w(TAG, "Cue point detection failed", e);
                    }
                }

                // Update waveform view on UI thread
                mainHandler.post(() -> {
                    waveformView.setWaveformData(waveformData);
                    Log.d(TAG, "Waveform displayed with " + waveformData.length + " points");

                    // Clear cue markers when loading a track
                    waveformView.clearMarkers();

                    if (onComplete != null) {
                        onComplete.run();
                    }

                    Toast.makeText(DJActivity.this, "Waveform loaded!", Toast.LENGTH_SHORT).show();
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
        // Stop playhead updates
        if (playheadHandler != null) {
            playheadHandler.removeCallbacks(playheadUpdater);
        }
        audioPlayerManager.release();
        executorService.shutdown();
    }
}