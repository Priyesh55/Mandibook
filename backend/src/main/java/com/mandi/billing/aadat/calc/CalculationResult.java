package com.mandi.billing.aadat.calc;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result of a calculation request. {@code verified} is false for every mode that exists today.
 * Consumers (UI, print, reports) must show {@code noticeKey} next to any figure and must not
 * feed these figures into receivables, payables or any accounting record.
 */
public record CalculationResult(
        CalculationMode mode,
        String ruleSetId,
        boolean verified,
        String noticeKey,
        List<LotAmount> lotAmounts,
        BigDecimal lotSubtotal,
        BigDecimal enteredExpensesTotal) {

    /** Amount is null when the inputs needed by the demonstration rule were not entered. */
    public record LotAmount(int lineNo, BigDecimal amount) {
    }

    /** The mode of every new bill: figures come from BillCalculator and are stored; nothing is recomputed by this service. */
    public static CalculationResult calculated() {
        return new CalculationResult(CalculationMode.CALCULATED, com.mandi.billing.aadat.BillCalculator.RULE_SET_ID,
                true, null, List.of(), null, null);
    }

    public static CalculationResult notCalculated() {
        return new CalculationResult(CalculationMode.NOT_CALCULATED, AadatCalculationService.NO_RULE_SET,
                false, "calculation.notCalculated", List.of(), null, null);
    }
}
