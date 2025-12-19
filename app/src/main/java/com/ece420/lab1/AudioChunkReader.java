package com.ece420.lab1;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

// streaming audio decoder that reads files in chunks
public class AudioChunkReader implements Closeable {

    private static final String TAG = "AudioChunkReader";
    public static final int DEFAULT_CHUNK_SIZE = 48000;

    private MediaExtractor extractor;
    private MediaCodec decoder;
    private int chunkSize;
    private int sampleRate;
    private int channelCount;
    private long durationUs;
    private long totalSamples;

    private float[] internalBuffer;
    private int internalBufferLen = 0;

    private MediaCodec.BufferInfo bufferInfo;
    private boolean inputEOS = false;
    private boolean outputEOS = false;
    private long timeoutUs = 10000;
    private long samplesRead = 0;

    public AudioChunkReader(String filePath, int chunkSize) throws IOException {
        this.chunkSize = chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;
        this.internalBuffer = new float[this.chunkSize * 2];
        this.bufferInfo = new MediaCodec.BufferInfo();

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(filePath);

            int audioTrackIndex = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    break;
                }
            }

            if (audioTrackIndex == -1) {
                throw new IOException("No audio track found");
            }

            extractor.selectTrack(audioTrackIndex);
            MediaFormat format = extractor.getTrackFormat(audioTrackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);

            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            durationUs = format.containsKey(MediaFormat.KEY_DURATION) ?
                    format.getLong(MediaFormat.KEY_DURATION) : 0;
            totalSamples = (durationUs * sampleRate * channelCount) / 1_000_000;

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();

        } catch (Exception e) {
            close();
            throw new IOException("Failed to open audio file", e);
        }
    }

    public AudioChunkReader(String filePath) throws IOException {
        this(filePath, DEFAULT_CHUNK_SIZE);
    }

    public float[] readNextChunk() throws IOException {
        if (outputEOS && internalBufferLen == 0) return null;

        while (internalBufferLen < chunkSize && !outputEOS) {
            decodeMoreSamples();
        }

        if (internalBufferLen == 0) return null;

        int returnLen = Math.min(chunkSize, internalBufferLen);
        float[] chunk = new float[returnLen];
        System.arraycopy(internalBuffer, 0, chunk, 0, returnLen);

        if (returnLen < internalBufferLen) {
            System.arraycopy(internalBuffer, returnLen, internalBuffer, 0, internalBufferLen - returnLen);
        }
        internalBufferLen -= returnLen;
        samplesRead += returnLen;

        return chunk;
    }

    public int readSamples(float[] buffer, int offset, int length) throws IOException {
        if (outputEOS && internalBufferLen == 0) return -1;

        while (internalBufferLen < length && !outputEOS) {
            decodeMoreSamples();
        }

        if (internalBufferLen == 0) return -1;

        int copyLen = Math.min(length, internalBufferLen);
        System.arraycopy(internalBuffer, 0, buffer, offset, copyLen);

        if (copyLen < internalBufferLen) {
            System.arraycopy(internalBuffer, copyLen, internalBuffer, 0, internalBufferLen - copyLen);
        }
        internalBufferLen -= copyLen;
        samplesRead += copyLen;

        return copyLen;
    }

    private void decodeMoreSamples() throws IOException {
        if (!inputEOS) {
            int inputIndex = decoder.dequeueInputBuffer(timeoutUs);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                int sampleSize = extractor.readSampleData(inputBuffer, 0);

                if (sampleSize < 0) {
                    decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    inputEOS = true;
                } else {
                    long presentationTimeUs = extractor.getSampleTime();
                    decoder.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0);
                    extractor.advance();
                }
            }
        }

        int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs);
        while (outputIndex >= 0) {
            ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);

            if (outputBuffer != null && bufferInfo.size > 0) {
                outputBuffer.order(ByteOrder.LITTLE_ENDIAN);
                outputBuffer.rewind();

                ShortBuffer shortBuffer = outputBuffer.asShortBuffer();
                int numSamples = shortBuffer.remaining();

                ensureInternalBufferCapacity(internalBufferLen + numSamples);

                while (shortBuffer.hasRemaining()) {
                    short sample = shortBuffer.get();
                    internalBuffer[internalBufferLen++] = sample / 32768.0f;
                }
            }

            decoder.releaseOutputBuffer(outputIndex, false);

            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                outputEOS = true;
                break;
            }

            outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0);
        }
    }

    private void ensureInternalBufferCapacity(int requiredCapacity) {
        if (internalBuffer.length < requiredCapacity) {
            int newSize = Math.max(requiredCapacity, internalBuffer.length * 2);
            float[] newBuffer = new float[newSize];
            System.arraycopy(internalBuffer, 0, newBuffer, 0, internalBufferLen);
            internalBuffer = newBuffer;
        }
    }

    public void skip(long samplesToSkip) throws IOException {
        long remaining = samplesToSkip;

        if (internalBufferLen > 0) {
            int skipFromBuffer = (int) Math.min(remaining, internalBufferLen);
            if (skipFromBuffer < internalBufferLen) {
                System.arraycopy(internalBuffer, skipFromBuffer, internalBuffer, 0, internalBufferLen - skipFromBuffer);
            }
            internalBufferLen -= skipFromBuffer;
            remaining -= skipFromBuffer;
            samplesRead += skipFromBuffer;
        }

        while (remaining > 0 && !outputEOS) {
            decodeMoreSamples();
            int skipFromBuffer = (int) Math.min(remaining, internalBufferLen);
            if (skipFromBuffer < internalBufferLen) {
                System.arraycopy(internalBuffer, skipFromBuffer, internalBuffer, 0, internalBufferLen - skipFromBuffer);
            }
            internalBufferLen -= skipFromBuffer;
            remaining -= skipFromBuffer;
            samplesRead += skipFromBuffer;
        }
    }

    public boolean isEOF() { return outputEOS && internalBufferLen == 0; }
    public long getTotalSamples() { return totalSamples; }
    public int getSampleRate() { return sampleRate; }
    public int getChannelCount() { return channelCount; }
    public float getDurationSeconds() { return durationUs / 1_000_000.0f; }

    @Override
    public void close() throws IOException {
        if (decoder != null) {
            try { decoder.stop(); decoder.release(); } catch (Exception e) { Log.w(TAG, "Error releasing decoder", e); }
            decoder = null;
        }
        if (extractor != null) {
            extractor.release();
            extractor = null;
        }
    }
}
