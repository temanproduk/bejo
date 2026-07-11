package com.bejo.controller;

import com.bejo.dto.request.AddMemberRequestDto;
import com.bejo.dto.request.UpdateMemberRequestDto;
import com.bejo.dto.response.ApiResponseDto;
import com.bejo.dto.response.MemberResponseDto;
import com.bejo.service.MemberService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Manage the users of a business. Any member may list; only the super admin may add,
 * update, or delete users (enforced in {@link MemberService}).
 */
@RestController
@RequestMapping("/api/users/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping
    public ResponseEntity<ApiResponseDto<List<MemberResponseDto>>> list(Authentication authentication) {
        List<MemberResponseDto> data = memberService.list(authentication.getName());
        return ResponseEntity.ok(ApiResponseDto.list(data));
    }

    @PostMapping
    public ResponseEntity<ApiResponseDto<MemberResponseDto>> add(
            @Valid @RequestBody AddMemberRequestDto request,
            Authentication authentication) {
        MemberResponseDto data = memberService.add(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseDto.success("Pengguna berhasil ditambahkan", data));
    }

    @PatchMapping("/{memberId}")
    public ResponseEntity<ApiResponseDto<MemberResponseDto>> update(
            @PathVariable Long memberId,
            @Valid @RequestBody UpdateMemberRequestDto request,
            Authentication authentication) {
        MemberResponseDto data = memberService.update(authentication.getName(), memberId, request);
        return ResponseEntity.ok(ApiResponseDto.success("Pengguna berhasil diperbarui", data));
    }

    @DeleteMapping("/{memberId}")
    public ResponseEntity<ApiResponseDto<Void>> delete(
            @PathVariable Long memberId,
            Authentication authentication) {
        memberService.delete(authentication.getName(), memberId);
        return ResponseEntity.ok(ApiResponseDto.success("Pengguna berhasil dihapus", null));
    }
}
