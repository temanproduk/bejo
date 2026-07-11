package com.bejo.controller;

import com.bejo.dto.request.UpdateProfilePictureRequestDto;
import com.bejo.dto.response.ApiResponseDto;
import com.bejo.dto.response.ProfilePictureResponseDto;
import com.bejo.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Business-level resources. The picture is the single image shared by all of the
 * business's users — GET/PUT/DELETE {@code /api/business/picture}.
 */
@RestController
@RequestMapping("/api/business")
public class BusinessController {

    private final UserService userService;

    public BusinessController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/picture")
    public ResponseEntity<ApiResponseDto<ProfilePictureResponseDto>> getPicture(Authentication authentication) {
        ProfilePictureResponseDto data = userService.getProfilePicture(authentication.getName());
        return ResponseEntity.ok(ApiResponseDto.success(data));
    }

    @PutMapping("/picture")
    public ResponseEntity<ApiResponseDto<ProfilePictureResponseDto>> setPicture(
            @Valid @RequestBody UpdateProfilePictureRequestDto request,
            Authentication authentication) {
        ProfilePictureResponseDto data = userService.updateProfilePicture(authentication.getName(), request.profilePicture());
        return ResponseEntity.ok(ApiResponseDto.success("Foto bisnis berhasil disimpan", data));
    }

    @DeleteMapping("/picture")
    public ResponseEntity<ApiResponseDto<Void>> deletePicture(Authentication authentication) {
        userService.deleteProfilePicture(authentication.getName());
        return ResponseEntity.ok(ApiResponseDto.success("Foto bisnis berhasil dihapus", null));
    }
}
