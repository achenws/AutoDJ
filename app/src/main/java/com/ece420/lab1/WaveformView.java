package com.ece420.lab1;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

// custom view for audio waveform display
public class WaveformView extends View {

    public interface SeekListener {
        void onSeek(float normalizedPosition);
    }

    private SeekListener seekListener;
    private float[] waveformData;

    private Paint waveformPaint;
    private Paint centerLinePaint;
    private Paint backgroundPaint;
    private Paint playheadPaint;
    private Paint cueMarkerPaint;
    private Paint fadeEndMarkerPaint;

    private int waveformColor = 0xFF4CAF50;
    private int centerLineColor = 0xFF9E9E9E;
    private int backgroundColor = 0xFF1E1E1E;
    private int playheadColor = 0xFFFFFFFF;
    private int cueMarkerColor = 0xFFFF9800;
    private int fadeEndColor = 0xFFFFCC80;

    private float barWidth = 3.0f;
    private float barGap = 1.0f;

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
        waveformPaint = new Paint();
        waveformPaint.setColor(waveformColor);
        waveformPaint.setStyle(Paint.Style.FILL);
        waveformPaint.setAntiAlias(true);

        centerLinePaint = new Paint();
        centerLinePaint.setColor(centerLineColor);
        centerLinePaint.setStyle(Paint.Style.STROKE);
        centerLinePaint.setStrokeWidth(1.0f);
        centerLinePaint.setAntiAlias(true);

        backgroundPaint = new Paint();
        backgroundPaint.setColor(backgroundColor);
        backgroundPaint.setStyle(Paint.Style.FILL);

        playheadPaint = new Paint();
        playheadPaint.setColor(playheadColor);
        playheadPaint.setStyle(Paint.Style.STROKE);
        playheadPaint.setStrokeWidth(2.0f);
        playheadPaint.setAntiAlias(true);

        cueMarkerPaint = new Paint();
        cueMarkerPaint.setColor(cueMarkerColor);
        cueMarkerPaint.setStyle(Paint.Style.STROKE);
        cueMarkerPaint.setStrokeWidth(3.0f);
        cueMarkerPaint.setAntiAlias(true);

        fadeEndMarkerPaint = new Paint();
        fadeEndMarkerPaint.setColor(fadeEndColor);
        fadeEndMarkerPaint.setStyle(Paint.Style.STROKE);
        fadeEndMarkerPaint.setStrokeWidth(2.0f);
        fadeEndMarkerPaint.setAntiAlias(true);

        setOnTouchListener((v, event) -> {
            if (waveformData == null || waveformData.length == 0) return false;

            float x = event.getX();
            float width = getWidth();

            if (width > 0) {
                float normalized = Math.max(0f, Math.min(1f, x / width));

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_MOVE:
                        setPlayheadPosition(normalized);
                        break;
                    case MotionEvent.ACTION_UP:
                        if (seekListener != null) seekListener.onSeek(normalized);
                        break;
                }
            }
            return true;
        });
    }

    public void setWaveformData(float[] data) {
        this.waveformData = data;
        invalidate();
    }

    public void clearWaveform() {
        this.waveformData = null;
        invalidate();
    }

    public void setWaveformColor(int color) {
        this.waveformColor = color;
        waveformPaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        canvas.drawRect(0, 0, getWidth(), getHeight(), backgroundPaint);

        if (waveformData == null || waveformData.length == 0) {
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

        canvas.drawLine(0, centerY, width, centerY, centerLinePaint);

        float totalBarWidth = barWidth + barGap;
        int numBars = (int) (width / totalBarWidth);
        int dataLength = waveformData.length;

        if (dataLength == 0) return;

        for (int i = 0; i < numBars; i++) {
            int dataIndex = (int) ((float) i / numBars * dataLength);
            if (dataIndex >= dataLength) dataIndex = dataLength - 1;

            float amplitude = waveformData[dataIndex];
            float clampedAmplitude = Math.max(-1.0f, Math.min(1.0f, amplitude));
            float x = i * totalBarWidth;
            float barHeight = Math.abs(clampedAmplitude) * (height / 2.0f) * 0.9f;

            float top = centerY - barHeight;
            float bottom = centerY + barHeight;

            RectF rect = new RectF(x, top, x + barWidth, bottom);
            canvas.drawRoundRect(rect, barWidth / 2.0f, barWidth / 2.0f, waveformPaint);
        }

        drawMarkers(canvas, width, height);
    }

    private void drawMarkers(Canvas canvas, float width, float height) {
        if (cueOutPosition >= 0 && cueOutPosition <= 1) {
            float x = cueOutPosition * width;
            canvas.drawLine(x, 0, x, height, cueMarkerPaint);
        }

        if (fadeEndPosition >= 0 && fadeEndPosition <= 1) {
            float x = fadeEndPosition * width;
            canvas.drawLine(x, 0, x, height, fadeEndMarkerPaint);
        }

        if (playheadPosition >= 0 && playheadPosition <= 1) {
            float x = playheadPosition * width;
            canvas.drawLine(x, 0, x, height, playheadPaint);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        int minHeight = (int) (200 * getResources().getDisplayMetrics().density);
        if (height < minHeight) height = minHeight;

        setMeasuredDimension(width, height);
    }

    public void setPlayheadPosition(float normalized) {
        this.playheadPosition = normalized;
        invalidate();
    }

    public void setCueMarkers(float cueOut, float fadeEnd) {
        this.cueOutPosition = cueOut;
        this.fadeEndPosition = fadeEnd;
        invalidate();
    }

    public void clearMarkers() {
        this.playheadPosition = -1;
        this.cueOutPosition = -1;
        this.fadeEndPosition = -1;
        invalidate();
    }

    public void setSeekListener(SeekListener listener) {
        this.seekListener = listener;
    }
}
