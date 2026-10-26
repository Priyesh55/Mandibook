package com.mandi.masters;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mandi.audit.AuditService;
import com.mandi.firm.CurrentFirm;
import com.mandi.masters.MasterDtos.MasterRequest;
import com.mandi.masters.MasterDtos.MasterResponse;
import com.mandi.shared.Like;
import com.mandi.shared.NotFoundException;
import com.mandi.shared.PageResponse;

import jakarta.persistence.criteria.Predicate;

@Service
public class MasterService {

    private final Map<MasterType, MasterRepository<? extends MasterEntity>> repos = new EnumMap<>(MasterType.class);

    /** Stable order for every list and page: by name, ties broken by id so pages never overlap or skip rows. */
    private static final Sort ORDER = Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"));
    static final int MAX_PAGE_SIZE = 200;

    private final AuditService audit;
    private final CurrentFirm firm;
    private final int maxListSize;
    private final VillageRepository villageRepo;

    public MasterService(FarmerRepository farmers, BuyerRepository buyers, VillageRepository villages,
                         ItemRepository items, ExpenseHeadRepository expenseHeads, AuditService audit,
                         CurrentFirm firm, @Value("${mandi.masters.max-list-size:5000}") int maxListSize) {
        this.maxListSize = maxListSize;
        this.villageRepo = villages;
        this.audit = audit;
        this.firm = firm;
        repos.put(MasterType.FARMERS, farmers);
        repos.put(MasterType.BUYERS, buyers);
        repos.put(MasterType.VILLAGES, villages);
        repos.put(MasterType.ITEMS, items);
        repos.put(MasterType.EXPENSE_HEADS, expenseHeads);
    }

    @SuppressWarnings("unchecked")
    private MasterRepository<MasterEntity> repo(MasterType type) {
        return (MasterRepository<MasterEntity>) repos.get(type);
    }

    /** Rows of a list plus whether the safety cap cut it short. */
    public record ListResult(List<MasterResponse> rows, boolean truncated) {
    }

    /**
     * The whole list for pick-lists (existing API, same JSON array). It is capped at
     * {@code mandi.masters.max-list-size} rows (default 5000) so one request can never return an unbounded table;
     * the caller is told when the cap applied. Use {@link #listPage} for browsing.
     */
    @Transactional(readOnly = true)
    public ListResult list(MasterType type, String q, boolean activeOnly, Long villageId) {
        Page<MasterEntity> page = repo(type).findAll(spec(type, q, activeOnly, villageId), PageRequest.of(0, maxListSize, ORDER));
        return new ListResult(responses(page.getContent()), page.hasNext());
    }

    /** One page, 0-based, size 1..200, stable order. */
    @Transactional(readOnly = true)
    public PageResponse<MasterResponse> listPage(MasterType type, String q, boolean activeOnly, Long villageId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<MasterEntity> result = repo(type).findAll(spec(type, q, activeOnly, villageId), PageRequest.of(Math.max(page, 0), safeSize, ORDER));
        return new PageResponse<>(responses(result.getContent()),
                result.getTotalElements(), result.getNumber(), result.getSize());
    }

