"use client";

import { useState } from "react";
import { expenseAmount, lotValue, purchaserCommission, purchaserTotal, purchaserValue } from "@/lib/billDraft";
import { nameOf } from "@/lib/format";
import { BillChecks, BillTotalsBar } from "../BillSidebar";
import { DeskAlerts, DeskHeader, DetailsPanel, ExpensesPanel, itemNameOf, show } from "../DeskParts";
import type { LayoutInfo } from "../layouts";
import type { Desk } from "../useBillDesk";
import { LotCardsList } from "./LayoutLotCards";

const STEP_KEYS = ["bill.section.header", "bill.lotsTitle", "bill.expensesTitle", "bill.section.review"] as const;
const stepOf = (key: string): number => (key.startsWith("bill.err.lot") || key.startsWith("bill.err.purchaser") ? 1 : key.startsWith("bill.err.expense") ? 2 : 0);

/** The review step: the bill as it will be saved, the figures of the parties, and what is still missing. */
function ReviewStep({ desk }: { desk: Desk }) {
  const { t, lang } = desk;
  const headName = (id: string) => nameOf((desk.heads ?? []).find((h) => String(h.id) === id) ?? null, lang);
  return (
    <div className="review">
      <section className="card2">
        <header className="card2-head"><h2>{t("bill.viewHeader")}</h2></header>
        <dl className="kv">
          <div><dt>{t("bill.date")}</dt><dd>{desk.draft.billDate || "—"}</dd></div>
          <div><dt>{t("bill.farmer")}</dt><dd>{desk.farmerKnown ? nameOf(desk.farmerKnown, lang) : "—"}</dd></div>
          <div><dt>{t("bill.village")}</dt><dd>{desk.villageKnown ? nameOf(desk.villageKnown, lang) : "—"}</dd></div>
          <div><dt>{t("bill.vehicleNo")}</dt><dd>{desk.draft.vehicleNo || "—"}</dd></div>
          <div><dt>{t("bill.bhadaPaid")}</dt><dd>{desk.draft.bhadaPaid === "yes" ? `${t("bill.yes")}${desk.draft.bhadaAmount ? ` (${desk.draft.bhadaAmount})` : ""}` : t("bill.no")}</dd></div>
          <div><dt>{t("bill.billType")}</dt><dd>{desk.draft.billType === "CASH" ? t("bill.billType.cash") : t("bill.billType.credit")}</dd></div>
        </dl>
      </section>
      <section className="card2">
        <header className="card2-head"><h2>{t("bill.lotsTitle")}</h2></header>
        <table className="rv">
          <thead><tr><th>#</th><th>{t("bill.item")}</th><th className="num">{t("bill.pieces")}</th><th className="num">{t("bill.weight")}</th><th className="num">{t("bill.rate")}</th><th className="num">{t("bill.col.value")}</th></tr></thead>
          <tbody>
            {desk.draft.lots.map((l, i) => (
              <tr key={l.key}><td>{i + 1}</td><td>{itemNameOf(desk, l.itemId)}</td><td className="num">{l.pieces || "—"}</td><td className="num">{l.weight || "—"}</td><td className="num">{l.rate || "—"}</td><td className="num strong">{show(lotValue(l))}</td></tr>
            ))}
          </tbody>
        </table>
      </section>
      <section className="card2">
        <header className="card2-head"><h2>{t("bill.expensesTitle")}</h2></header>
        <table className="rv">
          <thead><tr><th>{t("bill.expenseHead")}</th><th className="num">{t("bill.amount")}</th></tr></thead>
          <tbody>
            {desk.draft.expenses.filter((e) => e.expenseHeadId !== "").map((e) => (
              <tr key={e.key}><td>{headName(e.expenseHeadId)}</td><td className="num strong">{show(expenseAmount(e, desk.rates, desk.pieces))}</td></tr>
            ))}
            {desk.bhadaAmount !== null && <tr><td>{desk.bhadaHead ? nameOf(desk.bhadaHead, lang) : t("bill.bhadaHeadMissing")} <span className="auto-tag">{t("bill.autoFromBhada")}</span></td><td className="num strong">{desk.bhadaAmount}</td></tr>}
          </tbody>
        </table>
      </section>
      <section className="card2">
        <header className="card2-head"><h2>{t("bill.buyerSideDetails")}</h2></header>
        <table className="rv">
          <thead><tr><th>#</th><th>{t("bill.col.purchaser")}</th><th className="num">{t("bill.weight")}</th><th className="num">{t("bill.col.value")}</th><th className="num">{t("bill.col.commission")}</th><th className="num">{t("bill.col.total")}</th></tr></thead>
          <tbody>
            {desk.draft.lots.flatMap((l, i) => l.purchasers.map((p) => (
              <tr key={p.key}>
                <td>{i + 1}</td><td>{desk.buyerKnown[p.buyerId] ? nameOf(desk.buyerKnown[p.buyerId], lang) : p.buyerId ? "…" : "—"}</td><td className="num">{p.weight || "—"}</td>
                <td className="num">{show(purchaserValue(p))}</td><td className="num">{show(purchaserCommission(p))}</td><td className="num strong">{show(purchaserTotal(p))}</td>
              </tr>
            )))}
          </tbody>
        </table>
      </section>
    </div>
  );
}

/**
 * Layout 3: guided steps. One step at a time with large controls: bill details, lots and parties, expenses, then a review with Save. The
 * totals bar stays at the bottom the whole time. Back and Next never lose anything: the whole bill is one draft.
 */
export function LayoutSteps({ desk, layout }: { desk: Desk; layout: LayoutInfo }) {
  const { t } = desk;
  const [step, setStep] = useState(0);
  const problemSteps = new Set(desk.problems.map((p) => stepOf(p.key)));
  const last = step === STEP_KEYS.length - 1;   // Save appears in the sticky header on the last step, where it can never hide below the page
  return (
    <>
      <DeskHeader desk={desk} layout={layout} save={last} />
      <DeskAlerts desk={desk} />
      <ol className="stepper" aria-label="Steps" data-testid="stepper">
        {STEP_KEYS.map((k, i) => {
          const state = i === step ? "current" : i < step ? (problemSteps.has(i) ? "attention" : "done") : "todo";
          return (
            <li key={k}>
              <button type="button" className={`step ${state}`} aria-current={i === step ? "step" : undefined} onClick={() => setStep(i)} data-testid={`step-${i + 1}`}>
                <span className="step-no">{state === "done" ? "✓" : state === "attention" ? "!" : i + 1}</span>
                <span className="step-name">{t(k)}</span>
              </button>
            </li>
          );
        })}
      </ol>
      <div className="step-body" data-step={step + 1}>
        {step === 0 && <DetailsPanel desk={desk} variant="form" />}
        {step === 1 && <LotCardsList desk={desk} density="big" />}
        {step === 2 && <ExpensesPanel desk={desk} />}
        {step === 3 && (
          <>
            <ReviewStep desk={desk} />
            <BillChecks problems={desk.problemTexts} warnings={desk.warnings} />
          </>
        )}
      </div>
      <div className="step-nav">
        <button type="button" className="secondary" disabled={step === 0} onClick={() => setStep(step - 1)} data-testid="step-back">← Back</button>
        <span className="muted">{step + 1} / {STEP_KEYS.length}</span>
        {last
          ? <span className="muted">{t("bill.save")} ↑</span>
          : <button type="button" onClick={() => setStep(step + 1)} data-testid="step-next">Next →</button>}
      </div>
      <BillTotalsBar totals={desk.totals} problemCount={desk.problems.length} />
    </>
  );
}
