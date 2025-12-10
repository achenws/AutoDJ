package com.ece420.lab1;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Custom view for displaying audio waveform similar to Ringdroid/Logic Pro
 * Displays vertical bars representing audio amplitude over time
 */
public class WaveformView extends View {
    private static final String TAG = "WaveformView";

    /**
     * Listener interface for seek events when user interacts with waveform.
     */
    public interface SeekListener {
        void onSeek(float normalizedPosition);
    }

    private SeekListener seekListener;

    // Waveform data
    private float[] waveformData;

    // Paint objects for drawing
    private Paint waveformPaint;
    private Paint centerLinePaint;
    private Paint backgroundPaint;
    private Paint playheadPaint;      // White line for playback position
    private Paint cueMarkerPaint;     // Orange line for cue-out marker
    private Paint fadeEndMarkerPaint; // Lighter orange for fade end

    // Colors
    private int waveformColor = 0xFF4CAF50;  // Green
    private int centerLineColor = 0xFF9E9E9E;  // Gray
    private int backgroundColor = 0xFF1E1E1E;  // Dark background
    private int playheadColor = 0xFFFFFFFF;   // White
    private int cueMarkerColor = 0xFFFF9800;  // Orange
    private int fadeEndColor = 0xFFFFCC80;    // Light orange

    // Drawing parameters
    private float barWidth = 3.0f;
    private float barGap = 1.0f;

    // Playhead and cue marker positions (0.0 to 1.0, or -1 if not set)
    private float playheadPosition = -1;
    private float cueOutPosition = -1;
    private float fadeEndPosition = -1;

    public WaveformView(Context context) {
        super(context);
        init();
    }

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WaveformView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Initialize paint for waveform bars
        waveformPaint = new Paint();
        waveformPaint.setColor(waveformColor);
        waveformPaint.setStyle(Paint.Style.FILL);
        waveformPaint.setAntiAlias(true);

        // Initialize paint for center line
        centerLinePaint = new Paint();
        centerLinePaint.setColor(centerLineColor);
        centerLinePaint.setStyle(Paint.Style.STROKE);
        centerLinePaint.setStrokeWidth(1.0f);
        centerLinePaint.setAntiAlias(true);

        // Initialize paint for background
        backgroundPaint = new Paint();
        backgroundPaint.setColor(backgroundColor);
        backgroundPaint.setStyle(Paint.Style.FILL);

        // Initialize paint for playhead (white vertical line)
        playheadPaint = new Paint();
        playheadPaint.setColor(playheadColor);
        playheadPaint.setStyle(Paint.Style.STROKE);
        playheadPaint.setStrokeWidth(2.0f);
        playheadPaint.setAntiAlias(true);

        // Initialize paint for cue-out marker (orange line)
        cueMarkerPaint = new Paint();
        cueMarkerPaint.setColor(cueMarkerColor);
        cueMarkerPaint.setStyle(Paint.Style.STROKE);
        cueMarkerPaint.setStrokeWidth(3.0f);
        cueMarkerPaint.setAntiAlias(true);

        // Initialize paint for fade end marker (lighter orange)
        fadeEndMarkerPaint = new Paint();
        fadeEndMarkerPaint.setColor(fadeEndColor);
        fadeEndMarkerPaint.setStyle(Paint.Style.STROKE);
        fadeEndMarkerPaint.setStrokeWidth(2.0f);
        fadeEndMarkerPaint.setAntiAlias(true);

