package com.bejo.service;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;

/**
 * Reads audio duration with jaudiotagger, which parses only the header (no full decode). The
 * library works off a file, so the bytes are written to a short-lived temp file (named with the
 * right extension so the correct reader is chosen) and deleted immediately. A file it can't parse
 * — a corrupt upload or an unsupported container — surfaces as a 400, not a silent pass, so the
 * duration limit can't be dodged with an unreadable file.
 */
@Service
public class JAudioTaggerAudioInspector implements AdsAudioInspector {

    private static final Logger log = LoggerFactory.getLogger(JAudioTaggerAudioInspector.class);

    @Override
    public int durationSeconds(byte[] audio, String fileExtension) {
        File tmp = null;
        try {
            tmp = File.createTempFile("bejo-ad-", "." + fileExtension);
            Files.write(tmp.toPath(), audio);
            AudioFile file = AudioFileIO.read(tmp);
            return file.getAudioHeader().getTrackLength();
        } catch (Exception e) {
            log.warn("[ADS] Could not read audio duration (ext={}): {}", fileExtension, e.getMessage());
            throw new IllegalArgumentException("Durasi audio tidak dapat dibaca, pastikan file audio valid");
        } finally {
            if (tmp != null && !tmp.delete()) {
                tmp.deleteOnExit();
            }
        }
    }
}
