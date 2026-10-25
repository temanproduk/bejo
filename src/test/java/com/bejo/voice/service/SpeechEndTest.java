package com.bejo.voice.service;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SpeechEndTest {

    private static final int RATE = 16_000;

    /** {@code seconds} of a tone at {@code level} of full scale, or noise when {@code noise}. */
    private static byte[] sound(double seconds, double level, boolean noise) {
        byte[] pcm = new byte[(int) (seconds * RATE) * 2];
        Random random = new Random(7);
        for (int i = 0; i < pcm.length / 2; i++) {
            double wave = noise ? random.nextGaussian() * level : Math.sin(2 * Math.PI * 220 * i / RATE) * level * Math.sqrt(2);
            short sample = (short) Math.max(-32768, Math.min(32767, Math.round(wave * 32768)));
            pcm[2 * i] = (byte) sample;
            pcm[2 * i + 1] = (byte) (sample >> 8);
        }
        return pcm;
    }

    private static byte[] join(byte[]... parts) {
        int length = 0;
        for (byte[] p : parts) length += p.length;
        byte[] all = new byte[length];
        int at = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, all, at, p.length); at += p.length; }
        return all;
    }

    @Test
    void silenceHasNoSpeech() {
        SpeechEnd end = new SpeechEnd();
        end.add(new byte[RATE * 2 * 3]);

        assertThat(end.endSeconds()).isEqualTo(-1);
    }

    @Test
    void findsWhereTheLastSpeechEnds() {
        SpeechEnd end = new SpeechEnd();
        // 1 s quiet, 1 s speech, 1 s quiet, 0.5 s speech, 1 s quiet
        end.add(join(sound(1, 0.003, true), sound(1, 0.2, false), sound(1, 0.003, true), sound(0.5, 0.2, false), sound(1, 0.003, true)));

        assertThat(end.endSeconds()).isCloseTo(3.5, within(0.05));
    }

    @Test
    void doesNotCareHowTheRecordingIsCutIntoPieces() {
        byte[] all = join(sound(1, 0.003, true), sound(1.3, 0.2, false), sound(1, 0.003, true));
        SpeechEnd whole = new SpeechEnd();
        whole.add(all);
        SpeechEnd pieces = new SpeechEnd();
        for (int at = 0; at < all.length; at += 3_200 + 14) {   // odd sizes on purpose
            pieces.add(java.util.Arrays.copyOfRange(all, at, Math.min(all.length, at + 3_200 + 14)));
        }

        assertThat(pieces.endSeconds()).isCloseTo(whole.endSeconds(), within(0.05));
        assertThat(whole.endSeconds()).isCloseTo(2.3, within(0.05));
    }

    @Test
    void aSteadyRoomNoiseIsNotSpeechButSpeechOverItIs() {
        SpeechEnd end = new SpeechEnd();
        end.add(join(sound(1, 0.05, true), sound(1, 0.5, false), sound(1, 0.05, true)));

        assertThat(end.endSeconds()).isCloseTo(2.0, within(0.05));
    }

    @Test
    void someoneWhoStartsTalkingInTheFirstFrameIsStillHeard() {
        SpeechEnd end = new SpeechEnd();
        end.add(join(sound(2, 0.2, false), sound(1, 0.003, true)));   // no silence before the speech to learn the room from

        assertThat(end.endSeconds()).isCloseTo(2.0, within(0.05));
    }

    @Test
    void aMicrophoneHissBelowTheFloorIsNotSpeech() {
        SpeechEnd end = new SpeechEnd();
        end.add(sound(3, 0.01, true));

        assertThat(end.endSeconds()).isEqualTo(-1);
    }
}
