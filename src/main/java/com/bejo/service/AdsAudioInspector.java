package com.bejo.service;

/**
 * Reads the duration of an ad's audio so the ≤10s limit can be enforced <b>before</b> the file
 * is uploaded to storage. The production implementation ({@link JAudioTaggerAudioInspector})
 * parses the audio header only; the tests bind a stub.
 */
public interface AdsAudioInspector {

    /**
     * The audio's duration in whole seconds. {@code fileExtension} (e.g. {@code mp3}, {@code m4a},
     * {@code wav}) tells the parser the format. Throws {@link IllegalArgumentException} if the
     * bytes can't be read as audio of that format — the caller turns that into a 400.
     */
    int durationSeconds(byte[] audio, String fileExtension);
}
