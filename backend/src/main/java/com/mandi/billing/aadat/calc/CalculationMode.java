package com.mandi.billing.aadat.calc;

/**
 * How a bill's amounts are presented. There is deliberately NO verified mode yet:
 * no business rule has been confirmed (docs/business-rules.md).
 * A future verified rule set must add its own mode and test vectors.
 */
public enum CalculationMode {
    /** Bills saved before the owner confirmed the rules: typed amounts, shown as entered. Never produced for a new bill. */
    NOT_CALCULATED,
    /** The amounts were calculated by the server with the owner-confirmed rules (BillCalculator). The mode of every new bill. */
    CALCULATED,
    /** Opt-in demonstration. Results are UNVERIFIED and must never be treated as accounting amounts. */
    DEMO_UNVERIFIED
}
