package com.mandi.billing.aadat;

import com.mandi.shared.BusinessRuleException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The amounts of a bill, calculated from what the operator enters (weight, rate, pieces) by the rules of
 * docs/business-rules.md. The owner explicitly confirmed on 2026-10-25 the 5 percent commission, the rate per 100 kg and the
 * per-piece expenses; the rounding details are recorded there but not separately confirmed. This is the ONLY place these
 * formulas live; the browser shows a live preview of the same arithmetic but the server is authoritative and stores its own result.
 *
 * <ul>
 *   <li>the RATE is entered per 100 kg (onion at 30 rupees a kg is entered as 3000); the rate per kg is the rate divided by 100</li>
 *   <li>lot value = weight (kg) x rate per kg, rounded to a whole rupee (one rounding, at the end)</li>
 *   <li>purchaser value = purchaser weight x purchaser rate per kg, rounded to a whole rupee</li>
 *   <li>commission = {@value #COMMISSION_PERCENT} percent of the purchaser value (fixed for every bill), rounded to one decimal;
 *       charged to the buyer, never deducted from the farmer</li>
 *   <li>expense with a predefined rate = pieces x rate per piece, rounded to two decimals; other expenses are typed</li>
 *   <li>farmer gross = sum of lot values; farmer net = gross - expenses, rounded to a whole rupee</li>
 * </ul>
 *
 * Rounding is half-up. Whole rupees for values and net, one decimal for commission and two for expenses follow the
 * owner's rule statement of 2026-10-25; the tie rule is the owner's default until a real tie example is confirmed (docs/business-rules.md).
 */
public final class BillCalculator {

    public static final int COMMISSION_PERCENT = 5;
    public static final String RULE_SET_ID = "OWNER_2026_10_25_Q100";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private BillCalculator() {
    }

    /** Largest amount the amount columns (numeric(18,6)) can hold; a larger result is refused, never stored truncated. */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999");

    /** weight (kg) x rate per kg to a whole rupee, where the entered rate is per 100 kg; null when either is missing. */
    public static BigDecimal value(BigDecimal weight, BigDecimal ratePer100Kg) {
        if (weight == null || ratePer100Kg == null) return null;
        requireNotNegative(weight);
        requireNotNegative(ratePer100Kg);
        return inRange(weight.multiply(ratePer100Kg).divide(HUNDRED, 0, RoundingMode.HALF_UP));
    }

    public static BigDecimal commission(BigDecimal value) {
        if (value == null) return null;
        requireNotNegative(value);
        return inRange(value.multiply(BigDecimal.valueOf(COMMISSION_PERCENT)).divide(HUNDRED, 1, RoundingMode.HALF_UP));
    }

    public static BigDecimal perPieceExpense(long pieces, BigDecimal rate) {
        if (rate == null) throw new IllegalArgumentException("rate is required");
        if (pieces < 0) throw new IllegalArgumentException("pieces must not be negative");
        requireNotNegative(rate);
        return inRange(BigDecimal.valueOf(pieces).multiply(rate).setScale(2, RoundingMode.HALF_UP));
    }

    public static BigDecimal net(BigDecimal gross, BigDecimal expenses) {
        if (gross == null || expenses == null) throw new IllegalArgumentException("gross and expenses are required");
        // a negative net (expenses above gross) is a legitimate result and is kept
        return gross.subtract(expenses).setScale(0, RoundingMode.HALF_UP);
    }

    private static void requireNotNegative(BigDecimal n) {
        if (n.signum() < 0) throw new IllegalArgumentException("negative quantities and rates are not valid");
    }

    private static BigDecimal inRange(BigDecimal amount) {
        if (amount.compareTo(MAX_AMOUNT) > 0) throw new BusinessRuleException("validation.amount.tooLarge");
        return amount;
    }
}
