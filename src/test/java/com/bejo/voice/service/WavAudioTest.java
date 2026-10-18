package com.bejo.voice.service;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class WavAudioTest {

    private static String tag(byte[] wav, int at) {
        return new String(wav, at, 4, StandardCharsets.US_ASCII);
    }

    @Test
    void wrapsPcmInACanonical44ByteHeader() {
        byte[] pcm = {1, 2, 3, 4, 5, 6};

        byte[] wav = WavAudio.wrap(pcm, 16000);
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);

        assertThat(wav).hasSize(44 + pcm.length);
        assertThat(tag(wav, 0)).isEqualTo("RIFF");
        assertThat(b.getInt(4)).isEqualTo(36 + pcm.length);   // file size minus the 8 bytes of "RIFF" + this field
        assertThat(tag(wav, 8)).isEqualTo("WAVE");
        assertThat(tag(wav, 12)).isEqualTo("fmt ");
        assertThat(b.getInt(16)).isEqualTo(16);                // fmt chunk size
        assertThat(b.getShort(20)).isEqualTo((short) 1);       // PCM
        assertThat(b.getShort(22)).isEqualTo((short) 1);       // mono
        assertThat(b.getInt(24)).isEqualTo(16000);             // sample rate
        assertThat(b.getInt(28)).isEqualTo(32000);             // byte rate = 16000 x 1 x 2
        assertThat(b.getShort(32)).isEqualTo((short) 2);       // block align
        assertThat(b.getShort(34)).isEqualTo((short) 16);      // bits per sample
        assertThat(tag(wav, 36)).isEqualTo("data");
        assertThat(b.getInt(40)).isEqualTo(pcm.length);
        assertThat(Arrays.copyOfRange(wav, 44, wav.length)).isEqualTo(pcm);
    }

    @Test
    void theRateGoesIntoTheHeaderAndTheByteRate() {
        byte[] wav = WavAudio.wrap(new byte[]{0, 0}, 44100);
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);

        assertThat(b.getInt(24)).isEqualTo(44100);
        assertThat(b.getInt(28)).isEqualTo(88200);
    }

    @Test
    void sizesFollowTheLengthOfTheAudio() {
        byte[] oneSecond = new byte[32000];

        ByteBuffer b = ByteBuffer.wrap(WavAudio.wrap(oneSecond, 16000)).order(ByteOrder.LITTLE_ENDIAN);

        assertThat(b.getInt(4)).isEqualTo(36 + 32000);
        assertThat(b.getInt(40)).isEqualTo(32000);
        assertThat(b.capacity()).isEqualTo(44 + 32000);
    }

    @Test
    void mimeTypeIsWav() {
        assertThat(WavAudio.MIME_TYPE).isEqualTo("audio/wav");
    }
}
