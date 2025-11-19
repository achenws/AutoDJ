package com.ece420.lab1;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Custom view for displaying audio waveform similar to Ringdroid/Logic Pro
 * Displays vertical bars representing audio amplitude over time
 */
public class WaveformView extends View {
    private static final String TAG = "WaveformView";

    // Waveform data
    private float[] waveformData;

    // Paint objects for drawing
    private Paint waveformPaint;
    private Paint centerLinePaint;
    private Paint backgroundPaint;

    // Colors
    private int waveformColor = 0xFF4CAF50;  // Green
    private int centerLineColor = 0xFF9E9E9E;  // Gray
    private int backgroundColor = 0xFF1E1E1E;  // Dark background

    // Drawing parameters
    private float barWidth = 3.0f;
    private float barGap = 1.0f;

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
    }

    /**
     * Set the waveform data to display
     * @param data Array of normalized amplitude values (-1.0 to 1.0)
     */
    public void setWaveformData(float[] data) {
        this.waveformData = data;
        android.util.Log.d(TAG, "setWaveformData called with " + (data != null ? data.length : 0) + " points");
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
            android.util.Log.d(TAG, "onDraw: No waveform data to display");
            // Draw placeholder text
            Paint textPaint = new Paint();
            textPaint.setColor(0xFFFFFFFF);
            textPaint.setTextSize(40);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("No waveform loaded", getWidth() / 2.0f, getHeight() / 2.0f, textPaint);
            return;
        }

        android.util.Log.d(TAG, "onDraw: Drawing waveform with " + waveformData.length + " points");

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

            // Calculate bar position
            float x = i * totalBarWidth;

            // Calculate bar height (amplitude ranges from -1 to 1)
            // Scale to half the view height
            float barHeight = Math.abs(amplitude) * (height / 2.0f) * 0.9f;  // 0.9 for padding

            // Draw bar extending from center in both directions
            float top = centerY - barHeight;
            float bottom = centerY + barHeight;

            // Draw rounded rectangle for smoother appearance
            RectF rect = new RectF(x, top, x + barWidth, bottom);
            canvas.drawRoundRect(rect, barWidth / 2.0f, barWidth / 2.0f, waveformPaint);
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
}
