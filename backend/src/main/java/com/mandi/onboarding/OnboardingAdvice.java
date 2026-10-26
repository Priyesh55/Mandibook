package com.mandi.onboarding;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.mandi.identity.EmailNormalizer;
import com.mandi.identity.InvalidPasswordException;

/** Error shape of the onboarding endpoints: {@code {"error":CODE}} (plus a {@code reason} for an unacceptable password). */
@RestControllerAdvice(assignableTypes = {OnboardingController.class, FirmTeamController.class})
class OnboardingAdvice {

    @ExceptionHandler(OnboardingException.class)
    ResponseEntity<Map<String, Object>> refused(OnboardingException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.code());
        if (e.reason() != null) body.put("reason", e.reason());
        ResponseEntity.BodyBuilder b = ResponseEntity.status(e.status());
        if (e.status() == 429) b.header("Retry-After", "60");
        return b.body(body);
    }

    @ExceptionHandler(InvalidPasswordException.class)
    ResponseEntity<Map<String, Object>> badPassword(InvalidPasswordException e) {
        return ResponseEntity.status(422).body(Map.of("error", "INVALID_PASSWORD", "reason", e.reason().name()));
    }

    @ExceptionHandler(EmailNormalizer.InvalidEmailException.class)
    ResponseEntity<Map<String, Object>> badEmail(EmailNormalizer.InvalidEmailException e) {
        return ResponseEntity.status(400).body(Map.of("error", "INVALID_EMAIL"));
    }
}
