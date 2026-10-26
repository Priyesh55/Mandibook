"use client";

import { allocationNotes } from "@/lib/billDraft";
import { fill } from "@/lib/text";
import { BillChecks, BillTotalsBar } from "../BillSidebar";
import { DeskAlerts, DeskHeader, DetailsPanel, ExpensesPanel, itemNameOf, LotFields, PartiesPanel } from "../DeskParts";
import type { LayoutInfo } from "../layouts";
import type { Desk } from "../useBillDesk";

/** Every lot as a card with its own parties inside. Used by layouts 2, 3 and 6. */
export function LotCardsList({ desk, density = "normal" }: { desk: Desk; density?: "normal" | "big" | "compact" }) {
  const { t } = desk;
  return (
    <div className={`lotcards ${density}`}>
      {desk.draft.lots.map((l, idx) => {
        const current = idx === desk.selectedIndex;
        const warn = allocationNotes(l).length > 0;
        return (
          <article
            key={l.key} className={`lotcard${current ? " current" : ""}`} data-testid="lot-row" aria-current={current ? "true" : undefined}
            onFocusCapture={() => { if (!current) desk.setSelected(idx); }}
          >
            <header className="lotcard-head">
              <button type="button" className={`lot-pick${current ? " current" : ""}`} aria-current={current ? "true" : undefined} onClick={() => desk.setSelected(idx)} title={t("bill.pickLot")} data-testid="lot-tab">
                {idx + 1}{warn && <span className="dot" aria-label={t("bill.warnings")} />}
              </button>
              <h2>{fill(t("bill.lotLabel"), { n: idx + 1 })} <span className="sub">{itemNameOf(desk, l.itemId)}</span></h2>
              <div className="row-actions">
                <button type="button" className="icon-button" onClick={() => desk.duplicateLot(idx)} aria-label={`${t("bill.duplicateLot")} ${idx + 1}`} title={t("bill.duplicateLot")} data-testid="duplicate-lot">⧉</button>
                <button type="button" className="icon-button danger-icon" disabled={desk.draft.lots.length === 1} onClick={() => desk.requestRemoveLot(idx)} aria-label={`${t("bill.removeLot")} ${idx + 1}`} title={t("bill.removeLot")} data-testid="lot-remove">✕</button>
              </div>
            </header>
            <LotFields desk={desk} index={idx} />
            <PartiesPanel desk={desk} lotIndex={idx} />
          </article>
        );
      })}
      <button type="button" className="lotcard add" onClick={desk.addLot} data-testid="add-lot">+ {t("bill.addLot")}</button>
    </div>
  );
}

/** Layout 2: the classic frame (details strip, expenses on the right, totals bar), but the middle section is a stack of lot cards, each with its parties inside, so every lot and every party is visible at once. */
export function LayoutLotCards({ desk, layout }: { desk: Desk; layout: LayoutInfo }) {
  return (
    <>
      <DeskHeader desk={desk} layout={layout} />
      <DeskAlerts desk={desk} />
      <DetailsPanel desk={desk} variant="strip" />
      <div className="desk2">
        <div className="desk2-main"><LotCardsList desk={desk} /></div>
        <div className="desk2-side">
          <ExpensesPanel desk={desk} />
          <BillChecks problems={desk.attempted ? desk.problemTexts : []} warnings={desk.warnings} />
        </div>
      </div>
      <BillTotalsBar totals={desk.totals} problemCount={desk.problems.length} />
    </>
  );
}
