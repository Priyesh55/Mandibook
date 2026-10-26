"use client";

import { useI18n } from "@/lib/i18n";
import { fill } from "@/lib/text";

export interface TotalsView {
  gross: string;
  expenses: string;
  net: string;
  lotsWithoutValue: number;
  buyerValue: string;
  buyerCommission: string;
  buyerPurchaseValue: string;
  purchaserRowsWithoutValue: number;
}

/**
 * The farmer side and the buyer side of a bill, each in its own group and never added together. `calculated` is every bill saved
 * with the owner-confirmed rules (weight x rate, 5 percent commission charged to the buyer); an older bill keeps its typed amounts
 * and says so. `stacked` is the narrow summary column of the billing desk; the default is the two-column form of the saved bill.
 */
export function TotalsBlock({ totals, testId = "totals", stacked = false, calculated = true, sides = "both" }: { totals: TotalsView; testId?: string; stacked?: boolean; calculated?: boolean; sides?: "both" | "farmer" | "buyer" }) {
  const { t } = useI18n();
  return (
    <div className={`totals${stacked ? " stacked" : ""}${sides !== "both" ? " single" : ""}`} data-testid={testId}>
      {!stacked && !calculated && <span className="badge entered">{t("bill.enteredLabel")}</span>}
      {sides !== "buyer" && <div className="totals-side" data-testid={`${testId}-farmer`}>
        <h4>{t("bill.farmerSide")}</h4>
        <dl>
          <div><dt>{stacked ? t("bill.gross.short") : t("bill.gross")}</dt><dd data-testid="total-gross">{totals.gross}</dd></div>
          <div><dt>{stacked ? t("bill.expenses.short") : t("bill.totalExpenses")}</dt><dd data-testid="total-expenses">{totals.expenses}</dd></div>
          <div className="net"><dt>{stacked ? t("bill.net.short") : t("bill.net")}</dt><dd data-testid="total-net">{totals.net}</dd></div>
        </dl>
        {totals.lotsWithoutValue > 0 && <p className="note warn" data-testid="lots-without-value">{fill(t("bill.lotsWithoutValue"), { n: totals.lotsWithoutValue })}</p>}
      </div>}
      {sides !== "farmer" && <div className="totals-side buyer" data-testid={`${testId}-buyer`}>
        <h4>{t("bill.buyerSide")}</h4>
        <dl>
          <div><dt>{stacked ? t("bill.buyerValue.short") : t("bill.buyerEnteredValue")}</dt><dd data-testid="total-buyer-value">{totals.buyerValue}</dd></div>
          <div><dt>{stacked ? t("bill.buyerCommission.short") : t("bill.buyerCommissionTotal")}</dt><dd data-testid="total-buyer-commission">{totals.buyerCommission}</dd></div>
          <div><dt>{stacked ? t("bill.buyerTotal.short") : t("bill.buyerPurchaseTotal")}</dt><dd data-testid="total-buyer-purchase">{totals.buyerPurchaseValue}</dd></div>
        </dl>
        {totals.purchaserRowsWithoutValue > 0 && <p className="note warn">{fill(t("bill.rowsWithoutValue"), { n: totals.purchaserRowsWithoutValue })}</p>}
      </div>}
      {!stacked && sides !== "buyer" && <p className="hint">{calculated ? t("bill.calcNote") : `${t("bill.sidesNote")} ${t("bill.enteredNote")}`}</p>}
    </div>
  );
}
