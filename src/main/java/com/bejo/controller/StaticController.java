package com.bejo.controller;

import com.bejo.dto.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Open test endpoint: no X-API-TOKEN or JWT required (exempted in
 * ApiTokenFilter and SecurityConfig). Accepts GET and POST with any
 * parameters/body, logs the full request, and echoes it back.
 */
@RestController
@RequestMapping("/api/static")
public class StaticController {

    private static final Logger log = LoggerFactory.getLogger(StaticController.class);

    @RequestMapping(method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<ApiResponse<Map<String, Object>>> handle(
            @RequestParam Map<String, String> params,
            @RequestBody(required = false) String body,
            HttpServletRequest request) {

        Map<String, String> headers = new LinkedHashMap<>();
        Collections.list(request.getHeaderNames())
                .forEach(name -> headers.put(name, request.getHeader(name)));

        log.info("[STATIC] method={} path={} params={} headers={} body={}",
                request.getMethod(), request.getRequestURI(), params, headers, body);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("method", request.getMethod());
        data.put("path", request.getRequestURI());
        if (!params.isEmpty()) data.put("params", params);
        if (body != null && !body.isBlank()) data.put("body", body);

        return ResponseEntity.ok(ApiResponse.success("Static endpoint reached", data));
    }
}
