package com.mandi.billing.aadat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.mandi.billing.aadat.calc.CalculationMode;
import com.mandi.masters.Farmer;
import com.mandi.masters.Village;

import org.hibernate.annotations.BatchSize;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * An Aadat bill as entered. Stores entered values plus the calculation status / rule set it was saved under.
 * No computed amount is persisted (rules are unverified).
 */
@Entity
@Table(name = "aadat_bill")
public class AadatBill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bill_date", nullable = false)
    private LocalDate billDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "farmer_id", nullable = false)
    private Farmer farmer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "village_id")
    private Village village;

    @Column(name = "vehicle_no", length = 30)
    private String vehicleNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "calculation_status", nullable = false, length = 30)
    private CalculationMode calculationStatus;

    @Column(name = "rule_set_id", nullable = false, length = 60)
    private String ruleSetId;

    @Column(name = "firm_id", nullable = false, updatable = false)
    private Long firmId;

    /** Client-chosen key that makes a retried save return this bill instead of creating a second one (V10). */
    @Column(name = "idempotency_key", length = 64, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", updatable = false)
    private byte[] requestHash;

    /** Official number: one continuous series per firm across financial years; null for bills saved before V11. */
    @Column(name = "bill_no", updatable = false)
    private Integer billNo;

    @Column(length = 100)
    private String transport;

    @Column(name = "bhada_paid")
    private Boolean bhadaPaid;

    @Column(name = "bhada_amount", precision = 18, scale = 6)
    private java.math.BigDecimal bhadaAmount;

    /** A label only, with no accounting effect. */
    @Enumerated(EnumType.STRING)
    @Column(name = "bill_type", length = 10)
    private BillType billType;

    // optional reference numbers: meaning not confirmed, stored exactly as typed
    @Column(name = "panjiyan_no", length = 40)
    private String panjiyanNo;
    @Column(name = "book_no", length = 30)
    private String bookNo;
    @Column(name = "receipt_no", length = 30)
    private String receiptNo;
    @Column(name = "entry_no", length = 30)
    private String entryNo;
    @Column(name = "ws_no", length = 30)
    private String wsNo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    @BatchSize(size = 50)
    private List<AadatLot> lots = new ArrayList<>();

    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq")
    @BatchSize(size = 50)
    private List<AadatExpense> expenses = new ArrayList<>();

    public void setIdempotency(String key, byte[] hash) {
        this.idempotencyKey = key;
        this.requestHash = hash == null ? null : hash.clone();
    }

    public String getIdempotencyKey() { return idempotencyKey; }

    public byte[] getRequestHash() { return requestHash == null ? null : requestHash.clone(); }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public void addLot(AadatLot lot) {
        requireFirm();
        lot.setBill(this);
        lot.setFirmId(firmId);
        lots.add(lot);
    }

    public void addExpense(AadatExpense expense) {
        requireFirm();
        expense.setBill(this);
        expense.setFirmId(firmId);
        expenses.add(expense);
    }

    private void requireFirm() {
        if (firmId == null) {
            throw new IllegalStateException("Set the firm before adding lots or expenses");
        }
    }

    public Long getId() { return id; }
    public Long getFirmId() { return firmId; }
    public void setFirmId(Long firmId) { this.firmId = firmId; }
    public LocalDate getBillDate() { return billDate; }
    public void setBillDate(LocalDate billDate) { this.billDate = billDate; }
    public Farmer getFarmer() { return farmer; }
    public void setFarmer(Farmer farmer) { this.farmer = farmer; }
    public Village getVillage() { return village; }
    public void setVillage(Village village) { this.village = village; }
    public String getVehicleNo() { return vehicleNo; }
    public void setVehicleNo(String vehicleNo) { this.vehicleNo = vehicleNo; }
    public CalculationMode getCalculationStatus() { return calculationStatus; }
    public void setCalculationStatus(CalculationMode calculationStatus) { this.calculationStatus = calculationStatus; }
    public String getRuleSetId() { return ruleSetId; }
    public void setRuleSetId(String ruleSetId) { this.ruleSetId = ruleSetId; }
    public Instant getCreatedAt() { return createdAt; }
    public Integer getBillNo() { return billNo; }
    public void setBillNo(Integer billNo) { this.billNo = billNo; }
    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }
    public Boolean getBhadaPaid() { return bhadaPaid; }
    public void setBhadaPaid(Boolean bhadaPaid) { this.bhadaPaid = bhadaPaid; }
    public java.math.BigDecimal getBhadaAmount() { return bhadaAmount; }
    public void setBhadaAmount(java.math.BigDecimal bhadaAmount) { this.bhadaAmount = bhadaAmount; }
    public BillType getBillType() { return billType; }
    public void setBillType(BillType billType) { this.billType = billType; }
    public String getPanjiyanNo() { return panjiyanNo; }
    public void setPanjiyanNo(String v) { this.panjiyanNo = v; }
    public String getBookNo() { return bookNo; }
    public void setBookNo(String v) { this.bookNo = v; }
    public String getReceiptNo() { return receiptNo; }
    public void setReceiptNo(String v) { this.receiptNo = v; }
    public String getEntryNo() { return entryNo; }
    public void setEntryNo(String v) { this.entryNo = v; }
    public String getWsNo() { return wsNo; }
    public void setWsNo(String v) { this.wsNo = v; }
    public List<AadatLot> getLots() { return lots; }
    public List<AadatExpense> getExpenses() { return expenses; }
}
