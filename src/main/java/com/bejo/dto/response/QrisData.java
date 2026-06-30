package com.bejo.dto.response;

public record QrisData(
        String type,
        String qrisImage,
        String businessName,
        String businessAddress,
        String nmid
) {}
