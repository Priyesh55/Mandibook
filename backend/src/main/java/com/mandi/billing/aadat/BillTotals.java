package com.mandi.billing.aadat;

import java.math.BigDecimal;
import java.util.List;

import com.mandi.billing.aadat.AadatDtos.AllocationSummary;
import com.mandi.billing.aadat.AadatDtos.EnteredTotals;

/**
 * The ONLY place that adds up what the operator typed. Plain exact sums (BigDecimal, no rounding) and one subtraction;
 * no rate, unit, percentage or commission rule is applied, so none of this is a business formula. Farmer side and
 * buyer side are computed separately and are never combined. Everything is derived from the stored lines on every read, so a
 * printed bill and the bill view cannot disagree.
 */
final class BillTotals {

    private BillTotals() {
    }

    static AllocationSummary allocationOf(AadatLot lot) {
        long pieces = 0;
        BigDecimal weight = BigDecimal.ZERO;
        BigDecimal value = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        for (AadatLotBuyer p : lot.getPurchasers()) {
            if (p.getPieces() != null) pieces += p.getPieces();
            weight = weight.add(nz(p.getWeight()));
            value = value.add(nz(p.getValue()));
            commission = commission.add(nz(p.getCommissionAmount()));
        }
        return new AllocationSummary(lot.getPurchasers().size(), pieces, weight, value, commission);
    }

    /** Entered purchase value of a purchaser row: its entered value plus its entered commission amount (null when neither was entered). */
    static BigDecimal purchaseValueOf(AadatLotBuyer p) {
        if (p.getValue() == null && p.getCommissionAmount() == null) return null;
        return nz(p.getValue()).add(nz(p.getCommissionAmount()));
    }

    /** Farmer-side figures of one bill from its gross and expense sum; a calculated bill rounds its net to a whole rupee. */
    static BigDecimal netOf(BigDecimal gross, BigDecimal expenses, boolean calculated) {
        return calculated ? BillCalculator.net(gross, expenses) : gross.subtract(expenses);
    }

    static EnteredTotals of(List<AadatLot> lots, List<AadatExpense> expenses, boolean calculated) {
        BigDecimal gross = BigDecimal.ZERO;
        int lotsWithout = 0;
        BigDecimal buyerValue = BigDecimal.ZERO;
        BigDecimal buyerCommission = BigDecimal.ZERO;
        int rowsWithout = 0;
        for (AadatLot l : lots) {
            if (l.getLotValue() == null) lotsWithout++; else gross = gross.add(l.getLotValue());
            for (AadatLotBuyer p : l.getPurchasers()) {
                buyerValue = buyerValue.add(nz(p.getValue()));
                buyerCommission = buyerCommission.add(nz(p.getCommissionAmount()));
                if (p.getValue() == null) rowsWithout++;
            }
        }
        BigDecimal expenseTotal = BigDecimal.ZERO;
        for (AadatExpense e : expenses) expenseTotal = expenseTotal.add(nz(e.getAmount()));
        return new EnteredTotals(calculated ? EnteredTotals.CALCULATED : EnteredTotals.SUM_OF_ENTERED,
                new EnteredTotals.FarmerSide(gross, expenseTotal, netOf(gross, expenseTotal, calculated), lotsWithout),
                new EnteredTotals.BuyerSide(buyerValue, buyerCommission, buyerValue.add(buyerCommission), rowsWithout));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
