"use client";

import { expenseAmount, lotValue } from "@/lib/billDraft";
import { nameOf } from "@/lib/format";
import { fill } from "@/lib/text";
import { BillChecks } from "../BillSidebar";
import { DeskAlerts, DeskHeader, DetailsPanel, ExpensesPanel, itemNameOf, show } from "../DeskParts";
import type { LayoutInfo } from "../layouts";
import type { Desk } from "../useBillDesk";
import { LotCardsList } from "./LayoutLotCards";

/** The farmer bill as it will look, built from the draft while you type (never saved by this panel; Save is in the header). */
function LiveBill({ desk }: { desk: Desk }) {
  const { t, lang } = desk;
  const headName = (id: string) => nameOf((desk.heads ?? []).find((h) => String(h.id) === id) ?? null, lang);
  const rows = desk.draft.expenses.filter((e) => e.expenseHeadId !== "");
  return (
    <article className="paper" aria-label={t("print.title")} data-testid="live-bill">
      <h2 className="paper-title">{t("print.title")}</h2>
      <p className="paper-sub">{t("bill.numberOnSave")}</p>
      <dl className="paper-meta">
        <div><dt>{t("print.date")}</dt><dd>{desk.draft.billDate || "—"}</dd></div>
        <div><dt>{t("print.farmer")}</dt><dd><b>{desk.farmerKnown ? nameOf(desk.farmerKnown, lang) : "—"}</b></dd></div>
        <div><dt>{t("print.village")}</dt><dd>{desk.villageKnown ? nameOf(desk.villageKnown, lang) : "—"}</dd></div>
        <div><dt>{t("bill.vehicleNo")}</dt><dd>{desk.draft.vehicleNo || "—"}</dd></div>
        <div><dt>{t("bill.billType")}</dt><dd>{desk.draft.billType === "CASH" ? t("bill.billType.cash") : t("bill.billType.credit")}</dd></div>
        <div><dt>{t("bill.bhadaPaid")}</dt><dd>{desk.draft.bhadaPaid === "yes" ? `${t("bill.yes")}${desk.draft.bhadaAmount ? ` (${desk.draft.bhadaAmount})` : ""}` : t("bill.no")}</dd></div>
      </dl>
      <table className="paper-table">
        <thead><tr><th>{t("bill.item")}</th><th className="num">{t("bill.weight")}</th><th className="num">{t("bill.rate")}</th><th className="num">{t("bill.col.value")}</th></tr></thead>
        <tbody>
          {desk.draft.lots.map((l) => (
            <tr key={l.key}><td>{itemNameOf(desk, l.itemId)}{l.marka ? ` · ${l.marka}` : ""}</td><td className="num">{l.weight || "—"}</td><td className="num">{l.rate || "—"}</td><td className="num"><b>{show(lotValue(l))}</b></td></tr>
          ))}
        </tbody>
      </table>
      {(rows.length > 0 || desk.bhadaAmount !== null) && (
        <table className="paper-table">
          <thead><tr><th>{t("print.expensesTitle")}</th><th className="num">{t("bill.amount")}</th></tr></thead>
          <tbody>
            {rows.map((e) => <tr key={e.key}><td>{headName(e.expenseHeadId)}</td><td className="num">{show(expenseAmount(e, desk.rates, desk.pieces))}</td></tr>)}
            {desk.bhadaAmount !== null && <tr><td>{desk.bhadaHead ? nameOf(desk.bhadaHead, lang) : t("bill.bhadaHeadMissing")}</td><td className="num">{desk.bhadaAmount}</td></tr>}
          </tbody>
        </table>
      )}
      <dl className="paper-sums">
        <div><dt>{t("print.gross")}</dt><dd data-testid="preview-gross">{desk.totals.gross}</dd></div>
        <div><dt>{t("print.totalExpenses")}</dt><dd data-testid="preview-expenses">{desk.totals.expenses}</dd></div>
        <div className="net"><dt>{t("print.net")}</dt><dd data-testid="preview-net">{desk.totals.net}</dd></div>
      </dl>
      <p className="paper-foot" data-testid="bill-summary">
        {t("bill.buyerSide")} ({t("bill.notOnBill")}): {t("bill.buyerValue.short")} <b data-testid="total-buyer-value">{desk.totals.buyerValue}</b> ·{" "}
        {t("bill.buyerCommission.short")} <b data-testid="total-buyer-commission">{desk.totals.buyerCommission}</b>
      </p>
      <p className="paper-ready">
        {desk.problems.length === 0 ? <span className="chip ok" data-testid="review-ready">✓ {t("bill.readyShort")}</span> : <span className="chip attention" data-testid="review-count">{fill(t("bill.problemCount"), { n: desk.problems.length })}</span>}
      </p>
    </article>
  );
}

/**
 * Layout 6: live bill. The details as a vertical form on the left, the lots (cards with their parties) and the expenses in the middle, and
 * on the right the farmer bill as it will look, updating while you type. There is no totals bar: the paper carries the totals.
 */
export function LayoutPreview({ desk, layout }: { desk: Desk; layout: LayoutInfo }) {
  return (
    <>
      <DeskHeader desk={desk} layout={layout} />
      <DeskAlerts desk={desk} />
      <div className="live">
        <div className="live-left"><DetailsPanel desk={desk} variant="column" /></div>
        <div className="live-mid">
          <LotCardsList desk={desk} density="compact" />
          <ExpensesPanel desk={desk} />
        </div>
        <div className="live-right">
          <LiveBill desk={desk} />
          <BillChecks problems={desk.attempted ? desk.problemTexts : []} warnings={desk.warnings} />
        </div>
      </div>
    </>
  );
}
