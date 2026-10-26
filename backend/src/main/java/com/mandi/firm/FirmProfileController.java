package com.mandi.firm;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/** Any member of the active firm may read the print profile; only the owner may change it. The firm is never a parameter. */
@RestController
@RequestMapping("/api/v1/firm/profile")
public class FirmProfileController {

    private final FirmProfileService service;

    public FirmProfileController(FirmProfileService service) {
        this.service = service;
    }

    @GetMapping
    public FirmProfile get() {
        return service.get();
    }

    @PutMapping
    public FirmProfile update(@Valid @RequestBody FirmProfileService.Update body) {
        return service.update(body);
    }
}
