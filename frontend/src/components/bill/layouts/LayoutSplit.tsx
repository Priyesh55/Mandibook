"use client";

import { expenseAmount, lotValue } from "@/lib/billDraft";
import { nameOf } from "@/lib/format";
import { fill } from "@/lib/text";
import { BillChecks, BillTotalsBar } from "../BillSidebar";
import { DeskAlerts, DeskHeader, DetailsPanel, itemNameOf, LotFields, PartiesPanel, show } from "../DeskParts";
import type { LayoutInfo } from "../layouts";
import type { Desk } from "../useBillDesk";

/** Every expense head of the firm as a tile: type the amount, or switch a head with a rate on. The Motor Bhada tile follows the bill details. */
function ExpenseTiles({ desk }: { desk: Desk }) {
  const { t, lang } = desk;
  const bhadaId = desk.bhadaHead ? String(desk.bhadaHead.id) : null;
  return (
    <section className="card2" aria-labelledby="sec-tiles" data-testid="section-expenses">
      <header className="card2-head"><h2 id="sec-tiles">{t("bill.expensesTitle")}</h2></header>
      <div className="tiles">
        {(desk.heads ?? []).filter((h) => String(h.id) !== bhadaId).map((h) => {
          const id = String(h.id);
          const row = desk.draft.expenses.find((x) => x.expenseHeadId === id);
          const rate = desk.rates[id];
          const on = row !== undefined;
          return (
            <div key={h.id} className={`tile${on ? " on" : ""}`} data-testid="expense-tile">
              <div className="tile-name">{nameOf(h, lang)}</div>
              <div className="tile-sub">{rate !== undefined ? fill(t("bill.perPiece"), { rate }) : t("bill.col.expenseRate") + " —"}</div>
              {rate !== undefined ? (
                <button type="button" className={`tile-toggle${on ? " on" : ""}`} aria-pressed={on} onClick={() => desk.setHeadAmount(id, on ? null : "")} data-testid="expense-tile-toggle">
                  {on ? <output data-testid="expense-tile-amount">{show(expenseAmount(row, desk.rates, desk.pieces))}</output> : "+"}
                </button>
              ) : (
                <input className="num" type="number" min="0" step="any" value={row?.amount ?? ""} placeholder="0" aria-label={`${nameOf(h, lang)} ${t("bill.amount")}`}
                  onChange={(e) => desk.setHeadAmount(id, e.target.value === "" ? null : e.target.value)} data-testid="expense-tile-input" />
              )}
            </div>
          );
        })}
        {desk.bhadaAmount !== null && (
          <div className="tile on auto" data-testid="expense-tile">
            <div className="tile-name">{desk.bhadaHead ? nameOf(desk.bhadaHead, lang) : t("bill.bhadaHeadMissing")}</div>
            <div className="tile-sub">{t("bill.autoFromBhada")}</div>
            <output className="tile-auto" data-testid="expense-amount-bhada">{desk.bhadaAmount}</output>
          </div>
        )}
      </div>
    </section>
  );
}

/**
 * Layout 5: split view. A list of lots on the left, the selected lot in the middle with its parties as cards, and on the right the bill
 * details as a vertical form and every expense head as a tile (nothing to add or remove).
 */
export function LayoutSplit({ desk, layout }: { desk: Desk; layout: LayoutInfo }) {
  const { t } = desk;
  const i = desk.selectedIndex;
  const lot = desk.draft.lots[i];
  return (
    <>
      <DeskHeader desk={desk} layout={layout} />
      <DeskAlerts desk={desk} />
      <div className="split">
        <aside className="split-lots card2" aria-label={t("bill.lotsTitle")} data-testid="section-lots">
          <header className="card2-head"><h2>{t("bill.lotsTitle")}</h2></header>
          <ul className="lotlist">
            {desk.draft.lots.map((l, idx) => (
              <li key={l.key}>
                <button type="button" className={`lotitem${idx === i ? " current" : ""}`} aria-current={idx === i ? "true" : undefined} onClick={() => desk.setSelected(idx)} data-testid="lot-tab">
                  <span className="li-no">{idx + 1}</span>
                  <span className="li-main">
                    <b>{itemNameOf(desk, l.itemId)}</b>
                    <small>{l.weight || "—"} kg · {l.rate || "—"} · {l.purchasers.length} {t("bill.purchasersShort")}</small>
                  </span>
                  <span className="li-value">{show(lotValue(l))}</span>
                </button>
              </li>
            ))}
          </ul>
          <button type="button" className="secondary" onClick={desk.addLot} data-testid="add-lot">+ {t("bill.addLot")}</button>
        </aside>

        <section className="split-detail" data-testid="lot-row" aria-current="true">
          <div className="card2">
            <header className="card2-head">
              <h2>{fill(t("bill.lotLabel"), { n: i + 1 })} <span className="sub">{itemNameOf(desk, lot.itemId)}</span></h2>
              <div className="row-actions">
                <button type="button" className="icon-button" onClick={() => desk.duplicateLot(i)} aria-label={`${t("bill.duplicateLot")} ${i + 1}`} title={t("bill.duplicateLot")} data-testid="duplicate-lot">⧉</button>
                <button type="button" className="icon-button danger-icon" disabled={desk.draft.lots.length === 1} onClick={() => desk.requestRemoveLot(i)} aria-label={`${t("bill.removeLot")} ${i + 1}`} title={t("bill.removeLot")} data-testid="lot-remove">✕</button>
              </div>
            </header>
            <LotFields desk={desk} index={i} cols="wide" />
          </div>
          <div className="card2">
            <PartiesPanel desk={desk} lotIndex={i} view="cards" />
          </div>
        </section>

        <div className="split-side">
          <DetailsPanel desk={desk} variant="column" />
          <ExpenseTiles desk={desk} />
          <BillChecks problems={desk.attempted ? desk.problemTexts : []} warnings={desk.warnings} />
        </div>
      </div>
      <BillTotalsBar totals={desk.totals} problemCount={desk.problems.length} />
    </>
  );
}
