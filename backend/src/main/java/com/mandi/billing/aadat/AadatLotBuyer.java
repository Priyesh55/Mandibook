package com.mandi.billing.aadat;

import java.math.BigDecimal;

import com.mandi.masters.Buyer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * What one purchaser actually bought of one lot, exactly as the operator typed it. Nothing is derived, divided or rounded:
 * quantities, weight, rate, value and commission are entered values. Read-only after save (the runtime role has no UPDATE).
 */
@Entity
@Table(name = "aadat_lot_buyer")
public class AadatLotBuyer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "firm_id", nullable = false, updatable = false)
    private Long firmId;

    @Column(name = "bill_id", nullable = false, updatable = false)
    private Long billId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lot_id", nullable = false)
    private AadatLot lot;

    @Column(nullable = false)
    private int seq;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buyer_id", nullable = false)
    private Buyer buyer;

    /** The person who bought on the purchaser's behalf (optional free text). */
    @Column(length = 100)
    private String haste;

    private Integer pieces;

    @Column(precision = 18, scale = 6)
    private BigDecimal weight;

    @Column(precision = 18, scale = 6)
    private BigDecimal rate;

    @Column(precision = 18, scale = 6)
    private BigDecimal value;

    @Column(name = "commission_applies", nullable = false)
    private boolean commissionApplies;

    @Column(name = "commission_percent", precision = 9, scale = 4)
    private BigDecimal commissionPercent;

    @Column(name = "commission_amount", precision = 18, scale = 6)
    private BigDecimal commissionAmount;

    /** The bill id exists only once the bill has been inserted, so it is read from the lot at persist time. */
    @jakarta.persistence.PrePersist
    void onCreate() {
        this.billId = lot.getBill().getId();
    }

    public Long getId() { return id; }
    public Long getFirmId() { return firmId; }
    void setFirmId(Long firmId) { this.firmId = firmId; }
    public Long getBillId() { return billId; }
    void setBillId(Long billId) { this.billId = billId; }
    public AadatLot getLot() { return lot; }
    void setLot(AadatLot lot) { this.lot = lot; }
    public int getSeq() { return seq; }
    public void setSeq(int seq) { this.seq = seq; }
    public Buyer getBuyer() { return buyer; }
    public void setBuyer(Buyer buyer) { this.buyer = buyer; }
    public String getHaste() { return haste; }
    public void setHaste(String haste) { this.haste = haste; }
    public Integer getPieces() { return pieces; }
    public void setPieces(Integer pieces) { this.pieces = pieces; }
    public BigDecimal getWeight() { return weight; }
    public void setWeight(BigDecimal weight) { this.weight = weight; }
    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }
    public boolean isCommissionApplies() { return commissionApplies; }
    public void setCommissionApplies(boolean commissionApplies) { this.commissionApplies = commissionApplies; }
    public BigDecimal getCommissionPercent() { return commissionPercent; }
    public void setCommissionPercent(BigDecimal commissionPercent) { this.commissionPercent = commissionPercent; }
    public BigDecimal getCommissionAmount() { return commissionAmount; }
    public void setCommissionAmount(BigDecimal commissionAmount) { this.commissionAmount = commissionAmount; }
}
