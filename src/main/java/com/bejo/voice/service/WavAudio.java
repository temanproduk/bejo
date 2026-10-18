package com.bejo.voice.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Wraps raw 16-bit mono PCM in the 44-byte WAV (RIFF) header that Gemini needs to accept it as {@code audio/wav}. */
final class WavAudio {

    static final int HEADER_BYTES = 44;
    static final String MIME_TYPE = "audio/wav";

    private WavAudio() {}

    static byte[] wrap(byte[] pcm16Mono, int sampleRateHertz) {
        int channels = 1;
        int bitsPerSample = 16;
        int byteRate = sampleRateHertz * channels * bitsPerSample / 8;
        ByteBuffer wav = ByteBuffer.allocate(HEADER_BYTES + pcm16Mono.length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R', 'I', 'F', 'F'});
        wav.putInt(36 + pcm16Mono.length);                   // everything after this field
        wav.put(new byte[]{'W', 'A', 'V', 'E'});
        wav.put(new byte[]{'f', 'm', 't', ' '});
        wav.putInt(16);                                      // size of the fmt chunk
        wav.putShort((short) 1);                             // PCM
        wav.putShort((short) channels);
        wav.putInt(sampleRateHertz);
        wav.putInt(byteRate);
        wav.putShort((short) (channels * bitsPerSample / 8)); // block align
        wav.putShort((short) bitsPerSample);
        wav.put(new byte[]{'d', 'a', 't', 'a'});
        wav.putInt(pcm16Mono.length);
        wav.put(pcm16Mono);
        return wav.array();
    }
}
