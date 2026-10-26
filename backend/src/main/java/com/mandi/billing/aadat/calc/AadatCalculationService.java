package com.mandi.billing.aadat.calc;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.mandi.shared.BusinessRuleException;

/**
 * The ONLY place in the application where bill amounts may be calculated.
 * Nothing here is a verified business rule. Default behaviour is to calculate nothing.
 */
@Service
public class AadatCalculationService {

    public static final String NO_RULE_SET = "NONE";

    private final Map<CalculationMode, AadatRuleSet> ruleSets;

    public AadatCalculationService(List<AadatRuleSet> ruleSets) {
        this.ruleSets = ruleSets.stream().collect(Collectors.toMap(AadatRuleSet::mode, Function.identity()));
    }

    /** True when the opt-in demonstration rule set has been enabled on this server. */
    public boolean demoAvailable() {
        return ruleSets.containsKey(CalculationMode.DEMO_UNVERIFIED);
    }

    public CalculationResult calculate(CalculationMode mode, CalculationInput input) {
        if (mode == null || mode == CalculationMode.NOT_CALCULATED) {
            return CalculationResult.notCalculated();
        }
        if (mode == CalculationMode.CALCULATED) {
            return CalculationResult.calculated();
        }
        AadatRuleSet ruleSet = ruleSets.get(mode);
        if (ruleSet == null) {
            throw new BusinessRuleException("error.demoCalculation.disabled");
        }
        return ruleSet.apply(input);
    }
}
