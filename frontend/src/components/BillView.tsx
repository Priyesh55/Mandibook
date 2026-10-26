"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { api } from "@/lib/api";
import { formatAuditDetails } from "@/lib/auditFormat";
import { nameOf, num } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import { fill } from "@/lib/text";
import { useAsync } from "@/lib/useAsync";
import type { AuditEvent, BillResponse } from "@/lib/types";
import { CalculationNotice } from "./CalculationNotice";
import { SectionHead } from "./bill/SectionHead";
import { TotalsBlock } from "./bill/TotalsBlock";

/** The totals as the server computed them (the print page shows the very same figures). */
export function totalsOf(b: BillResponse) {
  return {
    gross: num(b.totals.farmerSide.grossOfEnteredLotValues),
    expenses: num(b.totals.farmerSide.totalOfEnteredExpenses),
    net: num(b.totals.farmerSide.netOfEnteredAmounts),
    lotsWithoutValue: b.totals.farmerSide.lotsWithoutEnteredValue,
    buyerValue: num(b.totals.buyerSide.enteredValue),
    buyerCommission: num(b.totals.buyerSide.enteredCommission),
    buyerPurchaseValue: num(b.totals.buyerSide.enteredPurchaseValue),
    purchaserRowsWithoutValue: b.totals.buyerSide.purchaserRowsWithoutEnteredValue,
  };
}

/**
 * The saved bill: the FARMER bill (details, lots, expenses, gross / expenses / net). The purchasers and the commission charged to
 * them belong to the buyer side and are folded away at the end; they are not part of the farmer bill or of its print.
 */
