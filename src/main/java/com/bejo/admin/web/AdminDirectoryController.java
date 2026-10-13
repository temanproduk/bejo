package com.bejo.admin.web;

import com.bejo.admin.dto.AdminTransactionRowDto;
import com.bejo.admin.dto.AdminUserRowDto;
import com.bejo.admin.service.AdminDirectoryService;
import com.bejo.shared.web.ApiResponseDto;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * The dashboard's lists: every app user and every transaction, across all businesses. Read-only.
 * Guarded by {@link AdminAuthFilter}.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminDirectoryController {

    private final AdminDirectoryService directory;

    public AdminDirectoryController(AdminDirectoryService directory) {
        this.directory = directory;
    }

    @GetMapping("/users")
    public ResponseEntity<ApiResponseDto<List<AdminUserRowDto>>> users(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(ApiResponseDto.page(directory.users(q, role, status, page, size)));
    }

    @GetMapping("/transactions")
    public ResponseEntity<ApiResponseDto<List<AdminTransactionRowDto>>> transactions(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateEnd,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(ApiResponseDto.page(
                directory.transactions(q, dateStart, dateEnd, type, status, page, size)));
    }
}
