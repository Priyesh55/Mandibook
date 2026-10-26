package com.mandi.billing.aadat;

import java.time.LocalDate;
import java.time.Month;

/** The financial year runs from 1 April to 31 March (owner decision 2026-10-24). A year is identified by the year it starts in. */
public final class FinancialYear {

    private FinancialYear() {
    }

    public static int startYearOf(LocalDate date) {
        return date.getMonth().getValue() >= Month.APRIL.getValue() ? date.getYear() : date.getYear() - 1;
    }

    public static LocalDate start(int startYear) {
        return LocalDate.of(startYear, Month.APRIL, 1);
    }

    public static LocalDate end(int startYear) {
        return LocalDate.of(startYear + 1, Month.MARCH, 31);
    }

    public static boolean contains(int startYear, LocalDate date) {
        return startYearOf(date) == startYear;
    }

    /** 2026 becomes "2026-27". */
    public static String label(int startYear) {
        return startYear + "-" + String.format("%02d", (startYear + 1) % 100);
    }
}
