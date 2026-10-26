package com.mandi.billing.aadat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mandi.audit.AuditService;
import com.mandi.firm.CurrentFirm;
import com.mandi.billing.aadat.AadatDtos.BillRequest;
import com.mandi.billing.aadat.AadatDtos.BillResponse;
import com.mandi.billing.aadat.AadatDtos.BillSummary;
import com.mandi.billing.aadat.AadatDtos.ExpenseRequest;
import com.mandi.billing.aadat.AadatDtos.ExpenseResponse;
import com.mandi.billing.aadat.AadatDtos.LotRequest;
import com.mandi.billing.aadat.AadatDtos.LotResponse;
import com.mandi.billing.aadat.AadatDtos.PurchaserRequest;
import com.mandi.billing.aadat.AadatDtos.PurchaserResponse;
import com.mandi.firm.FirmProfileService;
import com.mandi.billing.aadat.calc.AadatCalculationService;
import com.mandi.billing.aadat.calc.CalculationInput;
import com.mandi.billing.aadat.calc.CalculationInput.ExpenseInput;
import com.mandi.billing.aadat.calc.CalculationInput.LotInput;
import com.mandi.billing.aadat.calc.CalculationMode;
import com.mandi.billing.aadat.calc.CalculationResult;
import com.mandi.masters.BuyerRepository;
import com.mandi.masters.ExpenseHeadRepository;
import com.mandi.masters.FarmerRepository;
import com.mandi.masters.ItemRepository;
import com.mandi.masters.MasterDtos.MasterRef;
import com.mandi.masters.MasterEntity;
import com.mandi.masters.MasterRepository;
import com.mandi.masters.VillageRepository;
import com.mandi.shared.BusinessRuleException;
import com.mandi.shared.Like;
import com.mandi.shared.NotFoundException;
import com.mandi.shared.PageResponse;

import jakarta.persistence.criteria.Predicate;

