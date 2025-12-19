package com.ece420.lab1;

import android.util.Log;

import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// writes 16 bit pcm wav files
public class WavWriter {

    private static final String TAG = "WavWriter";
    private static final int DEFAULT_SAMPLE_RATE = 44100;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int NUM_CHANNELS = 1;

    // write float samples to wav file
    public static boolean write(float[] samples, String outputPath, int sampleRate) {
        try (FileOutputStream fos = new FileOutputStream(outputPath)) {
            byte[] pcmData = floatToPCM16(samples);
            byte[] header = createWavHeader(pcmData.length, sampleRate);
            fos.write(header);
            fos.write(pcmData);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error writing WAV file", e);
            return false;
        }
    }

    public static boolean write(float[] samples, String outputPath) {
        return write(samples, outputPath, DEFAULT_SAMPLE_RATE);
    }

    // convert float samples to 16 bit pcm
    private static byte[] floatToPCM16(float[] samples) {
        ByteBuffer buffer = ByteBuffer.allocate(samples.length * 2);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        for (float sample : samples) {
            float clamped = Math.max(-1.0f, Math.min(1.0f, sample));
            short pcmSample = (short) (clamped * 32767);
            buffer.putShort(pcmSample);
        }
        return buffer.array();
    }

    // create wav header
    private static byte[] createWavHeader(int pcmDataLength, int sampleRate) {
        int byteRate = sampleRate * NUM_CHANNELS * BITS_PER_SAMPLE / 8;
        int blockAlign = NUM_CHANNELS * BITS_PER_SAMPLE / 8;
        int totalSize = 36 + pcmDataLength;

        ByteBuffer header = ByteBuffer.allocate(44);
        header.order(ByteOrder.LITTLE_ENDIAN);

        // riff header
        header.put((byte) 'R');
        header.put((byte) 'I');
        header.put((byte) 'F');
        header.put((byte) 'F');
        header.putInt(totalSize);
        header.put((byte) 'W');
        header.put((byte) 'A');
        header.put((byte) 'V');
        header.put((byte) 'E');

        // fmt subchunk
        header.put((byte) 'f');
        header.put((byte) 'm');
        header.put((byte) 't');
        header.put((byte) ' ');
        header.putInt(16);
        header.putShort((short) 1);
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

// streaming wav writer for large files
class StreamingWavWriter implements Closeable {

    private static final String TAG = "StreamingWavWriter";
    private static final int BITS_PER_SAMPLE = 16;
    private static final int CHUNK_BUFFER_SIZE = 8192;

    private RandomAccessFile raf;
    private int sampleRate;
    private int numChannels;
    private int totalSamplesWritten = 0;
    private ByteBuffer chunkBuffer;

    public void open(String outputPath, int sampleRate, int numChannels) throws IOException {
        this.sampleRate = sampleRate;
        this.numChannels = numChannels;
        this.raf = new RandomAccessFile(outputPath, "rw");
        this.chunkBuffer = ByteBuffer.allocate(CHUNK_BUFFER_SIZE);
        this.chunkBuffer.order(ByteOrder.LITTLE_ENDIAN);

        byte[] header = createPlaceholderHeader();
        raf.write(header);
        totalSamplesWritten = 0;
    }

    public void open(String outputPath, int sampleRate) throws IOException {
        open(outputPath, sampleRate, 1);
    }

    // write chunk of samples
    public void writeChunk(float[] samples) throws IOException {
        if (raf == null) throw new IOException("StreamingWavWriter not opened");

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

    // write chunk from range
    public void writeChunk(float[] samples, int start, int length) throws IOException {
        if (raf == null) throw new IOException("StreamingWavWriter not opened");

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

    @Override
    public void close() throws IOException {
        if (raf == null) return;

        try {
            int pcmDataLength = totalSamplesWritten * 2;
            int totalSize = 36 + pcmDataLength;

            raf.seek(4);
            writeIntLE(totalSize);
            raf.seek(40);
            writeIntLE(pcmDataLength);
        } finally {
            raf.close();
            raf = null;
        }
    }

    public int getTotalSamplesWritten() {
        return totalSamplesWritten;
    }

    private byte[] createPlaceholderHeader() {
        int byteRate = sampleRate * numChannels * BITS_PER_SAMPLE / 8;
        int blockAlign = numChannels * BITS_PER_SAMPLE / 8;

        ByteBuffer header = ByteBuffer.allocate(44);
        header.order(ByteOrder.LITTLE_ENDIAN);

        header.put((byte) 'R');
        header.put((byte) 'I');
        header.put((byte) 'F');
        header.put((byte) 'F');
        header.putInt(0);
        header.put((byte) 'W');
        header.put((byte) 'A');
        header.put((byte) 'V');
        header.put((byte) 'E');

        header.put((byte) 'f');
        header.put((byte) 'm');
        header.put((byte) 't');
        header.put((byte) ' ');
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) numChannels);
        header.putInt(sampleRate);
        header.putInt(byteRate);
        header.putShort((short) blockAlign);
        header.putShort((short) BITS_PER_SAMPLE);

        header.put((byte) 'd');
        header.put((byte) 'a');
        header.put((byte) 't');
        header.put((byte) 'a');
        header.putInt(0);

        return header.array();
    }

    private void writeIntLE(int value) throws IOException {
        raf.write(value & 0xFF);
        raf.write((value >> 8) & 0xFF);
        raf.write((value >> 16) & 0xFF);
        raf.write((value >> 24) & 0xFF);
    }
}
