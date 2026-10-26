package com.mandi.billing.aadat.calc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.mandi.billing.aadat.calc.CalculationInput.ExpenseInput;
import com.mandi.billing.aadat.calc.CalculationInput.LotInput;
import com.mandi.billing.aadat.calc.CalculationResult.LotAmount;

/**
 * DEMONSTRATION ONLY. "weight x rate" is NOT a confirmed business rule: the rate unit, the role of pieces,
 * rounding and the meaning of the total are all unresolved (questions Q1, Q4, Q6).
 * Exact BigDecimal arithmetic, no rounding. Only registered when mandi.calculation.demo-enabled=true.
 * Produces no net/payable/receivable figure.
 */
@Component
@ConditionalOnProperty(name = "mandi.calculation.demo-enabled", havingValue = "true")
public class DemoWeightTimesRateRuleSet implements AadatRuleSet {

    public static final String ID = "DEMO_WEIGHT_X_RATE_V0";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public CalculationMode mode() {
        return CalculationMode.DEMO_UNVERIFIED;
    }

    @Override
    public CalculationResult apply(CalculationInput input) {
        List<LotAmount> amounts = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (LotInput lot : input.lots()) {
            BigDecimal amount = lot.weight() != null && lot.rate() != null ? lot.weight().multiply(lot.rate()) : null;
            amounts.add(new LotAmount(lot.lineNo(), amount));
            if (amount != null) {
                subtotal = subtotal.add(amount);
            }
        }
        BigDecimal expenses = BigDecimal.ZERO;
        for (ExpenseInput e : input.expenses()) {
            expenses = expenses.add(e.amount());
        }
        return new CalculationResult(mode(), ID, false, "calculation.demoUnverified", amounts, subtotal, expenses);
    }
}
