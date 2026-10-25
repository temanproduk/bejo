package com.bejo.voice.service;

/**
 * Where, in the audio sent so far, the last speech ends — found with a plain loudness test, and used for one thing only: knowing
 * how much the model still has to hear. The model answers a phrase some time after it was said, and when audio arrives in a burst
 * (the connection took a moment to come up, or the network held it back) the model is seconds behind; "nothing has happened for a
 * moment" then does not mean "it has finished", but "it has heard up to where the last speech ended" does ({@link LiveSale}).
 *
 * <p>A frame counts as speech when it is clearly louder than the quietest frame seen so far (the room), and louder than a floor
 * that keeps a silent microphone from reading as speech. It is deliberately conservative: missing quiet speech only costs a
 * moment's patience, while mistaking noise for speech makes the session wait for the model to catch up with something it will
 * never report.
 */
final class SpeechEnd {

    private static final int BYTES_PER_SECOND = 16_000 * 2;
    private static final int FRAME_BYTES = BYTES_PER_SECOND / 50;   // 20 ms
    /** Of full scale. Speech from a phone microphone sits well above this; a quiet room well below. */
    private static final double MIN_LEVEL = 0.02;
    /** How much louder than the quietest frame so far a frame must be. */
    private static final double OVER_NOISE = 3.0;
    /**
     * The quietest frame is taken as the room, but never as louder than this: someone who starts talking in the first frame has
     * no silence to learn the room from, and speech must not become its own noise floor.
     */
    private static final double MAX_NOISE_FLOOR = 0.03;

    private long bytesSeen;
    private double quietest = Double.MAX_VALUE;
    private double speechEndSeconds = -1;

    /** Takes the next piece of the recording: 16 kHz mono 16-bit little-endian PCM, any length. */
    void add(byte[] pcm) {
        for (int at = 0; at + 2 <= pcm.length; at += FRAME_BYTES) {
            int end = Math.min(pcm.length, at + FRAME_BYTES) & ~1;
            double level = level(pcm, at, end);
            quietest = Math.min(quietest, level);
            if (level > Math.max(MIN_LEVEL, Math.min(quietest, MAX_NOISE_FLOOR) * OVER_NOISE)) {
                speechEndSeconds = (bytesSeen + end) / (double) BYTES_PER_SECOND;
            }
        }
        bytesSeen += pcm.length;
    }

    /** Seconds into the recording where the last speech ends, or -1 when none was heard. */
    double endSeconds() {
        return speechEndSeconds;
    }

    private static double level(byte[] pcm, int from, int to) {
        double sum = 0;
        int count = 0;
        for (int i = from; i + 1 < to; i += 2) {
            int sample = (pcm[i] & 0xFF) | (pcm[i + 1] << 8);   // little-endian, sign-extended
            sum += (double) sample * sample;
            count++;
        }
        return count == 0 ? 0 : Math.sqrt(sum / count) / 32768.0;
    }
}
