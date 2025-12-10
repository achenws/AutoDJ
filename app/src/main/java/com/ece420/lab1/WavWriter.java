package com.ece420.lab1;

import android.util.Log;

import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Simple WAV file writer for PCM audio data.
 * Writes standard 16-bit PCM WAV files at 44100 Hz mono.
 */
public class WavWriter {

    private static final String TAG = "WavWriter";
    private static final int DEFAULT_SAMPLE_RATE = 44100;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int NUM_CHANNELS = 1;  // Mono

    /**
     * Write float audio samples to a WAV file.
     *
     * @param samples    Audio samples (normalized -1.0 to 1.0)
     * @param outputPath Path for output WAV file
     * @param sampleRate Sample rate in Hz (default 44100)
     * @return true if successful, false otherwise
     */
    public static boolean write(float[] samples, String outputPath, int sampleRate) {
        try (FileOutputStream fos = new FileOutputStream(outputPath)) {
            // Convert float samples to 16-bit PCM
            byte[] pcmData = floatToPCM16(samples);

            // Write WAV header
            byte[] header = createWavHeader(pcmData.length, sampleRate);
            fos.write(header);

            // Write PCM data
            fos.write(pcmData);

            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error writing WAV file", e);
            return false;
        }
    }

    /**
     * Write float audio samples to a WAV file using default sample rate.
     *
     * @param samples    Audio samples (normalized -1.0 to 1.0)
     * @param outputPath Path for output WAV file
     * @return true if successful, false otherwise
     */
    public static boolean write(float[] samples, String outputPath) {
        return write(samples, outputPath, DEFAULT_SAMPLE_RATE);
    }

    /**
     * Convert float samples (-1.0 to 1.0) to 16-bit PCM bytes.
     */
    private static byte[] floatToPCM16(float[] samples) {
        ByteBuffer buffer = ByteBuffer.allocate(samples.length * 2);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        for (float sample : samples) {
            // Clamp to valid range
            float clamped = Math.max(-1.0f, Math.min(1.0f, sample));

            // Convert to 16-bit signed integer
            short pcmSample = (short) (clamped * 32767);
            buffer.putShort(pcmSample);
        }

        return buffer.array();
    }

    /**
     * Create a standard WAV header (44 bytes).
     *
     * @param pcmDataLength Length of PCM data in bytes
     * @param sampleRate    Sample rate in Hz
     * @return WAV header bytes
     */
    private static byte[] createWavHeader(int pcmDataLength, int sampleRate) {
        int byteRate = sampleRate * NUM_CHANNELS * BITS_PER_SAMPLE / 8;
        int blockAlign = NUM_CHANNELS * BITS_PER_SAMPLE / 8;
        int totalSize = 36 + pcmDataLength;

        ByteBuffer header = ByteBuffer.allocate(44);
        header.order(ByteOrder.LITTLE_ENDIAN);

        // RIFF header
        header.put((byte) 'R');
        header.put((byte) 'I');
        header.put((byte) 'F');
        header.put((byte) 'F');
        header.putInt(totalSize);          // File size - 8
        header.put((byte) 'W');
        header.put((byte) 'A');
        header.put((byte) 'V');
        header.put((byte) 'E');

        // fmt subchunk
        header.put((byte) 'f');
        header.put((byte) 'm');
        header.put((byte) 't');
        header.put((byte) ' ');
        header.putInt(16);                 // Subchunk1 size (16 for PCM)
        header.putShort((short) 1);        // Audio format (1 = PCM)
        header.putShort((short) NUM_CHANNELS);
        header.putInt(sampleRate);
        header.putInt(byteRate);
        header.putShort((short) blockAlign);
        header.putShort((short) BITS_PER_SAMPLE);

        // data subchunk
        header.put((byte) 'd');
        header.put((byte) 'a');
        header.put((byte) 't');
        header.put((byte) 'a');
        header.putInt(pcmDataLength);

        return header.array();
    }
}


/**
 * Streaming WAV writer for memory-efficient audio output.
 * Writes audio chunks incrementally without holding entire file in memory.
 */
class StreamingWavWriter implements Closeable {

    private static final String TAG = "StreamingWavWriter";
    private static final int BITS_PER_SAMPLE = 16;

    private RandomAccessFile raf;
    private int sampleRate;
    private int numChannels;
    private int totalSamplesWritten = 0;
    private ByteBuffer chunkBuffer;
    private static final int CHUNK_BUFFER_SIZE = 8192; // Reusable buffer for PCM conversion

    /**
     * Open a new WAV file for streaming writes.
     *
     * @param outputPath  Path to output file
     * @param sampleRate  Sample rate in Hz
     * @param numChannels Number of audio channels (1=mono, 2=stereo)
     * @throws IOException if file cannot be created
     */
    public void open(String outputPath, int sampleRate, int numChannels) throws IOException {
        this.sampleRate = sampleRate;
        this.numChannels = numChannels;
        this.raf = new RandomAccessFile(outputPath, "rw");
        this.chunkBuffer = ByteBuffer.allocate(CHUNK_BUFFER_SIZE);
        this.chunkBuffer.order(ByteOrder.LITTLE_ENDIAN);

        // Write placeholder header (will update on close)
        byte[] header = createPlaceholderHeader();
        raf.write(header);

        totalSamplesWritten = 0;
    }

