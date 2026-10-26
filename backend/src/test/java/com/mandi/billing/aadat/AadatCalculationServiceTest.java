package com.mandi.billing.aadat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mandi.billing.aadat.calc.AadatCalculationService;
import com.mandi.billing.aadat.calc.CalculationInput;
import com.mandi.billing.aadat.calc.CalculationInput.ExpenseInput;
import com.mandi.billing.aadat.calc.CalculationInput.LotInput;
import com.mandi.billing.aadat.calc.CalculationMode;
import com.mandi.billing.aadat.calc.CalculationResult;
import com.mandi.billing.aadat.calc.DemoWeightTimesRateRuleSet;
import com.mandi.shared.BusinessRuleException;

/**
 * These tests protect the SAFETY CONTRACT of the calculator, not any business rule.
 * The demonstration rule set is not a verified rule; its tests only prove it behaves as documented.
 */
class AadatCalculationServiceTest {

    private static CalculationInput input() {
        return new CalculationInput(
                List.of(new LotInput(1, 10, new BigDecimal("100"), new BigDecimal("2.5")),
                        new LotInput(2, null, new BigDecimal("5"), null)),
                List.of(new ExpenseInput(1, new BigDecimal("7.25"))));
    }

    private final AadatCalculationService withoutDemo = new AadatCalculationService(List.of());
    private final AadatCalculationService withDemo =
            new AadatCalculationService(List.of(new DemoWeightTimesRateRuleSet()));

    @Test
    void defaultModeProducesNoAmountsAndIsNeverVerified() {
        CalculationResult r = withoutDemo.calculate(CalculationMode.NOT_CALCULATED, input());
        assertThat(r.verified()).isFalse();
        assertThat(r.mode()).isEqualTo(CalculationMode.NOT_CALCULATED);
        assertThat(r.ruleSetId()).isEqualTo(AadatCalculationService.NO_RULE_SET);
        assertThat(r.lotAmounts()).isEmpty();
        assertThat(r.lotSubtotal()).isNull();
        assertThat(r.enteredExpensesTotal()).isNull();
        assertThat(r.noticeKey()).isEqualTo("calculation.notCalculated");
    }

    @Test
    void nullModeBehavesAsNotCalculated() {
        assertThat(withoutDemo.calculate(null, input()).mode()).isEqualTo(CalculationMode.NOT_CALCULATED);
    }

    @Test
    void demoModeIsRefusedWhenNotExplicitlyEnabled() {
        assertThat(withoutDemo.demoAvailable()).isFalse();
        assertThatThrownBy(() -> withoutDemo.calculate(CalculationMode.DEMO_UNVERIFIED, input()))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void demoModeIsAlwaysFlaggedUnverified() {
        CalculationResult r = withDemo.calculate(CalculationMode.DEMO_UNVERIFIED, input());
        assertThat(withDemo.demoAvailable()).isTrue();
        assertThat(r.verified()).isFalse();
        assertThat(r.mode()).isEqualTo(CalculationMode.DEMO_UNVERIFIED);
        assertThat(r.ruleSetId()).isEqualTo(DemoWeightTimesRateRuleSet.ID);
        assertThat(r.noticeKey()).isEqualTo("calculation.demoUnverified");
    }

    @Test
    void demoRuleIsExactAndSkipsLotsWithMissingInputs() {
        CalculationResult r = withDemo.calculate(CalculationMode.DEMO_UNVERIFIED, input());
        assertThat(r.lotAmounts()).hasSize(2);
        assertThat(r.lotAmounts().get(0).amount()).isEqualByComparingTo("250.0");
        assertThat(r.lotAmounts().get(1).amount()).isNull();
        assertThat(r.lotSubtotal()).isEqualByComparingTo("250.0");
        assertThat(r.enteredExpensesTotal()).isEqualByComparingTo("7.25");
    }

    @Test
    void demoRuleDoesNotRoundOrInventPrecision() {
        CalculationInput in = new CalculationInput(
                List.of(new LotInput(1, null, new BigDecimal("333"), new BigDecimal("19.37"))), List.of());
        CalculationResult r = withDemo.calculate(CalculationMode.DEMO_UNVERIFIED, in);
        assertThat(r.lotAmounts().get(0).amount()).isEqualByComparingTo("6450.21");
    }

    @Test
    void resultNeverContainsNetPayableOrReceivableFigures() {
        // Contract guard: the result type exposes only lot amounts, a lot subtotal and the entered expense sum.
        assertThat(CalculationResult.class.getRecordComponents())
                .extracting(c -> c.getName())
                .containsExactly("mode", "ruleSetId", "verified", "noticeKey", "lotAmounts", "lotSubtotal",
                        "enteredExpensesTotal");
    }
}
