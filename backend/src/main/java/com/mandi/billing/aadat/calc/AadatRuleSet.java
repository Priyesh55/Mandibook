package com.mandi.billing.aadat.calc;

/** A named, versioned set of calculation rules. Verified rule sets will be added here once confirmed. */
public interface AadatRuleSet {

    String id();

    CalculationMode mode();

    CalculationResult apply(CalculationInput input);
}