        // Setup touch listener for seeking
        setOnTouchListener((v, event) -> {
            if (waveformData == null || waveformData.length == 0) {
                return false;
            }

            float x = event.getX();
            float width = getWidth();

            if (width > 0) {
                float normalized = Math.max(0f, Math.min(1f, x / width));

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_MOVE:
                        // Update playhead position visually during drag
                        setPlayheadPosition(normalized);
                        break;
                    case MotionEvent.ACTION_UP:
                        // Seek to position on release
                        if (seekListener != null) {
                            seekListener.onSeek(normalized);
                        }
                        break;
                }
            }
            return true;
        });
    }

    /**
     * Set the waveform data to display
     * @param data Array of normalized amplitude values (-1.0 to 1.0)
     */
    public void setWaveformData(float[] data) {
        this.waveformData = data;
        invalidate();  // Trigger redraw
    }

    /**
     * Clear the waveform display
     */
    public void clearWaveform() {
        this.waveformData = null;
        invalidate();
    }

    /**
     * Set the color of the waveform bars
     */
    public void setWaveformColor(int color) {
        this.waveformColor = color;
        waveformPaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Draw background
        canvas.drawRect(0, 0, getWidth(), getHeight(), backgroundPaint);

        // If no data, show message
        if (waveformData == null || waveformData.length == 0) {
            // Draw placeholder text
            Paint textPaint = new Paint();
            textPaint.setColor(0xFFFFFFFF);
            textPaint.setTextSize(40);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("No waveform loaded", getWidth() / 2.0f, getHeight() / 2.0f, textPaint);
            return;
        }

        float width = getWidth();
        float height = getHeight();
        float centerY = height / 2.0f;

        // Draw center line
        canvas.drawLine(0, centerY, width, centerY, centerLinePaint);

        // Calculate how many bars we can fit
        float totalBarWidth = barWidth + barGap;
        int numBars = (int) (width / totalBarWidth);

        // If we have more data points than bars, downsample
        // If we have fewer data points than bars, spread them out
        int dataLength = waveformData.length;

        if (dataLength == 0) return;

        // Draw each bar
        for (int i = 0; i < numBars; i++) {
            // Map bar index to data index
            int dataIndex = (int) ((float) i / numBars * dataLength);
            if (dataIndex >= dataLength) dataIndex = dataLength - 1;

            float amplitude = waveformData[dataIndex];
            // Clamp amplitude to prevent overflow when magnitude > 1.0
            float clampedAmplitude = Math.max(-1.0f, Math.min(1.0f, amplitude));

            // Calculate bar position
            float x = i * totalBarWidth;

            // Calculate bar height (amplitude ranges from -1 to 1)
            // Scale to half the view height
            float barHeight = Math.abs(clampedAmplitude) * (height / 2.0f) * 0.9f;  // 0.9 for padding

            // Draw bar extending from center in both directions
            float top = centerY - barHeight;
            float bottom = centerY + barHeight;

            // Draw rounded rectangle for smoother appearance
            RectF rect = new RectF(x, top, x + barWidth, bottom);
            canvas.drawRoundRect(rect, barWidth / 2.0f, barWidth / 2.0f, waveformPaint);
        }

        // Draw cue markers (behind playhead)
        drawMarkers(canvas, width, height);
    }

    /**
     * Draw playhead and cue markers on top of waveform
     */
    private void drawMarkers(Canvas canvas, float width, float height) {
        // Draw cue-out marker (orange vertical line)
        if (cueOutPosition >= 0 && cueOutPosition <= 1) {
            float x = cueOutPosition * width;
            canvas.drawLine(x, 0, x, height, cueMarkerPaint);
        }

        // Draw fade-end marker (lighter orange vertical line)
        if (fadeEndPosition >= 0 && fadeEndPosition <= 1) {
            float x = fadeEndPosition * width;
            canvas.drawLine(x, 0, x, height, fadeEndMarkerPaint);
        }

        // Draw playhead (white vertical line) - on top
        if (playheadPosition >= 0 && playheadPosition <= 1) {
            float x = playheadPosition * width;
            canvas.drawLine(x, 0, x, height, playheadPaint);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // Ensure we have at least some minimum height
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        // Minimum height of 200dp
        int minHeight = (int) (200 * getResources().getDisplayMetrics().density);
        if (height < minHeight) {
            height = minHeight;
        }

        setMeasuredDimension(width, height);
    }

    /**
     * Set the playhead position (for real-time playback tracking).
     * @param normalized Position from 0.0 (start) to 1.0 (end), or -1 to hide
     */
    public void setPlayheadPosition(float normalized) {
        this.playheadPosition = normalized;
        invalidate();
    }

    /**
     * Set the cue marker positions.
     * @param cueOut Cue-out position (0.0 to 1.0) where transition starts, or -1 to hide
     * @param fadeEnd Fade-end position (0.0 to 1.0) where transition ends, or -1 to hide
     */
    public void setCueMarkers(float cueOut, float fadeEnd) {
        this.cueOutPosition = cueOut;
        this.fadeEndPosition = fadeEnd;
        invalidate();
    }

    /**
     * Clear all markers (playhead and cue markers).
     */
    public void clearMarkers() {
        this.playheadPosition = -1;
        this.cueOutPosition = -1;
        this.fadeEndPosition = -1;
        invalidate();
    }

    /**
     * Set the seek listener for when user taps/drags on waveform.
     * @param listener Listener to receive seek events
     */
    public void setSeekListener(SeekListener listener) {
        this.seekListener = listener;
    }
}
