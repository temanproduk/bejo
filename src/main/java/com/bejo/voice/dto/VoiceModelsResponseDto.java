package com.bejo.voice.dto;

import java.util.List;

/** The model files of one set (the voice converter) with signed download links. */
public record VoiceModelsResponseDto(String revision, List<VoiceModelFileDto> files) {}
