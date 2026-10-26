package com.mandi.masters;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A kind of expense. When {@code rateBasis} is PER_PIECE the bill multiplies its number of pieces (bags) by {@code rate};
 * without a rate the operator types the amount on each bill.
 */
@Entity
@Table(name = "expense_head")
public class ExpenseHead extends MasterEntity {

    public static final String PER_PIECE = "PER_PIECE";

    @Column(name = "rate_basis", length = 12)
    private String rateBasis;

    @Column(precision = 18, scale = 6)
    private BigDecimal rate;

    public String getRateBasis() { return rateBasis; }
    public void setRateBasis(String rateBasis) { this.rateBasis = rateBasis; }
    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }
    public boolean hasRate() { return PER_PIECE.equals(rateBasis) && rate != null; }
}
