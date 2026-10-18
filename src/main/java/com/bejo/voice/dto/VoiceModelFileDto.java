package com.bejo.voice.dto;

/** One model file the app should download: where from, how big, and its SHA-256 for verification. */
public record VoiceModelFileDto(String name, long bytes, String sha256, String url) {}
