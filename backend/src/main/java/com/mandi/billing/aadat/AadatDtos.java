package com.mandi.billing.aadat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.mandi.billing.aadat.calc.CalculationMode;
import com.mandi.billing.aadat.calc.CalculationResult;
import com.mandi.firm.FirmProfile;
import com.mandi.masters.MasterDtos.MasterRef;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class AadatDtos {
    private AadatDtos() {
    }

    public record BillRequest(
            @NotNull(message = "{validation.billDate.required}") LocalDate billDate,
            /** The financial year the operator is working in (start year, 2026 = 2026-27). When given, the date must fall in it. */
            Integer financialYear,
            @NotNull(message = "{validation.farmer.required}") Long farmerId,
            Long villageId,
            @Size(max = 30, message = "{validation.vehicleNo.size}") String vehicleNo,
            @Size(max = 100, message = "{validation.text.size}") String transport,
            Boolean bhadaPaid,
            @PositiveOrZero(message = "{validation.number.nonNegative}")
            @Digits(integer = 9, fraction = 4, message = "{validation.number.digits}") BigDecimal bhadaAmount,
            BillType billType,
            @Size(max = 40, message = "{validation.text.size}") String panjiyanNo,
            @Size(max = 30, message = "{validation.text.size}") String bookNo,
            @Size(max = 30, message = "{validation.text.size}") String receiptNo,
            @Size(max = 30, message = "{validation.text.size}") String entryNo,
            @Size(max = 30, message = "{validation.text.size}") String wsNo,
            /** Null means NOT_CALCULATED. DEMO_UNVERIFIED must be requested explicitly. */
            CalculationMode calculationMode,
            @NotEmpty(message = "{validation.lots.required}") List<@NotNull(message = "{validation.lots.required}") @Valid LotRequest> lots,
            List<@NotNull(message = "{validation.amount.required}") @Valid ExpenseRequest> expenses) {
    }

    public record LotRequest(
            @NotNull(message = "{validation.item.required}") Long itemId,
            @PositiveOrZero(message = "{validation.number.nonNegative}") Integer pieces,
            @PositiveOrZero(message = "{validation.number.nonNegative}")
            @Digits(integer = 9, fraction = 4, message = "{validation.number.digits}") BigDecimal weight,
            @PositiveOrZero(message = "{validation.number.nonNegative}")
            @Digits(integer = 9, fraction = 4, message = "{validation.number.digits}") BigDecimal rate,
            /** Single buyer of the lot (older bills); a lot with purchaser rows must leave it empty. */
            Long buyerId,
            @Size(max = 60, message = "{validation.text.size}") String marka,
            List<@NotNull(message = "{validation.purchaser.buyer.required}") @Valid PurchaserRequest> purchasers) {
    }

    /** What one purchaser bought of the lot: weight and rate are entered, the value and the 5 percent commission are calculated. */
    public record PurchaserRequest(
            @NotNull(message = "{validation.purchaser.buyer.required}") Long buyerId,
            @Size(max = 100, message = "{validation.text.size}") String haste,
            @PositiveOrZero(message = "{validation.number.nonNegative}") Integer pieces,
            @PositiveOrZero(message = "{validation.number.nonNegative}")
            @Digits(integer = 9, fraction = 4, message = "{validation.number.digits}") BigDecimal weight,
            @PositiveOrZero(message = "{validation.number.nonNegative}")
            @Digits(integer = 9, fraction = 4, message = "{validation.number.digits}") BigDecimal rate,
            BigDecimal ignoredValue) {
    }

    /** The amount is typed only for a head without a predefined rate; for a head with one the server calculates pieces x rate. */
    public record ExpenseRequest(
            @NotNull(message = "{validation.expenseHead.required}") Long expenseHeadId,
            @PositiveOrZero(message = "{validation.number.nonNegative}")
            @Digits(integer = 9, fraction = 4, message = "{validation.number.digits}") BigDecimal amount) {
    }

    public record PurchaserResponse(int seq, MasterRef buyer, String haste, Integer pieces, BigDecimal weight, BigDecimal rate,
                                    BigDecimal value, boolean commissionApplies, BigDecimal commissionPercent,
                                    BigDecimal commissionAmount, BigDecimal enteredPurchaseValue) {
    }

    /** Sums of what the purchasers of a lot were entered with, to compare with the lot at review. Never written back. */
    public record AllocationSummary(int purchaserCount, long pieces, BigDecimal weight, BigDecimal value, BigDecimal commission) {
    }

    public record LotResponse(int lineNo, MasterRef item, Integer pieces, BigDecimal weight, BigDecimal rate,
                              MasterRef buyer, String marka, BigDecimal lotValue,
                              List<PurchaserResponse> purchasers, AllocationSummary allocation) {
    }

    public record ExpenseResponse(int seq, MasterRef expenseHead, BigDecimal amount, BigDecimal quantity, BigDecimal rate) {
    }

    /**
     * Plain sums and one difference of amounts the operator typed. Not a business formula: no rate, unit,
     * commission rule or rounding is applied, and farmer side and buyer side are never added together.
     */
    public record EnteredTotals(String basis, FarmerSide farmerSide, BuyerSide buyerSide) {
        public static final String SUM_OF_ENTERED = "SUM_OF_ENTERED";
        /** The amounts were calculated by BillCalculator; net is rounded to a whole rupee. */
        public static final String CALCULATED = "CALCULATED";

        /** gross = sum of entered lot values; net = gross - total of entered expenses. */
        public record FarmerSide(BigDecimal grossOfEnteredLotValues, BigDecimal totalOfEnteredExpenses,
                                 BigDecimal netOfEnteredAmounts, int lotsWithoutEnteredValue) {
        }

        public record BuyerSide(BigDecimal enteredValue, BigDecimal enteredCommission, BigDecimal enteredPurchaseValue,
                                int purchaserRowsWithoutEnteredValue) {
        }
    }

    public record BillResponse(Long id, Integer billNo, LocalDate billDate, Integer financialYear, String financialYearLabel,
                               MasterRef farmer, MasterRef village, String vehicleNo, String transport, Boolean bhadaPaid,
                               BigDecimal bhadaAmount, BillType billType, String panjiyanNo, String bookNo, String receiptNo,
                               String entryNo, String wsNo, CalculationMode calculationStatus, String ruleSetId,
                               Instant createdAt, List<LotResponse> lots, List<ExpenseResponse> expenses,
                               EnteredTotals totals, FirmProfile firm, CalculationResult calculation) {
    }

    /** Search row with the farmer-side figures of the bill (gross of lot values, expenses, net). */
    public record BillSummary(Long id, Integer billNo, LocalDate billDate, Integer financialYear, MasterRef farmer,
                              MasterRef village, String vehicleNo, int lotCount, CalculationMode calculationStatus,
                              BigDecimal gross, BigDecimal expenses, BigDecimal net) {
    }

    public record NextNumber(int expectedNext, boolean startLocked) {
    }

    public record NumberingStart(@NotNull(message = "{validation.billNumber.start}") Integer nextNumber) {
    }

    public record AppConfig(boolean demoCalculationEnabled) {
    }
}
