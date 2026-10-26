package com.mandi.billing.aadat.calc;

import java.math.BigDecimal;
import java.util.List;

public record CalculationInput(List<LotInput> lots, List<ExpenseInput> expenses) {

    public record LotInput(int lineNo, Integer pieces, BigDecimal weight, BigDecimal rate) {
    }

    /** An expense amount exactly as typed by the operator. */
    public record ExpenseInput(int seq, BigDecimal amount) {
    }
}
