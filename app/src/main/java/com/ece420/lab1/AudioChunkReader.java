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

/**
 * Streaming audio decoder that reads audio files in chunks.
 * Avoids loading entire files into memory for memory-efficient processing.
 */
public class AudioChunkReader implements Closeable {

    private static final String TAG = "AudioChunkReader";

    // Default chunk size: 1 second at 48kHz stereo
    public static final int DEFAULT_CHUNK_SIZE = 48000;

    private MediaExtractor extractor;
    private MediaCodec decoder;
    private int chunkSize;
    private int sampleRate;
    private int channelCount;
    private long durationUs;
    private long totalSamples;

    // Internal buffer for accumulating decoded samples
    private float[] internalBuffer;
    private int internalBufferPos = 0;
    private int internalBufferLen = 0;

    // Decoding state
    private MediaCodec.BufferInfo bufferInfo;
    private boolean inputEOS = false;
    private boolean outputEOS = false;
    private long timeoutUs = 10000;

    // Position tracking
    private long samplesRead = 0;

    /**
     * Open an audio file for streaming chunk-by-chunk reading.
     *
     * @param filePath  Path to audio file
     * @param chunkSize Number of samples per chunk (0 for default)
     * @throws IOException if file cannot be opened or decoded
     */
    public AudioChunkReader(String filePath, int chunkSize) throws IOException {
        this.chunkSize = chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;
        this.internalBuffer = new float[this.chunkSize * 2]; // Extra space for decoder output
        this.bufferInfo = new MediaCodec.BufferInfo();

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(filePath);

            // Find audio track
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
                throw new IOException("No audio track found in: " + filePath);
            }

            extractor.selectTrack(audioTrackIndex);
            MediaFormat format = extractor.getTrackFormat(audioTrackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);

            // Get audio properties
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            durationUs = format.containsKey(MediaFormat.KEY_DURATION) ?
                    format.getLong(MediaFormat.KEY_DURATION) : 0;
            totalSamples = (durationUs * sampleRate * channelCount) / 1_000_000;

            // Create decoder
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();

