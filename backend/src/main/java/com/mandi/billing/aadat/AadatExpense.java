package com.mandi.billing.aadat;

import java.math.BigDecimal;

import com.mandi.masters.ExpenseHead;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** One expense line: a typed amount, or pieces x the head's predefined rate (quantity and rate kept). {@code seq} is entry order only. */
@Entity
@Table(name = "aadat_expense")
public class AadatExpense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "firm_id", nullable = false, updatable = false)
    private Long firmId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    private AadatBill bill;

    @Column(nullable = false)
    private int seq;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "expense_head_id", nullable = false)
    private ExpenseHead expenseHead;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal amount;

    /** For a head with a predefined rate: the number of pieces and the rate per piece the amount was calculated with. */
    @Column(precision = 18, scale = 6)
    private BigDecimal quantity;

    @Column(precision = 18, scale = 6)
    private BigDecimal rate;

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }
    public Long getId() { return id; }
    public Long getFirmId() { return firmId; }
    void setFirmId(Long firmId) { this.firmId = firmId; }
    public AadatBill getBill() { return bill; }
    void setBill(AadatBill bill) { this.bill = bill; }
    public int getSeq() { return seq; }
    public void setSeq(int seq) { this.seq = seq; }
    public ExpenseHead getExpenseHead() { return expenseHead; }
    public void setExpenseHead(ExpenseHead expenseHead) { this.expenseHead = expenseHead; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
