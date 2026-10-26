package com.mandi.billing.aadat;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mandi.billing.aadat.AadatDtos.AppConfig;
import com.mandi.billing.aadat.AadatDtos.BillRequest;
import com.mandi.billing.aadat.AadatDtos.BillResponse;
import com.mandi.billing.aadat.AadatDtos.BillSummary;
import com.mandi.billing.aadat.calc.AadatCalculationService;
import com.mandi.billing.aadat.calc.CalculationResult;
import com.mandi.shared.PageResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
public class AadatBillController {

    private final AadatBillService service;
    private final AadatCalculationService calculation;
    private final BillNumberService numbers;
    private final com.mandi.firm.RequestFirmContext context;

    public AadatBillController(AadatBillService service, AadatCalculationService calculation, BillNumberService numbers,
                               com.mandi.firm.RequestFirmContext context) {
        this.service = service;
        this.calculation = calculation;
        this.numbers = numbers;
        this.context = context;
    }

    @GetMapping("/config")
    public AppConfig config() {
        return new AppConfig(calculation.demoAvailable());
    }

    @PostMapping("/aadat/bills/preview")
    public CalculationResult preview(@Valid @RequestBody BillRequest req) {
        return service.preview(req);
    }

    /**
     * Saves a bill. Send an {@code Idempotency-Key} (the web application does, one per form) so that a retry after a lost
     * response returns the same bill instead of creating a second one: 201 for a new bill, 200 with
     * {@code Idempotent-Replay: true} for the replay, 409 when the key was used with different content.
     */
    @PostMapping("/aadat/bills")
    public org.springframework.http.ResponseEntity<BillResponse> create(@Valid @RequestBody BillRequest req,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        AadatBillService.CreateResult result;
        try {
            result = service.create(req, idempotencyKey);
        } catch (AadatBillService.IdempotentRace race) {
            result = service.replay(req, idempotencyKey);
        }
        org.springframework.http.ResponseEntity.BodyBuilder b = org.springframework.http.ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.CREATED);
        if (result.replay()) b.header("Idempotent-Replay", "true");
        return b.body(result.bill());
    }

    @GetMapping("/aadat/bills/{id}")
    public BillResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping("/aadat/bills")
    public PageResponse<BillSummary> search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long farmerId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer billNo,
            @RequestParam(required = false) Integer financialYear,
            @RequestParam(defaultValue = "false") boolean includeDemo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.search(from, to, farmerId, q, billNo, financialYear, includeDemo, page, size);
    }

    /** The number the next saved bill is expected to get. Only the number on the SAVED bill is official. */
    @GetMapping("/aadat/bills/next-number")
    public AadatDtos.NextNumber nextNumber() {
        return new AadatDtos.NextNumber(numbers.expectedNext(), numbers.startLocked());
    }

    /**
     * Owner only: sets the number the firm's first bill will get (for example to continue a paper series). Refused with 409
     * as soon as the firm has a numbered bill. The firm is the session firm, never a parameter.
     */
    @org.springframework.web.bind.annotation.PutMapping("/firm/bill-numbering")
    public AadatDtos.NextNumber configureStart(@Valid @RequestBody AadatDtos.NumberingStart body) {
        if (context.current().role() != com.mandi.identity.MemberRole.OWNER) throw new com.mandi.shared.PermissionDeniedException();
        numbers.configureStart(body.nextNumber());
        return new AadatDtos.NextNumber(numbers.expectedNext(), numbers.startLocked());
    }
}
