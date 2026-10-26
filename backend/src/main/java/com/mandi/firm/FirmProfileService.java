package com.mandi.firm;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mandi.audit.AuditService;
import com.mandi.identity.MemberRole;
import com.mandi.shared.NotFoundException;
import com.mandi.shared.PermissionDeniedException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The print profile of the ACTIVE firm. Updated with explicit SQL on exactly the five profile columns, because the runtime
 * database role has column-level UPDATE on those only (never on code, name or active). Owner only. The statements run through
 * the JPA EntityManager so that the change, its read-back and its audit event share one transaction.
 */
@Service
public class FirmProfileService {

    @PersistenceContext
    private EntityManager em;

    private final CurrentFirm firm;
    private final RequestFirmContext context;
    private final AuditService audit;

    public FirmProfileService(CurrentFirm firm, RequestFirmContext context, AuditService audit) {
        this.firm = firm;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public FirmProfile get() {
        return read(firm.id());
    }

    /** Same firm as the caller; used when a bill is read. */
    @Transactional(readOnly = true)
    public FirmProfile read(long firmId) {
        var rows = em.createNativeQuery("select name, name_hi, address, address_hi, phone, licence_no, shop_no from firm where id = :id")
                .setParameter("id", firmId).getResultList();
        if (rows.isEmpty()) throw new NotFoundException();
        Object[] r = (Object[]) rows.get(0);
        return new FirmProfile((String) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4], (String) r[5], (String) r[6]);
    }

    public record Update(@jakarta.validation.constraints.Size(max = 300, message = "{validation.text.size}") String address,
                         @jakarta.validation.constraints.Size(max = 300, message = "{validation.text.size}") String addressHi,
                         @jakarta.validation.constraints.Size(max = 30, message = "{validation.text.size}") String phone,
                         @jakarta.validation.constraints.Size(max = 60, message = "{validation.text.size}") String licenceNo,
                         @jakarta.validation.constraints.Size(max = 30, message = "{validation.text.size}") String shopNo) {
    }

    @Transactional
    public FirmProfile update(Update u) {
        if (context.current().role() != MemberRole.OWNER) throw new PermissionDeniedException();
        long firmId = firm.id();
        em.createNativeQuery("update firm set address = :a, address_hi = :ah, phone = :p, licence_no = :l, shop_no = :s where id = :id")
                .setParameter("a", blank(u.address())).setParameter("ah", blank(u.addressHi())).setParameter("p", blank(u.phone()))
                .setParameter("l", blank(u.licenceNo())).setParameter("s", blank(u.shopNo())).setParameter("id", firmId).executeUpdate();
        audit.record("FIRM", firmId, "PROFILE_UPDATED", "print profile edited (address, phone, licence and shop numbers)");
        return read(firmId);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
