package com.mandi.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;

import jakarta.servlet.http.HttpServletResponse;

/** The one error shape of the security layer: {@code {"error":"CODE"}}; codes are stable, text is the client's job (en/hi). */
final class JsonErrors {

    private JsonErrors() {
    }

    static void write(HttpServletResponse res, int status, String code) throws IOException {
        if (res.isCommitted()) return;
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.setHeader("Cache-Control", "no-store");
        res.getWriter().write("{\"error\":\"" + code + "\"}");
    }
}
