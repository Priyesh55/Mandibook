package com.mandi.billing.aadat;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mandi.audit.AuditService;
import com.mandi.firm.CurrentFirm;
import com.mandi.shared.BusinessRuleException;
import com.mandi.shared.ConflictException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Bill numbers: ONE continuous series per firm, across financial years (owner decision 2026-10-24).
 *
 * <p>The number is assigned when the bill is SAVED, inside the save transaction, from a per-firm counter row that is
 * locked ({@code FOR UPDATE}) until the transaction ends. Consequences, all deliberate:
 * <ul>
 *   <li>two concurrent saves receive different, consecutive numbers (the second waits for the first to commit);</li>
 *   <li>a save that fails or is retried after losing a race rolls its increment back, so no number is lost or reused;</li>
 *   <li>nothing is reserved while a bill is only being typed, so abandoned drafts leave no gaps;</li>
 *   <li>a committed bill's number is never changed: the runtime role cannot update bills.</li>
 * </ul>
 * Data integrity matters more here than a number sequence that can never show a gap.
 *
 * <p>The statements go through the JPA {@link EntityManager}, so they are guaranteed to run on the very connection and
 * transaction that saves the bill; the row lock is therefore held until that transaction commits or rolls back.
 */
@Service
public class BillNumberService {

    private static final int MAX_START = 999_999_999;

    @PersistenceContext
    private EntityManager em;

    private final CurrentFirm firm;
    private final AuditService audit;

    public BillNumberService(CurrentFirm firm, AuditService audit) {
        this.firm = firm;
        this.audit = audit;
    }

    /** Takes the next number for the firm. Must run inside the transaction that saves the bill. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int allocate() {
        long firmId = firm.id();
        ensureCounter(firmId);
        int no = lockedNext(firmId);
        em.createNativeQuery("update bill_number_counter set next_no = next_no + 1 where firm_id = :f").setParameter("f", firmId).executeUpdate();
        return no;
    }

    /** The number the next saved bill is expected to get; another operator may save first, so only the saved number is official. */
    @Transactional(readOnly = true)
    public int expectedNext() {
        var rows = em.createNativeQuery("select next_no from bill_number_counter where firm_id = :f").setParameter("f", firm.id()).getResultList();
        return rows.isEmpty() ? 1 : ((Number) rows.get(0)).intValue();
    }

    /** True once this firm has a numbered bill: the starting number can then no longer be changed. */
    @Transactional(readOnly = true)
    public boolean startLocked() {
        Object exists = em.createNativeQuery("select exists (select 1 from aadat_bill where firm_id = :f and bill_no is not null)")
                .setParameter("f", firm.id()).getSingleResult();
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Sets the number the firm's FIRST bill will get (for example to continue a paper series). Refused once any numbered bill
     * exists; the check runs while the counter row is locked, so it cannot race with a save.
     */
    @Transactional
    public int configureStart(int nextNo) {
        if (nextNo < 1 || nextNo > MAX_START) throw new BusinessRuleException("validation.billNumber.start");
        long firmId = firm.id();
        ensureCounter(firmId);
        lockedNext(firmId);
        if (startLocked()) throw new ConflictException("error.billNumbering.locked");
        em.createNativeQuery("update bill_number_counter set next_no = :n where firm_id = :f").setParameter("n", nextNo).setParameter("f", firmId).executeUpdate();
        audit.record("FIRM", firmId, "BILL_NUMBERING_START_SET", "next bill number set to " + nextNo + " before the first numbered bill");
        return nextNo;
    }

    private void ensureCounter(long firmId) {
        em.createNativeQuery("insert into bill_number_counter (firm_id, next_no) values (:f, 1) on conflict (firm_id) do nothing").setParameter("f", firmId).executeUpdate();
    }

    private int lockedNext(long firmId) {
        return ((Number) em.createNativeQuery("select next_no from bill_number_counter where firm_id = :f for update")
                .setParameter("f", firmId).getSingleResult()).intValue();
    }
}
