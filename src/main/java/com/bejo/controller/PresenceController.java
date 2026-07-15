package com.bejo.controller;

import com.bejo.dto.request.PresenceClockRequestDto;
import com.bejo.dto.request.PresencePingRequestDto;
import com.bejo.dto.request.PresenceRespondRequestDto;
import com.bejo.dto.response.ApiResponseDto;
import com.bejo.dto.response.PingResultResponseDto;
import com.bejo.dto.response.PresenceResponseDto;
import com.bejo.service.PresenceService;
import com.bejo.util.Pagination;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Employee daily presence. Members clock in/out (which pushes a notification to the super
 * admin); the super admin pings the whole team for a spot-check and reads the history.
 */
@RestController
@RequestMapping("/api/presences")
public class PresenceController {

    private final PresenceService presenceService;

    public PresenceController(PresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @PostMapping("/clock-in")
    public ResponseEntity<ApiResponseDto<PresenceResponseDto>> clockIn(
            @Valid @RequestBody PresenceClockRequestDto request,
            Authentication authentication) {
        PresenceResponseDto data = presenceService.clockIn(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseDto.success("Absen masuk berhasil dicatat", data));
    }

    @PostMapping("/clock-out")
    public ResponseEntity<ApiResponseDto<PresenceResponseDto>> clockOut(
            @Valid @RequestBody PresenceClockRequestDto request,
            Authentication authentication) {
        PresenceResponseDto data = presenceService.clockOut(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseDto.success("Absen keluar berhasil dicatat", data));
    }

    // Super admin only: records + pushes a PING entry to the whole team, or to the one member
    // named by userId. Only members are pinged — never the super admin's own devices, and never
    // a member with no registered device. The result says how many of the team were reached.
    @PostMapping("/pings")
    public ResponseEntity<ApiResponseDto<PingResultResponseDto>> ping(
            @Valid @RequestBody(required = false) PresencePingRequestDto request,
            Authentication authentication) {
        // No prose here on purpose: the response carries the counts (pinged / totalMembers)
        // and the app writes the sentence it wants to show.
        PingResultResponseDto data = presenceService.ping(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseDto.success(data));
    }

    // The member acknowledges their ping: stamps responseDatetime + where they are (a ping is
    // a spot-check, so the location is required), plus an optional note.
    @PostMapping("/{id}/respond")
    public ResponseEntity<ApiResponseDto<PresenceResponseDto>> respond(
            @PathVariable Long id,
            @Valid @RequestBody PresenceRespondRequestDto request,
            Authentication authentication) {
        PresenceResponseDto data = presenceService.respond(authentication.getName(), id, request);
        return ResponseEntity.ok(ApiResponseDto.success("Ping berhasil dijawab", data));
    }

    // Super admin sees the whole business (optionally one member via userId); a member only
    // ever sees their own. year (+ optional month) narrows it to that calendar period.
    @GetMapping
    public ResponseEntity<ApiResponseDto<List<PresenceResponseDto>>> list(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        Page<PresenceResponseDto> data = presenceService.list(
                authentication.getName(), userId, year, month, Pagination.of(page, size));
        return ResponseEntity.ok(ApiResponseDto.page(data));
    }
}
