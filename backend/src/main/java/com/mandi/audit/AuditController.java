package com.mandi.audit;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mandi.audit.AuditService.AuditEventResponse;

/** Read-only. History can be listed but never edited or deleted through the API. */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    /** Example: /api/v1/audit?entityType=AADAT_BILL&amp;entityId=12 (newest first, at most 200). */
    @GetMapping
    public List<AuditEventResponse> history(@RequestParam String entityType, @RequestParam Long entityId) {
        return service.history(entityType, entityId);
    }
}
