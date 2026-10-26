package com.mandi.masters;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mandi.masters.MasterDtos.ActiveRequest;
import com.mandi.masters.MasterDtos.MasterRequest;
import com.mandi.masters.MasterDtos.MasterResponse;
import com.mandi.shared.NotFoundException;
import com.mandi.shared.PageResponse;

import jakarta.validation.Valid;

/** Types: farmers, buyers, villages, items, expense-heads. */
@RestController
@RequestMapping("/api/v1/masters/{type}")
public class MasterController {

    private final MasterService service;

    public MasterController(MasterService service) {
        this.service = service;
    }

    private static MasterType type(String path) {
        return MasterType.fromPath(path).orElseThrow(NotFoundException::new);
    }

    /**
     * Whole list as a JSON array (used by pick-lists). Bounded by a server-side safety cap; when the cap cut the
     * list short the response carries {@code X-Result-Truncated: true}. Use {@code /page} to browse large lists.
     */
    @GetMapping
    public ResponseEntity<List<MasterResponse>> list(@PathVariable String type,
                                                     @RequestParam(required = false) String q,
                                                     @RequestParam(defaultValue = "false") boolean activeOnly,
                                                     @RequestParam(required = false) Long villageId) {
        MasterService.ListResult result = service.list(type(type), q, activeOnly, villageId);
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        if (result.truncated()) {
            ok.header("X-Result-Truncated", "true");
        }
        return ok.body(result.rows());
    }

    /** One page of the list, sorted by name then id. {@code size} 1..200 (default 50), {@code page} 0-based. */
    @GetMapping("/page")
    public PageResponse<MasterResponse> page(@PathVariable String type,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "false") boolean activeOnly,
                                             @RequestParam(required = false) Long villageId,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int size) {
        return service.listPage(type(type), q, activeOnly, villageId, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MasterResponse create(@PathVariable String type, @Valid @RequestBody MasterRequest req) {
        return service.create(type(type), req);
    }

    @PutMapping("/{id}")
    public MasterResponse update(@PathVariable String type, @PathVariable Long id,
                                 @Valid @RequestBody MasterRequest req) {
        return service.update(type(type), id, req);
    }

    @PatchMapping("/{id}/active")
    public MasterResponse setActive(@PathVariable String type, @PathVariable Long id,
                                    @RequestBody ActiveRequest req) {
        return service.setActive(type(type), id, req.active());
    }
}
