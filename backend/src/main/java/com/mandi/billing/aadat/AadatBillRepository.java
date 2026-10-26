package com.mandi.billing.aadat;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AadatBillRepository extends JpaRepository<AadatBill, Long>, JpaSpecificationExecutor<AadatBill> {

    /** The only way to load a bill by id: it must belong to the given firm, otherwise it does not exist. */
    Optional<AadatBill> findByIdAndFirmId(Long id, Long firmId);

    /** The bill a retried save refers to: same firm, same client key. */
    Optional<AadatBill> findByFirmIdAndIdempotencyKey(Long firmId, String idempotencyKey);

    /**
     * Bill with everything needed to show it, loaded in TWO statements instead of one per lot and per expense.
     * A bill has two list collections (lots, expenses); Hibernate cannot fetch two lists in one query, so the lots
     * (with item and buyer) come here and the expenses (with their head) from
     * {@link #findWithExpensesByIdAndFirmId}. Both run in the same transaction and fill the same entity.
     * Both are firm-scoped like {@link #findByIdAndFirmId}.
     */
    @Query("""
            select b from AadatBill b
            left join fetch b.farmer
            left join fetch b.village
            left join fetch b.lots l
            left join fetch l.item
            left join fetch l.buyer
            where b.id = :id and b.firmId = :firmId""")
    Optional<AadatBill> findWithLotsByIdAndFirmId(@Param("id") Long id, @Param("firmId") Long firmId);

    @Query("""
            select b from AadatBill b
            left join fetch b.expenses e
            left join fetch e.expenseHead
            where b.id = :id and b.firmId = :firmId""")
    Optional<AadatBill> findWithExpensesByIdAndFirmId(@Param("id") Long id, @Param("firmId") Long firmId);

    /** Number of lots per bill for the given bills (one grouped query; no lot rows are loaded). Rows: {billId, count}. */
    @Query("""
            select l.bill.id, count(l) from AadatLot l
            where l.firmId = :firmId and l.bill.id in :billIds
            group by l.bill.id""")
    List<Object[]> countLotsByBill(@Param("billIds") Collection<Long> billIds, @Param("firmId") Long firmId);

    /** Sum of the lot values per bill (one grouped query, for the list). Rows: {billId, sum}. */
    @Query("""
            select l.bill.id, sum(l.lotValue) from AadatLot l
            where l.firmId = :firmId and l.bill.id in :billIds
            group by l.bill.id""")
    List<Object[]> sumLotValuesByBill(@Param("billIds") Collection<Long> billIds, @Param("firmId") Long firmId);

    /** Sum of the expense amounts per bill. Rows: {billId, sum}. */
    @Query("""
            select e.bill.id, sum(e.amount) from AadatExpense e
            where e.firmId = :firmId and e.bill.id in :billIds
            group by e.bill.id""")
    List<Object[]> sumExpensesByBill(@Param("billIds") Collection<Long> billIds, @Param("firmId") Long firmId);

    @Override
    @EntityGraph(attributePaths = {"farmer", "village"})
    Page<AadatBill> findAll(Specification<AadatBill> spec, Pageable pageable);
}
