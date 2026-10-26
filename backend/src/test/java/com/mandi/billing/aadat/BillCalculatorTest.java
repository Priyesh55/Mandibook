package com.mandi.billing.aadat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mandi.shared.BusinessRuleException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Unit vectors for the owner-confirmed rules (docs/business-rules.md BR-001 to BR-006). Every expected figure is worked out by
 * hand in the comment beside it, not taken from the code under test. Synthetic numbers only.
 */
class BillCalculatorTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    void theRateIsPer100KgSoAThirtyRupeeKiloIsEnteredAs3000() {
        // 250 kg at 30 a kg = 7500; the entered rate is 3000 per 100 kg
        assertThat(BillCalculator.value(d("250"), d("3000"))).isEqualByComparingTo("7500");
        // 50 kg at 18 a kg = 900
        assertThat(BillCalculator.value(d("50"), d("1800"))).isEqualByComparingTo("900");
    }

    @Test
    void valuesRoundHalfUpToAWholeRupeeInOneRoundingAtTheEnd() {
        // 333 x 1937 / 100 = 6450.21 -> 6450
        assertThat(BillCalculator.value(d("333"), d("1937"))).isEqualByComparingTo("6450");
        // 2.5 x 100 / 100 = 2.5 -> 3 (half up)
        assertThat(BillCalculator.value(d("2.5"), d("100"))).isEqualByComparingTo("3");
        // 2.49 x 100 / 100 = 2.49 -> 2
        assertThat(BillCalculator.value(d("2.49"), d("100"))).isEqualByComparingTo("2");
        // 0.5 x 100 / 100 = 0.5 -> 1
        assertThat(BillCalculator.value(d("0.5"), d("100"))).isEqualByComparingTo("1");
    }

    @Test
    void zeroWeightOrZeroRateGivesZeroNotAnError() {
        assertThat(BillCalculator.value(d("0"), d("1800"))).isEqualByComparingTo("0");
        assertThat(BillCalculator.value(d("50"), d("0"))).isEqualByComparingTo("0");
    }

    @Test
    void aMissingWeightOrRateGivesNoValueInsteadOfAGuess() {
        assertThat(BillCalculator.value(null, d("1800"))).isNull();
        assertThat(BillCalculator.value(d("50"), null)).isNull();
        assertThat(BillCalculator.commission(null)).isNull();
    }

    @Test
    void commissionIsFivePercentToOneDecimalHalfUp() {
        assertThat(BillCalculator.COMMISSION_PERCENT).isEqualTo(5);
        assertThat(BillCalculator.commission(d("900"))).isEqualByComparingTo("45.0");   // 900 x 5 / 100
        assertThat(BillCalculator.commission(d("6450"))).isEqualByComparingTo("322.5"); // 322.5 exactly
        assertThat(BillCalculator.commission(d("3"))).isEqualByComparingTo("0.2");      // 0.15 -> 0.2 half up
        assertThat(BillCalculator.commission(d("1"))).isEqualByComparingTo("0.1");      // 0.05 -> 0.1 half up
        assertThat(BillCalculator.commission(d("0"))).isEqualByComparingTo("0.0");
    }

    @Test
    void aPerPieceExpenseIsPiecesTimesRateToTwoDecimals() {
        assertThat(BillCalculator.perPieceExpense(10, d("2"))).isEqualByComparingTo("20.00");
        assertThat(BillCalculator.perPieceExpense(35, d("2.5"))).isEqualByComparingTo("87.50");   // synthetic rate: 35 x 2.5
        assertThat(BillCalculator.perPieceExpense(35, d("1.75"))).isEqualByComparingTo("61.25");  // synthetic rate: 35 x 1.75
        assertThat(BillCalculator.perPieceExpense(35, d("3"))).as("a different configured rate gives a different amount").isEqualByComparingTo("105.00");
        assertThat(BillCalculator.perPieceExpense(0, d("2.5"))).isEqualByComparingTo("0.00");
        assertThat(BillCalculator.perPieceExpense(3, d("0.125"))).as("0.375 rounds half up to two decimals").isEqualByComparingTo("0.38");
        assertThat(BillCalculator.perPieceExpense(1, d("0.005"))).isEqualByComparingTo("0.01");   // half up at two decimals
    }

    @Test
    void netIsGrossLessExpensesToAWholeRupeeAndMayBeNegative() {
        assertThat(BillCalculator.net(d("900"), d("20"))).isEqualByComparingTo("880");
        assertThat(BillCalculator.net(d("40500"), d("1250.85"))).isEqualByComparingTo("39249");   // 39249.15
        assertThat(BillCalculator.net(d("100"), d("100.5"))).isEqualByComparingTo("-1");          // -0.5 rounds half-up away from zero (tie rule unconfirmed, BR-101)
        assertThat(BillCalculator.net(d("100"), d("250"))).isEqualByComparingTo("-150");
    }

    @Test
    void negativeQuantitiesAndRatesAreRejectedBeforeAnyArithmetic() {
        assertThatThrownBy(() -> BillCalculator.value(d("-1"), d("100"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BillCalculator.value(d("1"), d("-100"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BillCalculator.commission(d("-1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BillCalculator.perPieceExpense(-1, d("2"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BillCalculator.perPieceExpense(1, d("-2"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BillCalculator.perPieceExpense(1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BillCalculator.net(null, d("1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BillCalculator.net(d("1"), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aResultBeyondWhatCanBeStoredIsRefusedNotTruncated() {
        assertThat(BillCalculator.value(d("999999999"), d("100000"))).isEqualByComparingTo("999999999000"); // exactly representable
        assertThatThrownBy(() -> BillCalculator.value(d("999999999"), d("999999999"))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> BillCalculator.perPieceExpense(4_000_000_000L, d("999999999"))).isInstanceOf(BusinessRuleException.class);
    }
}