            Log.d(TAG, "Opened: " + filePath + " (" + sampleRate + "Hz, " +
                    channelCount + "ch, " + String.format("%.1f", durationUs / 1_000_000.0) + "s)");

        } catch (Exception e) {
            close();
            throw new IOException("Failed to open audio file: " + filePath, e);
        }
    }

    /**
     * Open with default chunk size.
     */
    public AudioChunkReader(String filePath) throws IOException {
        this(filePath, DEFAULT_CHUNK_SIZE);
    }

    /**
     * Read the next chunk of audio samples.
     *
     * @return Float array of samples, or null if end of file reached
     * @throws IOException if read fails
     */
    public float[] readNextChunk() throws IOException {
        if (outputEOS && internalBufferLen == 0) {
            return null;
        }

        // Fill internal buffer until we have enough for a chunk
        while (internalBufferLen < chunkSize && !outputEOS) {
            decodeMoreSamples();
        }

        if (internalBufferLen == 0) {
            return null;
        }

        // Return a chunk
        int returnLen = Math.min(chunkSize, internalBufferLen);
        float[] chunk = new float[returnLen];
        System.arraycopy(internalBuffer, 0, chunk, 0, returnLen);

        // Shift remaining data in internal buffer
        if (returnLen < internalBufferLen) {
            System.arraycopy(internalBuffer, returnLen, internalBuffer, 0,
                    internalBufferLen - returnLen);
        }
        internalBufferLen -= returnLen;
        samplesRead += returnLen;

        return chunk;
    }

    /**
     * Read samples into a provided buffer (avoids allocation).
     *
     * @param buffer Buffer to fill
     * @param offset Start offset in buffer
     * @param length Maximum samples to read
     * @return Number of samples read, or -1 if end of file
     * @throws IOException if read fails
     */
    public int readSamples(float[] buffer, int offset, int length) throws IOException {
        if (outputEOS && internalBufferLen == 0) {
            return -1;
        }

        // Fill internal buffer
        while (internalBufferLen < length && !outputEOS) {
            decodeMoreSamples();
        }

        if (internalBufferLen == 0) {
            return -1;
        }

        int copyLen = Math.min(length, internalBufferLen);
        System.arraycopy(internalBuffer, 0, buffer, offset, copyLen);

        // Shift remaining
        if (copyLen < internalBufferLen) {
            System.arraycopy(internalBuffer, copyLen, internalBuffer, 0,
                    internalBufferLen - copyLen);
        }
        internalBufferLen -= copyLen;
        samplesRead += copyLen;

        return copyLen;
    }

    /**
     * Decode more samples from the media codec into internal buffer.
     */
    private void decodeMoreSamples() throws IOException {
        // Feed input
        if (!inputEOS) {
            int inputIndex = decoder.dequeueInputBuffer(timeoutUs);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                int sampleSize = extractor.readSampleData(inputBuffer, 0);

                if (sampleSize < 0) {
                    decoder.queueInputBuffer(inputIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    inputEOS = true;
                } else {
                    long presentationTimeUs = extractor.getSampleTime();
                    decoder.queueInputBuffer(inputIndex, 0, sampleSize,
                            presentationTimeUs, 0);
                    extractor.advance();
                }
            }
        }

        // Get output
        int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs);
        while (outputIndex >= 0) {
            ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);

            if (outputBuffer != null && bufferInfo.size > 0) {
                outputBuffer.order(ByteOrder.LITTLE_ENDIAN);
                outputBuffer.rewind();

                // Ensure internal buffer has space
                ShortBuffer shortBuffer = outputBuffer.asShortBuffer();
                int numSamples = shortBuffer.remaining();

                ensureInternalBufferCapacity(internalBufferLen + numSamples);

                // Convert 16-bit PCM to float
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

    /**
     * Ensure internal buffer has enough capacity.
     */
    private void ensureInternalBufferCapacity(int requiredCapacity) {
        if (internalBuffer.length < requiredCapacity) {
            int newSize = Math.max(requiredCapacity, internalBuffer.length * 2);
            float[] newBuffer = new float[newSize];
            System.arraycopy(internalBuffer, 0, newBuffer, 0, internalBufferLen);
            internalBuffer = newBuffer;
        }
    }

    /**
     * Skip a number of samples.
     *
     * @param samplesToSkip Number of samples to skip
     * @throws IOException if skip fails
     */
    public void skip(long samplesToSkip) throws IOException {
        long remaining = samplesToSkip;

        // First consume from internal buffer
        if (internalBufferLen > 0) {
            int skipFromBuffer = (int) Math.min(remaining, internalBufferLen);
            if (skipFromBuffer < internalBufferLen) {
                System.arraycopy(internalBuffer, skipFromBuffer, internalBuffer, 0,
                        internalBufferLen - skipFromBuffer);
            }
            internalBufferLen -= skipFromBuffer;
            remaining -= skipFromBuffer;
            samplesRead += skipFromBuffer;
        }

        // Then decode and discard
        while (remaining > 0 && !outputEOS) {
            decodeMoreSamples();
            int skipFromBuffer = (int) Math.min(remaining, internalBufferLen);
            if (skipFromBuffer < internalBufferLen) {
                System.arraycopy(internalBuffer, skipFromBuffer, internalBuffer, 0,
                        internalBufferLen - skipFromBuffer);
            }
            internalBufferLen -= skipFromBuffer;
            remaining -= skipFromBuffer;
            samplesRead += skipFromBuffer;
        }
    }

    /**
     * Check if end of file has been reached.
     */
    public boolean isEOF() {
        return outputEOS && internalBufferLen == 0;
    }

    /**
     * Get number of samples read so far.
     */
    public long getSamplesRead() {
        return samplesRead;
    }

    /**
     * Get total estimated samples in file.
     */
    public long getTotalSamples() {
        return totalSamples;
    }

    /**
     * Get sample rate.
     */
    public int getSampleRate() {
        return sampleRate;
    }

    /**
     * Get channel count.
     */
    public int getChannelCount() {
        return channelCount;
    }

    /**
     * Get duration in microseconds.
     */
    public long getDurationUs() {
        return durationUs;
    }

    /**
     * Get duration in seconds.
     */
    public float getDurationSeconds() {
        return durationUs / 1_000_000.0f;
    }

    @Override
    public void close() throws IOException {
        if (decoder != null) {
            try {
                decoder.stop();
                decoder.release();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing decoder", e);
            }
            decoder = null;
        }

        if (extractor != null) {
            extractor.release();
            extractor = null;
        }

        Log.d(TAG, "Closed AudioChunkReader (" + samplesRead + " samples read)");
    }
}
