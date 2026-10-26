"use client";

import type { AllocationNote } from "@/lib/billDraft";
import { useI18n } from "@/lib/i18n";
import { fill } from "@/lib/text";
import type { TotalsView } from "./TotalsBlock";

interface ChecksProps {
  problems: string[];
  warnings: { lot: number; note: AllocationNote }[];
}

const SHOWN = 5;

/**
 * What still needs fixing and what is only worth a look, as a short card beside the expenses. It is not shown at all while there is
 * nothing to say, so a complete bill leaves the space to the work.
 */
export function BillChecks({ problems, warnings }: ChecksProps) {
  const { t } = useI18n();
  if (problems.length === 0 && warnings.length === 0) return null;
  return (
    <section className="card2 checks" id="sec-checks" aria-label={t("bill.section.review")} data-testid="section-review">
      {problems.length > 0 && (
        <div className="note error-note" role="status" data-testid="review-problems">
          <strong>{t("bill.errorsTitle")}</strong>
          <ul>
            {problems.slice(0, SHOWN).map((p, i) => <li key={i}>{p}</li>)}
            {problems.length > SHOWN && <li>{fill(t("bill.problemsMore"), { n: problems.length - SHOWN })}</li>}
          </ul>
        </div>
      )}
      {warnings.length > 0 && (
        <div className="note warn" role="status" data-testid="review-warnings">
          <strong>{t("bill.warnings")}</strong>
          <ul>
            {warnings.map((w, i) => (
              <li key={i}>
                {fill(t("bill.lotLabel"), { n: w.lot })}:{" "}
                {w.note.kind === "noPurchasers" && t("bill.noteNoPurchasers")}
                {w.note.kind === "pieces" && fill(t("bill.notePieces"), { entered: w.note.entered ?? "", lot: w.note.lot ?? "" })}
                {w.note.kind === "weight" && fill(t("bill.noteWeight"), { entered: w.note.entered ?? "", lot: w.note.lot ?? "" })}
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}

/**
 * The totals of the bill as one bar at the bottom of the desk: the farmer side first and large (gross, expenses, net), the buyer
 * side beside it (purchase value, 5 percent commission, their sum), and the verdict on the right. The two sides are never added together.
 */
export function BillTotalsBar({ totals, problemCount }: { totals: TotalsView; problemCount: number }) {
  const { t } = useI18n();
  return (
    <div className="totalsbar" id="sec-review" role="region" aria-label={t("bill.summary")} data-testid="bill-summary">
      <div className="tb-group farmer" data-testid="bill-summary-farmer">
        <span className="tb-title">{t("bill.farmerSide")}</span>
        <dl>
          <div><dt>{t("bill.gross.short")}</dt><dd data-testid="total-gross">{totals.gross}</dd></div>
          <div><dt>{t("bill.expenses.short")}</dt><dd data-testid="total-expenses">{totals.expenses}</dd></div>
          <div className="net"><dt>{t("bill.net")}</dt><dd data-testid="total-net">{totals.net}</dd></div>
        </dl>
      </div>
      <span className="tb-divider" aria-hidden />
      <div className="tb-group buyer" data-testid="bill-summary-buyer">
        <span className="tb-title">{t("bill.buyerSide")}</span>
        <dl>
          <div><dt>{t("bill.buyerValue.short")}</dt><dd data-testid="total-buyer-value">{totals.buyerValue}</dd></div>
          <div><dt>{t("bill.buyerCommission.short")}</dt><dd data-testid="total-buyer-commission">{totals.buyerCommission}</dd></div>
          <div><dt>{t("bill.buyerTotal.short")}</dt><dd data-testid="total-buyer-purchase">{totals.buyerPurchaseValue}</dd></div>
        </dl>
      </div>
      <div className="tb-status">
        {totals.lotsWithoutValue > 0 && <span className="chip attention" data-testid="lots-without-value">{fill(t("bill.lotsWithoutValue"), { n: totals.lotsWithoutValue })}</span>}
        {problemCount === 0
          ? <span className="chip ok" data-testid="review-ready">✓ {t("bill.readyShort")}</span>
          : <span className="chip attention" data-testid="review-count">{fill(t("bill.problemCount"), { n: problemCount })}</span>}
      </div>
    </div>
  );
}
