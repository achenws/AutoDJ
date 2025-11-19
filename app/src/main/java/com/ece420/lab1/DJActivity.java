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
import android.widget.TextView;
import android.widget.Toast;
import android.Manifest;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

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
    private GraphView waveformGraph;
    private LineGraphSeries<DataPoint> waveformSeries;

    // Audio Components
    private AudioPlayerManager audioPlayerManager;
    private SimpleBPMDetector bpmDetector;

    // Data
    private List<Track> trackList;
    private ArrayAdapter<String> trackAdapter;
    private Track currentTrack;

    // Threading
    private ExecutorService executorService;
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dj);

        // Initialize components
        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        trackList = new ArrayList<>();
        audioPlayerManager = new AudioPlayerManager();
        bpmDetector = new SimpleBPMDetector();

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
        waveformGraph = findViewById(R.id.waveformGraph);

        // Setup graph
        waveformSeries = new LineGraphSeries<>();
        waveformGraph.addSeries(waveformSeries);
        waveformGraph.getViewport().setXAxisBoundsManual(true);
        waveformGraph.getViewport().setYAxisBoundsManual(true);
        waveformGraph.getViewport().setMinX(0);
        waveformGraph.getViewport().setMaxX(1000);
        waveformGraph.getViewport().setMinY(-1);
        waveformGraph.getViewport().setMaxY(1);

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
    }

    private void checkPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // Android 13+ uses READ_MEDIA_AUDIO
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_MEDIA_AUDIO},
                    PERMISSION_REQUEST_CODE);
            }
        } else {
            // Older versions use READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
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
                FILE_SELECT_CODE
            );
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
                    // Detect BPM
                    float bpm = bpmDetector.detectBPM(tempFile.getAbsolutePath());

                    // Create track object
                    Track track = new Track(fileName, tempFile.getAbsolutePath(), bpm);

                    // Update UI on main thread
                    mainHandler.post(() -> {
                        // Add to list
                        trackList.add(track);
                        trackAdapter.add(String.format("%s - %.1f BPM", track.getName(), track.getBpm()));
                        trackAdapter.notifyDataSetChanged();

                        // Set as current track
                        currentTrack = track;
                        tvCurrentTrack.setText(track.getName());
                        tvBPMValue.setText(String.format("%.1f", track.getBpm()));

                        // Enable playback buttons
                        btnPlay.setEnabled(true);
                        btnStop.setEnabled(true);

                        // Load into player
                        try {
                            audioPlayerManager.loadTrack(track.getFilePath());
                            displayWaveform(tempFile);
                        } catch (IOException e) {
                            Log.e(TAG, "Error loading track", e);
                            Toast.makeText(DJActivity.this, "Error loading track", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing file", e);
                mainHandler.post(() -> {
                    tvBPMValue.setText("Error");
                    Toast.makeText(DJActivity.this, "Error processing file", Toast.LENGTH_SHORT).show();
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
            currentTrack = trackList.get(position);
            tvCurrentTrack.setText(currentTrack.getName());
            tvBPMValue.setText(String.format("%.1f", currentTrack.getBpm()));

            // Load track
            try {
                audioPlayerManager.loadTrack(currentTrack.getFilePath());
                btnPlay.setEnabled(true);
                btnStop.setEnabled(true);
                // Display waveform
                displayWaveform(new File(currentTrack.getFilePath()));
            } catch (IOException e) {
                Log.e(TAG, "Error loading track", e);
                Toast.makeText(this, "Error loading track", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void playCurrentTrack() {
        if (currentTrack != null) {
            audioPlayerManager.play();
            btnPause.setEnabled(true);
            btnPlay.setEnabled(false);
        }
    }

    private void pausePlayback() {
        audioPlayerManager.pause();
        btnPlay.setEnabled(true);
        btnPause.setEnabled(false);
    }

    private void stopPlayback() {
        audioPlayerManager.stop();
        btnPlay.setEnabled(true);
        btnPause.setEnabled(false);
    }

    private void displayWaveform(File audioFile) {
        // Simple waveform display - read some samples and show them
        executorService.execute(() -> {
            try {
                // Read file (simplified - just read some bytes)
                InputStream is = new java.io.FileInputStream(audioFile);
                byte[] buffer = new byte[1000];  // Read 1000 samples for simple display
                is.read(buffer);
                is.close();

                // Convert to DataPoints (simplified)
                DataPoint[] dataPoints = new DataPoint[buffer.length];
                for (int i = 0; i < buffer.length; i++) {
                    // Normalize byte to -1 to 1 range
                    float value = buffer[i] / 128.0f;
                    dataPoints[i] = new DataPoint(i, value);
                }

                // Update graph on UI thread
                mainHandler.post(() -> {
                    waveformSeries.resetData(dataPoints);
                    waveformGraph.getViewport().setMaxX(dataPoints.length);
                });
            } catch (Exception e) {
                Log.e(TAG, "Error displaying waveform", e);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        audioPlayerManager.release();
        executorService.shutdown();
    }
}