    /**
     * Open a new WAV file for streaming writes (mono).
     */
    public void open(String outputPath, int sampleRate) throws IOException {
        open(outputPath, sampleRate, 1);
    }

    /**
     * Write a chunk of audio samples.
     *
     * @param samples Float samples (-1.0 to 1.0)
     * @throws IOException if write fails
     */
    public void writeChunk(float[] samples) throws IOException {
        if (raf == null) {
            throw new IOException("StreamingWavWriter not opened");
        }

        // Convert and write in sub-chunks to limit memory usage
        int offset = 0;
        while (offset < samples.length) {
            chunkBuffer.clear();
            int count = Math.min(samples.length - offset, CHUNK_BUFFER_SIZE / 2);

            for (int i = 0; i < count; i++) {
                float sample = samples[offset + i];
                float clamped = Math.max(-1.0f, Math.min(1.0f, sample));
                short pcmSample = (short) (clamped * 32767);
                chunkBuffer.putShort(pcmSample);
            }

            raf.write(chunkBuffer.array(), 0, count * 2);
            offset += count;
        }

        totalSamplesWritten += samples.length;
    }

    /**
     * Write a chunk of audio samples from a specific range.
     *
     * @param samples Float samples array
     * @param start   Start index
     * @param length  Number of samples to write
     * @throws IOException if write fails
     */
    public void writeChunk(float[] samples, int start, int length) throws IOException {
        if (raf == null) {
            throw new IOException("StreamingWavWriter not opened");
        }

        int end = Math.min(start + length, samples.length);
        int offset = start;

        while (offset < end) {
            chunkBuffer.clear();
            int count = Math.min(end - offset, CHUNK_BUFFER_SIZE / 2);

            for (int i = 0; i < count; i++) {
                float sample = samples[offset + i];
                float clamped = Math.max(-1.0f, Math.min(1.0f, sample));
                short pcmSample = (short) (clamped * 32767);
                chunkBuffer.putShort(pcmSample);
            }

            raf.write(chunkBuffer.array(), 0, count * 2);
            offset += count;
        }

        totalSamplesWritten += (end - start);
    }

    /**
     * Close the file and update the WAV header with final size.
     */
    @Override
    public void close() throws IOException {
        if (raf == null) {
            return;
        }

        try {
            // Update header with final sizes
            int pcmDataLength = totalSamplesWritten * 2;  // 16-bit = 2 bytes per sample
            int totalSize = 36 + pcmDataLength;

            // Seek to file size field (offset 4)
            raf.seek(4);
            writeIntLE(totalSize);

            // Seek to data size field (offset 40)
            raf.seek(40);
            writeIntLE(pcmDataLength);
        } finally {
            raf.close();
            raf = null;
        }
    }

    /**
     * Get total samples written so far.
     */
    public int getTotalSamplesWritten() {
        return totalSamplesWritten;
    }

    /**
     * Create a placeholder WAV header (44 bytes) with zeroed size fields.
     */
    private byte[] createPlaceholderHeader() {
        int byteRate = sampleRate * numChannels * BITS_PER_SAMPLE / 8;
        int blockAlign = numChannels * BITS_PER_SAMPLE / 8;

        ByteBuffer header = ByteBuffer.allocate(44);
        header.order(ByteOrder.LITTLE_ENDIAN);

        // RIFF header
        header.put((byte) 'R');
        header.put((byte) 'I');
        header.put((byte) 'F');
        header.put((byte) 'F');
        header.putInt(0);                  // Placeholder - will update on close
        header.put((byte) 'W');
        header.put((byte) 'A');
        header.put((byte) 'V');
        header.put((byte) 'E');

        // fmt subchunk
        header.put((byte) 'f');
        header.put((byte) 'm');
        header.put((byte) 't');
        header.put((byte) ' ');
        header.putInt(16);                 // Subchunk1 size (16 for PCM)
        header.putShort((short) 1);        // Audio format (1 = PCM)
        header.putShort((short) numChannels);
        header.putInt(sampleRate);
        header.putInt(byteRate);
        header.putShort((short) blockAlign);
        header.putShort((short) BITS_PER_SAMPLE);

        // data subchunk
        header.put((byte) 'd');
        header.put((byte) 'a');
        header.put((byte) 't');
        header.put((byte) 'a');
        header.putInt(0);                  // Placeholder - will update on close

        return header.array();
    }

    /**
     * Write an int in little-endian format at current position.
     */
    private void writeIntLE(int value) throws IOException {
        raf.write(value & 0xFF);
        raf.write((value >> 8) & 0xFF);
        raf.write((value >> 16) & 0xFF);
        raf.write((value >> 24) & 0xFF);
    }
}