    /** Rows as responses; the village names of a page of farmers come from one query, never one per row. */
    private List<MasterResponse> responses(List<MasterEntity> rows) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (MasterEntity e : rows) if (e instanceof Farmer f && f.getVillageId() != null) ids.add(f.getVillageId());
        Map<Long, String> names = new java.util.HashMap<>();
        if (!ids.isEmpty()) villageRepo.findByFirmIdAndIdIn(firm.id(), ids).forEach((v) -> names.put(v.getId(), v.getName()));
        return rows.stream().map((e) -> MasterResponse.of(e, e instanceof Farmer f ? names.get(f.getVillageId()) : null)).toList();
    }

    private MasterResponse respond(MasterEntity e) {
        return responses(List.of(e)).get(0);
    }

    private Specification<MasterEntity> spec(MasterType type, String q, boolean activeOnly, Long villageId) {
        String like = q == null || q.isBlank() ? null : Like.contains(q);
        long firmId = firm.id();
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("firmId"), firmId));
            if (activeOnly) {
                ps.add(cb.isTrue(root.get("active")));
            }
            if (villageId != null && type == MasterType.FARMERS) {
                ps.add(cb.equal(root.get("villageId"), villageId));
            }
            if (like != null) {
                ps.add(cb.or(cb.like(cb.lower(root.<String>get("name")), like, Like.ESCAPE),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("nameHi"), "")), like, Like.ESCAPE)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    @Transactional
    public MasterResponse create(MasterType type, MasterRequest req) {
        MasterEntity e = type.newEntity();
        e.setFirmId(firm.id());
        apply(type, e, req);
        MasterEntity saved = repo(type).save(e);
        audit.record(type.auditName(), saved.getId(), "CREATED", describe(saved));
        return respond(saved);
    }

    @Transactional
    public MasterResponse update(MasterType type, Long id, MasterRequest req) {
        MasterEntity e = repo(type).findByIdAndFirmId(id, firm.id()).orElseThrow(NotFoundException::new);
        String before = describe(e);
        apply(type, e, req);
        String after = describe(e);
        if (!before.equals(after)) {
            audit.record(type.auditName(), e.getId(), "UPDATED", "from " + before + " to " + after);
        }
        return respond(e);
    }

    @Transactional
    public MasterResponse setActive(MasterType type, Long id, boolean active) {
        MasterEntity e = repo(type).findByIdAndFirmId(id, firm.id()).orElseThrow(NotFoundException::new);
        if (e.isActive() != active) {
            e.setActive(active);
            audit.record(type.auditName(), e.getId(), active ? "ACTIVATED" : "DEACTIVATED", describe(e));
        }
        return respond(e);
    }

    private static String describe(MasterEntity e) {
        String base = "name='" + e.getName() + "'; nameHi='" + (e.getNameHi() == null ? "" : e.getNameHi()) + "'";
        if (e instanceof ExpenseHead h && h.hasRate()) base += "; rate=" + h.getRate().stripTrailingZeros().toPlainString() + " " + h.getRateBasis();
        return e instanceof Farmer f && f.getVillageId() != null ? base + "; villageId=" + f.getVillageId() : base;
    }

    private void apply(MasterType type, MasterEntity e, MasterRequest req) {
        e.setName(req.name().trim());
        e.setNameHi(req.nameHi() == null || req.nameHi().isBlank() ? null : req.nameHi().trim());
        if (type == MasterType.FARMERS) {
            // the village must be an ACTIVE village of this firm; an unknown, inactive or foreign one is refused
            if (req.villageId() != null) {
                villageRepo.findByIdAndFirmId(req.villageId(), firm.id()).filter(MasterEntity::isActive)
                        .orElseThrow(() -> new com.mandi.shared.BusinessRuleException("error.reference.notFound", "village #" + req.villageId()));
            }
            ((Farmer) e).setVillageId(req.villageId());
        } else if (req.villageId() != null) {
            throw new com.mandi.shared.BusinessRuleException("validation.villageOnlyForFarmers");
        }
        if (type == MasterType.EXPENSE_HEADS) {
            // a predefined rate needs its basis and the other way round; both empty means the amount is typed on each bill
            boolean basis = req.rateBasis() != null && !req.rateBasis().isBlank();
            if (basis != (req.rate() != null) || (basis && !ExpenseHead.PER_PIECE.equals(req.rateBasis()))) {
                throw new com.mandi.shared.BusinessRuleException("validation.expenseRate.invalid");
            }
            ((ExpenseHead) e).setRateBasis(basis ? req.rateBasis() : null);
            ((ExpenseHead) e).setRate(basis ? req.rate() : null);
        } else if (req.rateBasis() != null && !req.rateBasis().isBlank() || req.rate() != null) {
            throw new com.mandi.shared.BusinessRuleException("validation.rateOnlyForExpenseHeads");
        }
    }
}