@Service
public class AadatBillService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AadatBillRepository bills;
    private final FarmerRepository farmers;
    private final VillageRepository villages;
    private final ItemRepository items;
    private final BuyerRepository buyers;
    private final ExpenseHeadRepository expenseHeads;
    private final AadatCalculationService calculation;
    private final AuditService audit;
    private final CurrentFirm firm;
    private final BillNumberService numbers;
    private final FirmProfileService firmProfiles;

    public AadatBillService(AadatBillRepository bills, FarmerRepository farmers, VillageRepository villages,
                            ItemRepository items, BuyerRepository buyers, ExpenseHeadRepository expenseHeads,
                            AadatCalculationService calculation, AuditService audit, CurrentFirm firm,
                            BillNumberService numbers, FirmProfileService firmProfiles) {
        this.numbers = numbers;
        this.firmProfiles = firmProfiles;
        this.audit = audit;
        this.firm = firm;
        this.bills = bills;
        this.farmers = farmers;
        this.villages = villages;
        this.items = items;
        this.buyers = buyers;
        this.expenseHeads = expenseHeads;
        this.calculation = calculation;
    }

    /** Preview never persists anything. Amounts come only from AadatCalculationService. */
    public CalculationResult preview(BillRequest req) {
        return calculation.calculate(req.calculationMode(), toInput(req));
    }

    /** Result of a save: the bill and whether it is the replay of an earlier save with the same Idempotency-Key. */
    public record CreateResult(BillResponse bill, boolean replay) {
    }

    /** Thrown when two saves with the same key race and this one lost: the caller replays the winner. */
    public static class IdempotentRace extends RuntimeException {
        public IdempotentRace() {
            super("concurrent save with the same idempotency key");
        }
    }

    private static final java.util.regex.Pattern KEY = java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private static byte[] fingerprint(BillRequest req) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(req.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public BillResponse create(BillRequest req) {
        return create(req, null).bill();
    }

    /**
     * Saves a bill once. With an Idempotency-Key, a second request with the same key and the same content returns the SAME
     * bill (nothing is written); the same key with different content is refused (409). The firm is always the verified
     * session firm, so a key can never reach another firm's bill.
     */
    @Transactional
    public CreateResult create(BillRequest req, String idempotencyKey) {
        byte[] hash = null;
        if (idempotencyKey != null) {
            if (!KEY.matcher(idempotencyKey).matches()) throw new BusinessRuleException("validation.idempotencyKey.invalid");
            hash = fingerprint(req);
            java.util.Optional<AadatBill> earlier = bills.findByFirmIdAndIdempotencyKey(firm.id(), idempotencyKey);
            if (earlier.isPresent()) return replayOf(earlier.get(), hash);
        }
        validate(req);
        // every new bill is calculated; only the opt-in demonstration mode may be asked for explicitly
        if (req.calculationMode() == CalculationMode.NOT_CALCULATED) throw new BusinessRuleException("validation.calculationMode.invalid");
        CalculationMode mode = req.calculationMode() == null ? CalculationMode.CALCULATED : req.calculationMode();
        CalculationResult result = calculation.calculate(mode, toInput(req)); // fails fast if demo is not enabled

        AadatBill bill = new AadatBill();
        bill.setFirmId(firm.id());
        bill.setBillDate(req.billDate());
        com.mandi.masters.Farmer farmerEntity = ref(farmers, req.farmerId(), "farmer");
        bill.setFarmer(farmerEntity);
        bill.setVillage(req.villageId() == null ? null : ref(villages, req.villageId(), "village"));
        // village first: a farmer who belongs to a village can only be billed under that village (the server checks it again)
        if (req.villageId() != null && farmerEntity.getVillageId() != null && !farmerEntity.getVillageId().equals(req.villageId())) {
            throw new BusinessRuleException("validation.farmer.village");
        }
        bill.setVehicleNo(text(req.vehicleNo()));
        bill.setTransport(text(req.transport()));
        bill.setBhadaPaid(req.bhadaPaid());
        bill.setBhadaAmount(req.bhadaAmount());
        bill.setBillType(req.billType());
        bill.setPanjiyanNo(text(req.panjiyanNo()));
        bill.setBookNo(text(req.bookNo()));
        bill.setReceiptNo(text(req.receiptNo()));
        bill.setEntryNo(text(req.entryNo()));
        bill.setWsNo(text(req.wsNo()));
        bill.setCalculationStatus(result.mode());
        bill.setRuleSetId(result.ruleSetId());
        if (idempotencyKey != null) bill.setIdempotency(idempotencyKey, hash);

        long totalPieces = req.lots().stream().mapToLong(l -> l.pieces() == null ? 0 : l.pieces()).sum();
        int lineNo = 1;
        for (LotRequest l : req.lots()) {
            AadatLot lot = new AadatLot();
            lot.setLineNo(lineNo++);
            lot.setItem(ref(items, l.itemId(), "item"));
            lot.setPieces(l.pieces());
            lot.setWeight(l.weight());
            lot.setRate(l.rate());
            lot.setBuyer(l.buyerId() == null ? null : ref(buyers, l.buyerId(), "buyer"));
            lot.setMarka(text(l.marka()));
            lot.setLotValue(BillCalculator.value(l.weight(), l.rate()));
            bill.addLot(lot);
            int row = 1;
            for (PurchaserRequest pr : l.purchasers() == null ? List.<PurchaserRequest>of() : l.purchasers()) {
                AadatLotBuyer p = new AadatLotBuyer();
                p.setSeq(row++);
                p.setBuyer(ref(buyers, pr.buyerId(), "buyer"));
                p.setHaste(text(pr.haste()));
                p.setPieces(pr.pieces());
                p.setWeight(pr.weight());
                p.setRate(pr.rate());
                BigDecimal value = BillCalculator.value(pr.weight(), pr.rate());
                p.setValue(value);
                p.setCommissionApplies(true);
                p.setCommissionPercent(BigDecimal.valueOf(BillCalculator.COMMISSION_PERCENT));
                p.setCommissionAmount(BillCalculator.commission(value));
                lot.addPurchaser(p);
            }
        }
        int seq = 1;
        for (ExpenseRequest e : expensesOf(req)) {
            AadatExpense exp = new AadatExpense();
            exp.setSeq(seq++);
            com.mandi.masters.ExpenseHead head = ref(expenseHeads, e.expenseHeadId(), "expense head");
            exp.setExpenseHead(head);
            if (head.hasRate()) {
                // a predefined rate: the server multiplies the pieces (bags) of the bill by it; a typed amount is ignored
                if (totalPieces <= 0) throw new BusinessRuleException("validation.expense.piecesRequired", head.getName());
                exp.setQuantity(BigDecimal.valueOf(totalPieces));
                exp.setRate(head.getRate());
                exp.setAmount(BillCalculator.perPieceExpense(totalPieces, head.getRate()));
            } else {
                if (e.amount() == null) throw new BusinessRuleException("validation.amount.required");
                exp.setAmount(e.amount());
            }
            bill.addExpense(exp);
        }
        // The number is taken LAST, so the counter row is locked for the shortest time; it is given back with the whole
        // transaction if anything below fails (including losing an idempotency race).
        bill.setBillNo(numbers.allocate());
        AadatBill saved;
        try {
            saved = idempotencyKey == null ? bills.save(bill) : bills.saveAndFlush(bill);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // only losing the race on the idempotency key means "someone saved this exact request"; any other violation is a real error
            Throwable cause = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(race);
            if (idempotencyKey != null && cause.getMessage() != null && cause.getMessage().contains("aadat_bill_idempotency_uk")) throw new IdempotentRace();
            throw race;
        }
        int purchaserRows = saved.getLots().stream().mapToInt(l -> l.getPurchasers().size()).sum();
        audit.record("AADAT_BILL", saved.getId(), "CREATED", String.format(
                "billNo=%d; fy=%s; farmer='%s'; village=%s; vehicle=%s; lots=%d; purchaserRows=%d; expenses=%d; calculationStatus=%s; ruleSet=%s",
                saved.getBillNo(), FinancialYear.label(FinancialYear.startYearOf(saved.getBillDate())), saved.getFarmer().getName(), saved.getVillage() == null ? "-" : "'" + saved.getVillage().getName() + "'",
                saved.getVehicleNo() == null ? "-" : "'" + saved.getVehicleNo() + "'", saved.getLots().size(), purchaserRows,
                saved.getExpenses().size(), saved.getCalculationStatus(), saved.getRuleSetId()));
        return new CreateResult(toResponse(saved), false);
    }

    /** Called after losing a race on the key: reads what the winner stored. */
    @Transactional(readOnly = true)
    public CreateResult replay(BillRequest req, String idempotencyKey) {
        AadatBill earlier = bills.findByFirmIdAndIdempotencyKey(firm.id(), idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("idempotent bill vanished"));
        return replayOf(earlier, fingerprint(req));
    }

    private CreateResult replayOf(AadatBill earlier, byte[] hash) {
        if (!java.util.Arrays.equals(earlier.getRequestHash(), hash)) throw new com.mandi.shared.IdempotencyConflictException();
        return new CreateResult(get(earlier.getId()), true);
    }

    @Transactional(readOnly = true)
    public BillResponse get(Long id) {
        long firmId = firm.id();
        AadatBill bill = bills.findWithLotsByIdAndFirmId(id, firmId).orElseThrow(NotFoundException::new);
        bills.findWithExpensesByIdAndFirmId(id, firmId); // fills the same entity's expenses: 2 statements in total
        return toResponse(bill);
    }

    @Transactional(readOnly = true)
    public PageResponse<BillSummary> search(LocalDate from, LocalDate to, Long farmerId, String q, Integer billNo,
                                          Integer financialYear, boolean includeDemo, int page, int size) {
        String like = q == null || q.isBlank() ? null : Like.contains(q);
        Long idMatch = parseLong(q);
        long firmId = firm.id();
        Specification<AadatBill> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("firmId"), firmId));
            if (from != null) {
                ps.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("billDate"), from));
            }
            if (to != null) {
                ps.add(cb.lessThanOrEqualTo(root.<LocalDate>get("billDate"), to));
            }
            if (!includeDemo) {
                // Demonstration (unverified) bills are hidden from listings unless explicitly requested.
                ps.add(cb.notEqual(root.get("calculationStatus"), CalculationMode.DEMO_UNVERIFIED));
            }
            if (farmerId != null) {
                ps.add(cb.equal(root.get("farmer").get("id"), farmerId));
            }
            if (billNo != null) {
                ps.add(cb.equal(root.get("billNo"), billNo));
            }
            if (financialYear != null) {
                ps.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("billDate"), FinancialYear.start(financialYear)));
                ps.add(cb.lessThanOrEqualTo(root.<LocalDate>get("billDate"), FinancialYear.end(financialYear)));
            }
            if (like != null) {
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(cb.coalesce(root.<String>get("vehicleNo"), "")), like, Like.ESCAPE));
                any.add(cb.like(cb.lower(root.get("farmer").<String>get("name")), like, Like.ESCAPE));
                any.add(cb.like(cb.lower(cb.coalesce(root.get("farmer").<String>get("nameHi"), "")), like, Like.ESCAPE));
                if (idMatch != null) {
                    any.add(cb.equal(root.get("id"), idMatch));
                    if (idMatch <= Integer.MAX_VALUE) any.add(cb.equal(root.get("billNo"), idMatch.intValue()));
                }
                ps.add(cb.or(any.toArray(new Predicate[0])));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<AadatBill> result = bills.findAll(spec, PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by(Sort.Order.desc("billDate"), Sort.Order.desc("id"))));
        // Lot counts come from one grouped query; the lots themselves are never loaded for a list.
        Map<Long, Integer> lotCounts = new HashMap<>();
        Map<Long, BigDecimal> gross = new HashMap<>();
        Map<Long, BigDecimal> expenseSums = new HashMap<>();
        if (!result.isEmpty()) {
            List<Long> ids = result.getContent().stream().map(AadatBill::getId).toList();
            for (Object[] row : bills.countLotsByBill(ids, firmId)) lotCounts.put((Long) row[0], ((Number) row[1]).intValue());
            for (Object[] row : bills.sumLotValuesByBill(ids, firmId)) gross.put((Long) row[0], row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1]);
            for (Object[] row : bills.sumExpensesByBill(ids, firmId)) expenseSums.put((Long) row[0], row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1]);
        }
        List<BillSummary> rows = result.getContent().stream().map(b -> new BillSummary(b.getId(), b.getBillNo(), b.getBillDate(),
                FinancialYear.startYearOf(b.getBillDate()), MasterRef.of(b.getFarmer()), MasterRef.of(b.getVillage()), b.getVehicleNo(),
                lotCounts.getOrDefault(b.getId(), 0), b.getCalculationStatus(), gross.getOrDefault(b.getId(), BigDecimal.ZERO),
                expenseSums.getOrDefault(b.getId(), BigDecimal.ZERO),
                BillTotals.netOf(gross.getOrDefault(b.getId(), BigDecimal.ZERO), expenseSums.getOrDefault(b.getId(), BigDecimal.ZERO),
                        b.getCalculationStatus() == CalculationMode.CALCULATED))).toList();
        return new PageResponse<>(rows, result.getTotalElements(), result.getNumber(), result.getSize());
    }

    // ---- helpers ----

    private static String text(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Rules the server enforces whatever the browser did. None of them is an accounting rule. */
    private void validate(BillRequest req) {
        LocalDate date = req.billDate();
        if (date.getYear() < 2000 || date.getYear() > 2100) throw new BusinessRuleException("validation.billDate.range");
        if (req.financialYear() != null) {
            int fy = req.financialYear();
            if (fy < 2000 || fy > 2100) throw new BusinessRuleException("validation.financialYear.range");
            if (!FinancialYear.contains(fy, date)) {
                throw new BusinessRuleException("validation.billDate.outsideFinancialYear", date.toString(), FinancialYear.label(fy),
                        FinancialYear.start(fy).toString(), FinancialYear.end(fy).toString());
            }
        }
        if (Boolean.TRUE.equals(req.bhadaPaid()) && req.bhadaAmount() == null) throw new BusinessRuleException("validation.bhada.amountRequired");
        if (!Boolean.TRUE.equals(req.bhadaPaid()) && req.bhadaAmount() != null) throw new BusinessRuleException("validation.bhada.amountNeedsPaid");
        java.util.Set<Long> heads = new java.util.HashSet<>();
        for (ExpenseRequest e : expensesOf(req)) {
            if (!heads.add(e.expenseHeadId())) throw new BusinessRuleException("validation.expense.duplicateHead");
        }
        for (LotRequest l : req.lots()) {
            if (l.weight() == null || l.rate() == null) throw new BusinessRuleException("validation.lot.weightRate.required");
            List<PurchaserRequest> rows = l.purchasers() == null ? List.of() : l.purchasers();
            if (!rows.isEmpty() && l.buyerId() != null) throw new BusinessRuleException("validation.lot.buyerOrPurchasers");
            for (PurchaserRequest p : rows) {
                if (p.weight() == null || p.rate() == null) throw new BusinessRuleException("validation.purchaser.weightRate.required");
            }
        }
    }

    private static Long parseLong(String s) {
        try {
            return s == null ? null : Long.valueOf(s.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static List<ExpenseRequest> expensesOf(BillRequest req) {
        return req.expenses() == null ? List.of() : req.expenses();
    }

    private static CalculationInput toInput(BillRequest req) {
        List<LotInput> lots = new ArrayList<>();
        int n = 1;
        for (LotRequest l : req.lots()) {
            lots.add(new LotInput(n++, l.pieces(), l.weight(), l.rate()));
        }
        List<ExpenseInput> exps = new ArrayList<>();
        n = 1;
        for (ExpenseRequest e : expensesOf(req)) {
            exps.add(new ExpenseInput(n++, e.amount()));
        }
        return new CalculationInput(lots, exps);
    }

    private <T extends MasterEntity> T ref(MasterRepository<T> repo, Long id, String label) {
        return repo.findByIdAndFirmId(id, firm.id()).filter(MasterEntity::isActive)
                .orElseThrow(() -> new BusinessRuleException("error.reference.notFound", label + " #" + id));
    }

    private BillResponse toResponse(AadatBill b) {
        List<LotInput> lotInputs = b.getLots().stream()
                .map(l -> new LotInput(l.getLineNo(), l.getPieces(), l.getWeight(), l.getRate())).toList();
        List<ExpenseInput> expInputs = b.getExpenses().stream()
                .map(e -> new ExpenseInput(e.getSeq(), e.getAmount())).toList();
        // Demonstration figures are recomputed on view, never read from storage; if the demo rule set is not
        // enabled on this server, nothing is calculated.
        CalculationMode saved = b.getCalculationStatus();
        CalculationResult calc = saved == CalculationMode.DEMO_UNVERIFIED && !calculation.demoAvailable()
                ? CalculationResult.notCalculated()
                : calculation.calculate(saved, new CalculationInput(lotInputs, expInputs));
        int fy = FinancialYear.startYearOf(b.getBillDate());
        return new BillResponse(b.getId(), b.getBillNo(), b.getBillDate(), fy, FinancialYear.label(fy),
                MasterRef.of(b.getFarmer()), MasterRef.of(b.getVillage()), b.getVehicleNo(), b.getTransport(), b.getBhadaPaid(),
                b.getBhadaAmount(), b.getBillType(), b.getPanjiyanNo(), b.getBookNo(), b.getReceiptNo(), b.getEntryNo(), b.getWsNo(),
                b.getCalculationStatus(), b.getRuleSetId(), b.getCreatedAt(),
                b.getLots().stream().map(l -> new LotResponse(l.getLineNo(), MasterRef.of(l.getItem()), l.getPieces(),
                        l.getWeight(), l.getRate(), MasterRef.of(l.getBuyer()), l.getMarka(), l.getLotValue(),
                        l.getPurchasers().stream().map(p -> new PurchaserResponse(p.getSeq(), MasterRef.of(p.getBuyer()), p.getHaste(),
                                p.getPieces(), p.getWeight(), p.getRate(), p.getValue(), p.isCommissionApplies(),
                                p.getCommissionPercent(), p.getCommissionAmount(), BillTotals.purchaseValueOf(p))).toList(),
                        BillTotals.allocationOf(l))).toList(),
                b.getExpenses().stream().map(e -> new ExpenseResponse(e.getSeq(), MasterRef.of(e.getExpenseHead()),
                        e.getAmount(), e.getQuantity(), e.getRate())).toList(),
                BillTotals.of(b.getLots(), b.getExpenses(), saved == CalculationMode.CALCULATED), firmProfiles.read(b.getFirmId()),
                calc);
    }
}