export function BillView({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const saved = useSearchParams().get("saved") === "1";
  const bill = useAsync(() => api<BillResponse>(`/aadat/bills/${id}`, lang), `${id}|${lang}`);
  const history = useAsync(() => api<AuditEvent[]>(`/audit?entityType=AADAT_BILL&entityId=${id}`, lang), `history|${id}`);

  if (bill.error) return <div className="error" role="alert">{bill.error}</div>;
  const b = bill.data;
  if (!b) return <p className="muted">{t("common.loading")}</p>;

  const demo = b.calculation.mode === "DEMO_UNVERIFIED";
  const calculated = b.totals.basis === "CALCULATED";
  const when = (iso: string) =>
    new Date(iso).toLocaleString(lang === "hi" ? "hi-IN" : "en-IN", { dateStyle: "medium", timeStyle: "short" });
  const label = b.billNo !== null ? String(b.billNo) : `#${b.id}`;
  const billType = b.billType === "CREDIT" ? t("bill.billType.credit") : b.billType === "CASH" ? t("bill.billType.cash") : "—";
  const bhada = b.bhadaPaid === null ? "—" : b.bhadaPaid ? `${t("bill.yes")}${b.bhadaAmount !== null ? ` (${num(b.bhadaAmount)})` : ""}` : t("bill.no");
  const purchasersExist = b.lots.some((l) => l.purchasers.length > 0 || l.buyer !== null);

  return (
    <div data-testid="bill-view">
      <div className="page-head">
        <h1>
          {t("bill.viewTitle")} <span data-testid="bill-ref">{label}</span>{" "}
          <span className={demo ? "badge demo" : "badge"} data-testid="view-status">{t(`calc.${b.calculationStatus}`)}</span>
        </h1>
        <div className="row no-print">
          <Link href={`/bills/${b.id}/print`} className="button" data-testid="open-print">{t("bill.print")}</Link>
          <Link href="/" className="button secondary">{t("common.back")}</Link>
        </div>
      </div>
      {saved && <div className="success" role="status" data-testid="saved-msg">{t("bill.saved")}{b.billNo !== null ? ` — ${t("bill.billNo")} ${b.billNo}` : ""}</div>}
      {!calculated && <CalculationNotice result={b.calculation} />}

      <section className="card sec">
        <SectionHead id="v-header" number={1} title={t("bill.viewHeader")} />
        <dl className="kv-grid">
          <div><dt>{t("bill.billNo")}</dt><dd data-testid="view-bill-no">{b.billNo ?? <span className="muted">{t("bill.noNumber")} · #{b.id}</span>}</dd></div>
          <div><dt>{t("bill.date")}</dt><dd data-testid="view-date">{b.billDate}</dd></div>
          <div><dt>{t("bill.farmer")}</dt><dd data-testid="view-farmer">{nameOf(b.farmer, lang)}</dd></div>
          <div><dt>{t("bill.village")}</dt><dd data-testid="view-village">{nameOf(b.village, lang)}</dd></div>
          <div><dt>{t("bill.vehicleNo")}</dt><dd data-testid="view-vehicle">{b.vehicleNo ?? "—"}</dd></div>
          <div><dt>{t("bill.transport")}</dt><dd data-testid="view-transport">{b.transport ?? "—"}</dd></div>
          <div><dt>{t("bill.bhadaPaid")}</dt><dd data-testid="view-bhada">{bhada}</dd></div>
          <div><dt>{t("bill.billType")}</dt><dd data-testid="view-bill-type">{billType}</dd></div>
          {b.panjiyanNo && <div><dt>{t("bill.panjiyan")}</dt><dd>{b.panjiyanNo}</dd></div>}
          {b.bookNo && <div><dt>{t("bill.bookNo")}</dt><dd>{b.bookNo}</dd></div>}
          {b.receiptNo && <div><dt>{t("bill.receiptNo")}</dt><dd>{b.receiptNo}</dd></div>}
          {b.entryNo && <div><dt>{t("bill.entryNo")}</dt><dd>{b.entryNo}</dd></div>}
          {b.wsNo && <div><dt>{t("bill.wsNo")}</dt><dd>{b.wsNo}</dd></div>}
        </dl>
      </section>

      <section className="card sec">
        <SectionHead id="v-lots" number={2} title={t("bill.lotsTitle")} />
        <div className="table-wrap">
          <table className="data-table" data-testid="view-lots">
            <thead>
              <tr>
                <th>#</th><th>{t("bill.item")}</th><th>{t("bill.marka")}</th><th className="num">{t("bill.pieces")}</th>
                <th className="num">{t("bill.weight")}</th><th className="num">{t("bill.rate")}</th>
                <th className="num">{t("bill.lotValue")}</th>
              </tr>
            </thead>
            <tbody>
              {b.lots.map((l) => (
                <tr key={l.lineNo} data-testid="view-lot-row">
                  <td>{l.lineNo}</td>
                  <td>{nameOf(l.item, lang)}</td>
                  <td>{l.marka ?? "—"}</td>
                  <td className="num">{num(l.pieces)}</td>
                  <td className="num">{num(l.weight)}</td>
                  <td className="num">{num(l.rate)}</td>
                  <td className="num" data-testid="view-lot-value">{num(l.lotValue)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      {b.expenses.length > 0 && (
        <section className="card sec">
          <SectionHead id="v-expenses" number={3} title={t("bill.expensesTitle")} />
          <div className="table-wrap">
            <table className="data-table" data-testid="view-expenses">
              <thead>
                <tr><th>{t("bill.expenseHead")}</th><th className="num">{t("bill.pieces")}</th><th className="num">{t("bill.col.expenseRate")}</th><th className="num">{t("bill.amount")}</th></tr>
              </thead>
              <tbody>
                {b.expenses.map((e) => (
                  <tr key={e.seq} data-testid="view-expense-row">
                    <td>{nameOf(e.expenseHead, lang)}</td>
                    <td className="num">{num(e.quantity)}</td>
                    <td className="num">{num(e.rate)}</td>
                    <td className="num">{num(e.amount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      <section className="card sec">
        <SectionHead id="v-totals" number={b.expenses.length > 0 ? 4 : 3} title={t("bill.summary")} />
        <TotalsBlock totals={totalsOf(b)} testId="view-totals" calculated={calculated} sides="farmer" />
        {!calculated && <p className="hint">{t("bill.fromEnteredNote")}</p>}
      </section>

      {purchasersExist && (
        <details className="card sec buyer-details" data-testid="view-buyer-side">
          <summary>{t("bill.buyerSideDetails")}</summary>
          {b.lots.map((l) => (
            <div key={l.lineNo} className="view-purchasers" data-testid="view-lot-purchasers">
              <h3>{fill(t("bill.purchasers"), { n: l.lineNo })}</h3>
              {l.purchasers.length === 0 ? (
                <p className="muted">{l.buyer ? `${t("bill.singleBuyer")}: ${nameOf(l.buyer, lang)}` : t("bill.noteNoPurchasers")}</p>
              ) : (
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>{t("bill.buyer")}</th><th>{t("bill.haste")}</th><th className="num">{t("bill.pieces")}</th>
                        <th className="num">{t("bill.weight")}</th><th className="num">{t("bill.rate")}</th><th className="num">{t("bill.value")}</th>
                        <th className="num">{t("bill.commissionPercent")}</th><th className="num">{t("bill.commissionAmount")}</th>
                        <th className="num">{t("bill.purchaseValue")}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {l.purchasers.map((p) => (
                        <tr key={p.seq} data-testid="view-purchaser-row">
                          <td>{nameOf(p.buyer, lang)}</td>
                          <td>{p.haste ?? "—"}</td>
                          <td className="num">{num(p.pieces)}</td>
                          <td className="num">{num(p.weight)}</td>
                          <td className="num">{num(p.rate)}</td>
                          <td className="num">{num(p.value)}</td>
                          <td className="num">{p.commissionApplies ? num(p.commissionPercent) : "—"}</td>
                          <td className="num">{p.commissionApplies ? num(p.commissionAmount) : "—"}</td>
                          <td className="num">{num(p.enteredPurchaseValue)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          ))}
          <TotalsBlock totals={totalsOf(b)} testId="view-buyer-totals" calculated={calculated} sides="buyer" />
        </details>
      )}

      <section className="card" data-testid="history-card">
        <h2>{t("bill.history")}</h2>
        <p className="hint">{t("bill.historyNote")}</p>
        <p className="hint" data-testid="readonly-note">{t("bill.readOnlyNote")}</p>
        <div className="table-wrap" style={{ marginTop: 8 }}>
          <table className="data-table" data-testid="history-table">
            <thead>
              <tr><th>{t("audit.when")}</th><th>{t("audit.action")}</th><th>{t("audit.details")}</th></tr>
            </thead>
            <tbody>
              {(history.data ?? []).map((e) => (
                <tr key={e.id} data-testid="history-row">
                  <td className="nowrap">{when(e.createdAt)}</td>
                  <td className="nowrap">{t(`audit.${e.action}`)}</td>
                  <td className="muted" data-testid="history-details">{formatAuditDetails(e.details, t)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {history.error && <div className="error">{history.error}</div>}
      </section>
    </div>
  );
}
