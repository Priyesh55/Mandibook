"use client";

import { useRouter } from "next/navigation";
import { useRef } from "react";
import { allocationNotes, lotValue, purchaserCommission, purchaserTotal, purchaserValue } from "@/lib/billDraft";
import { formatScaled, parseScaled, sumScaled } from "@/lib/decimal";
import { nameOf } from "@/lib/format";
import { fill } from "@/lib/text";
import { ConfirmDialog } from "../ConfirmDialog";
import { MasterDialog } from "../MasterDialog";
import { MasterPicker } from "../MasterPicker";
import { BillHeaderSection } from "./BillHeaderSection";
import { ExpensesSection } from "./ExpensesSection";
import { moveOnEnter } from "./LotsSection";
import { LAYOUTS, type LayoutInfo } from "./layouts";
import type { Desk } from "./useBillDesk";

export const show = (v: bigint | null) => (v === null ? "—" : formatScaled(v));

/** TEMPORARY: switches between the layouts of the bill entry without losing what was typed (the page stays mounted). */
export function LayoutSwitcher({ current }: { current: LayoutInfo }) {
  const router = useRouter();
  return (
    <nav className="layout-switch" aria-label="Layout (temporary)" data-testid="layout-switch">
      <span className="ls-label">Layout</span>
      {LAYOUTS.map((l) => (
        <button
          key={l.id} type="button" className={`ls-btn${l.id === current.id ? " current" : ""}`} aria-current={l.id === current.id ? "true" : undefined}
          title={`${l.id}. ${l.name}: ${l.note}`} data-testid={`layout-switch-${l.id}`}
          onClick={() => router.replace(`/bills/new?layout=${l.id}`, { scroll: false })}
        >
          {l.id}
        </button>
      ))}
    </nav>
  );
}

/** The sticky header of every layout: title, chips, the layout switcher and Cancel / Save. `save` false leaves Save to the layout (the guided steps). */
export function DeskHeader({ desk, layout, save = true }: { desk: Desk; layout: LayoutInfo; save?: boolean }) {
  const { t } = desk;
  return (
    <header className="desk-head" data-testid="bill-bar">
      <div className="head-title">
        <h1>{t("bill.newTitle")}</h1>
        <span className="chip draft" data-testid="bill-status">{t("bill.draft")}</span>
        <span className="chip" data-testid="bill-number-note">{t("bill.numberOnSave")}</span>
      </div>
      <LayoutSwitcher current={layout} />
      <div className="head-actions">
        <button type="button" className="ghost" onClick={desk.cancel} data-testid="bill-cancel">{t("common.cancel")}</button>
        {save && <button type="submit" disabled={desk.saving} data-testid="bill-save">{desk.saving ? t("bill.saving") : t("bill.save")}</button>}
      </div>
    </header>
  );
}

export function DeskAlerts({ desk }: { desk: Desk }) {
  const { t } = desk;
  return (
    <>
      {desk.truncated && <div className="error" role="alert" data-testid="list-truncated" style={{ marginBottom: 12 }}>{t("bill.listTruncated")}</div>}
      {desk.errors.length > 0 && (
        <div className="error" role="alert" data-testid="form-errors" style={{ marginBottom: 12 }}>
          {desk.errors.map((m, i) => <div key={i}>{m}</div>)}
        </div>
      )}
    </>
  );
}

/** Dialogs sit OUTSIDE the bill form: a form inside a form is invalid HTML and its submit would reach the bill. */
export function DeskDialogs({ desk }: { desk: Desk }) {
  const { t, pending, dlg } = desk;
  return (
    <>
      <ConfirmDialog
        open={pending !== null} title={dlg.title} message={dlg.text} confirmLabel={dlg.ok} cancelLabel={pending?.kind === "leave" ? t("bill.keepEditing") : t("common.cancel")}
        onConfirm={desk.confirmPending} onCancel={() => desk.setPending(null)} testId="remove-dialog"
      />
      <MasterDialog
        open={desk.dialog !== null} type={desk.dialog ?? "villages"}
        lockedVillage={desk.dialog === "farmers" ? desk.villageKnown : null}
        onClose={() => desk.setDialog(null)} onCreated={desk.created}
      />
    </>
  );
}

export function itemNameOf(desk: Desk, itemId: string): string {
  const m = (desk.items ?? []).find((x) => String(x.id) === itemId);
  return m ? nameOf(m, desk.lang) : desk.t("bill.lotNoItem");
}

/** The farmer-side fields of one lot with labels above them. Same test ids as the grid layouts. */
export function LotFields({ desk, index, cols = "auto" }: { desk: Desk; index: number; cols?: "auto" | "wide" }) {
  const { t, lang } = desk;
  const l = desk.draft.lots[index];
  return (
    <div className={`lotfields ${cols}`} onKeyDown={(e) => moveOnEnter(e)}>
      <label className="field lf-item">
        <span className="field-label">{t("bill.item")}</span>
        <select value={l.itemId} onChange={(e) => desk.updateLot(index, { itemId: e.target.value })} data-nav data-testid="lot-item" aria-label={`${t("bill.item")} ${index + 1}`}>
          <option value="">{t("common.select")}</option>
          {(desk.items ?? []).map((m) => <option key={m.id} value={m.id}>{nameOf(m, lang)}</option>)}
        </select>
      </label>
      <label className="field">
        <span className="field-label">{t("bill.pieces")}</span>
        <input className="num" type="number" min="0" step="1" value={l.pieces} onChange={(e) => desk.updateLot(index, { pieces: e.target.value })} data-nav data-testid="lot-pieces" aria-label={`${t("bill.pieces")} ${index + 1}`} />
      </label>
      <label className="field">
        <span className="field-label">{t("bill.weight")}</span>
        <input className="num" type="number" min="0" step="any" value={l.weight} onChange={(e) => desk.updateLot(index, { weight: e.target.value })} data-nav data-testid="lot-weight" aria-label={`${t("bill.weight")} ${index + 1}`} />
      </label>
      <label className="field">
        <span className="field-label">{t("bill.rate")}</span>
        <input className="num" type="number" min="0" step="any" value={l.rate} onChange={(e) => desk.updateLot(index, { rate: e.target.value })} data-nav data-testid="lot-rate" aria-label={`${t("bill.rate")} ${index + 1}`} />
      </label>
      <label className="field">
        <span className="field-label">{t("bill.marka")}</span>
        <input value={l.marka} maxLength={60} onChange={(e) => desk.updateLot(index, { marka: e.target.value })} data-nav data-testid="lot-marka" aria-label={`${t("bill.marka")} ${index + 1}`} />
      </label>
      <div className="field lf-value">
        <span className="field-label" title={t("bill.calcHint")}>{t("bill.col.value")}</span>
        <output className="bigvalue" data-testid="lot-value" title={t("bill.valueAuto")}>{show(lotValue(l))}</output>
      </div>
    </div>
  );
}

/** The party rows of one lot as a table (a party is what the bill used to call a purchaser). */
export function PartyTable({ desk, lotIndex }: { desk: Desk; lotIndex: number }) {
  const { t } = desk;
  const lot = desk.draft.lots[lotIndex];
  const i = lotIndex;
  const body = useRef<HTMLDivElement>(null);

  function onKey(e: React.KeyboardEvent<HTMLDivElement>) {
    const r = moveOnEnter(e);
    if (r?.last && lot.purchasers.length > 0 && lot.purchasers[lot.purchasers.length - 1].buyerId !== "") {
      desk.addPurchaser(i);
      requestAnimationFrame(() => {
        const rows = body.current?.querySelectorAll("[data-testid='purchaser-row']");
        rows?.[rows.length - 1]?.querySelector<HTMLElement>("[data-nav]")?.focus();
      });
    }
  }

  if (lot.purchasers.length === 0) return <p className="empty-state" data-testid="purchasers-empty">{t("bill.noPurchasersYet")}</p>;
  return (
    <div ref={body} onKeyDown={onKey}>
      <table className="pt" data-testid="purchaser-table">
        <colgroup>
          <col style={{ width: "21%" }} /><col style={{ width: "12%" }} /><col style={{ width: "8%" }} /><col style={{ width: "10%" }} /><col style={{ width: "10%" }} />
          <col style={{ width: "11%" }} /><col style={{ width: "10%" }} /><col style={{ width: "11%" }} /><col style={{ width: "76px" }} />
        </colgroup>
        <thead>
          <tr>
            <th scope="col">{t("bill.col.purchaser")}</th>
            <th scope="col" title={t("bill.haste")}>{t("bill.col.haste")}</th>
            <th scope="col" className="num">{t("bill.col.pcs")}</th>
            <th scope="col" className="num">{t("bill.weight")}</th>
            <th scope="col" className="num">{t("bill.rate")}</th>
            <th scope="col" className="num" title={t("bill.calcHint")}>{t("bill.col.value")}</th>
            <th scope="col" className="num" title={t("bill.calcNote")}>{t("bill.col.commission")}</th>
            <th scope="col" className="num">{t("bill.col.total")}</th>
            <th scope="col"><span className="sr-only">{t("common.actions")}</span></th>
          </tr>
        </thead>
        <tbody>
          {lot.purchasers.map((r, row) => (
            <tr key={r.key} data-testid="purchaser-row">
              <td className="cell-picker" data-label={t("bill.col.purchaser")}>
                <MasterPicker
                  type="buyers" value={r.buyerId} known={desk.buyerKnown[r.buyerId] ?? null}
                  onChange={(id, m) => { if (m) desk.setBuyerKnown((k) => ({ ...k, [String(m.id)]: m })); desk.updatePurchaser(i, row, { buyerId: id }); }}
                  nav testId="purchaser-buyer" ariaLabel={`${t("bill.buyer")} ${row + 1}`}
                />
              </td>
              <td data-label={t("bill.col.haste")}><input value={r.haste} maxLength={100} onChange={(e) => desk.updatePurchaser(i, row, { haste: e.target.value })} data-nav data-testid="purchaser-haste" aria-label={`${t("bill.haste")} ${row + 1}`} /></td>
              <td data-label={t("bill.col.pcs")}><input className="num" type="number" min="0" step="1" value={r.pieces} onChange={(e) => desk.updatePurchaser(i, row, { pieces: e.target.value })} data-nav data-testid="purchaser-pieces" aria-label={`${t("bill.pieces")} ${row + 1}`} /></td>
              <td data-label={t("bill.weight")}><input className="num" type="number" min="0" step="any" value={r.weight} onChange={(e) => desk.updatePurchaser(i, row, { weight: e.target.value })} data-nav data-testid="purchaser-weight" aria-label={`${t("bill.weight")} ${row + 1}`} /></td>
              <td data-label={t("bill.rate")}>
                <input
                  className="num" type="number" min="0" step="any" value={r.rate} onChange={(e) => desk.updatePurchaser(i, row, { rate: e.target.value })}
                  title={lot.rate ? fill(t("bill.sameAsLot"), { rate: lot.rate }) : undefined}
                  data-nav data-testid="purchaser-rate" aria-label={`${t("bill.rate")} ${row + 1}`}
                />
              </td>
              <td className="num calc" data-label={t("bill.col.value")}><output data-testid="purchaser-value">{show(purchaserValue(r))}</output></td>
              <td className="num calc" data-label={t("bill.col.commission")}><output data-testid="purchaser-commission-amount">{show(purchaserCommission(r))}</output></td>
              <td className="num calc total" data-label={t("bill.col.total")}><output data-testid="purchaser-total">{show(purchaserTotal(r))}</output></td>
              <td>
                <div className="row-actions">
                  <button type="button" className="icon-button" onClick={() => desk.duplicatePurchaser(i, row)} aria-label={`${t("bill.duplicateRow")} ${row + 1}`} title={t("bill.duplicateRow")} data-testid="purchaser-duplicate">⧉</button>
                  <button type="button" className="icon-button danger-icon" onClick={() => desk.requestRemovePurchaser(i, row)} aria-label={`${t("bill.removePurchaser")} ${row + 1}`} title={t("bill.removePurchaser")} data-testid="purchaser-remove">✕</button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** The parties of one lot as cards: the party on top, what it bought in a 2 x 2 grid, and the calculated figures at the bottom. */
export function PartyCards({ desk, lotIndex }: { desk: Desk; lotIndex: number }) {
  const { t } = desk;
  const lot = desk.draft.lots[lotIndex];
  const i = lotIndex;
  return (
    <div className="partycards" onKeyDown={(e) => moveOnEnter(e)}>
      {lot.purchasers.map((r, row) => (
        <article key={r.key} className="partycard" data-testid="purchaser-row">
          <div className="pc-head">
            <MasterPicker
              type="buyers" value={r.buyerId} known={desk.buyerKnown[r.buyerId] ?? null}
              onChange={(id, m) => { if (m) desk.setBuyerKnown((k) => ({ ...k, [String(m.id)]: m })); desk.updatePurchaser(i, row, { buyerId: id }); }}
              nav testId="purchaser-buyer" ariaLabel={`${t("bill.buyer")} ${row + 1}`}
            />
            <div className="row-actions">
              <button type="button" className="icon-button" onClick={() => desk.duplicatePurchaser(i, row)} aria-label={`${t("bill.duplicateRow")} ${row + 1}`} title={t("bill.duplicateRow")} data-testid="purchaser-duplicate">⧉</button>
              <button type="button" className="icon-button danger-icon" onClick={() => desk.requestRemovePurchaser(i, row)} aria-label={`${t("bill.removePurchaser")} ${row + 1}`} title={t("bill.removePurchaser")} data-testid="purchaser-remove">✕</button>
            </div>
          </div>
          <div className="pc-grid">
            <label className="field"><span className="field-label">{t("bill.col.haste")}</span><input value={r.haste} maxLength={100} onChange={(e) => desk.updatePurchaser(i, row, { haste: e.target.value })} data-nav data-testid="purchaser-haste" /></label>
            <label className="field"><span className="field-label">{t("bill.col.pcs")}</span><input className="num" type="number" min="0" step="1" value={r.pieces} onChange={(e) => desk.updatePurchaser(i, row, { pieces: e.target.value })} data-nav data-testid="purchaser-pieces" /></label>
            <label className="field"><span className="field-label">{t("bill.weight")}</span><input className="num" type="number" min="0" step="any" value={r.weight} onChange={(e) => desk.updatePurchaser(i, row, { weight: e.target.value })} data-nav data-testid="purchaser-weight" /></label>
            <label className="field"><span className="field-label">{t("bill.rate")}</span><input className="num" type="number" min="0" step="any" value={r.rate} onChange={(e) => desk.updatePurchaser(i, row, { rate: e.target.value })} data-nav data-testid="purchaser-rate" /></label>
          </div>
          <dl className="pc-figures">
            <div><dt>{t("bill.col.value")}</dt><dd><output data-testid="purchaser-value">{show(purchaserValue(r))}</output></dd></div>
            <div><dt>{t("bill.col.commission")}</dt><dd><output data-testid="purchaser-commission-amount">{show(purchaserCommission(r))}</output></dd></div>
            <div className="total"><dt>{t("bill.col.total")}</dt><dd><output data-testid="purchaser-total">{show(purchaserTotal(r))}</output></dd></div>
          </dl>
        </article>
      ))}
      <button type="button" className="partycard add" onClick={() => desk.addPurchaser(i)} data-testid="add-purchaser">+ {t("bill.addPurchaser")}</button>
    </div>
  );
}

/** Parties of one lot with their heading, add buttons, totals line and the notes about them (table or cards). */
export function PartiesPanel({ desk, lotIndex, view = "table", showHeading = true }: { desk: Desk; lotIndex: number; view?: "table" | "cards"; showHeading?: boolean }) {
  const { t } = desk;
  const lot = desk.draft.lots[lotIndex];
  const notes = allocationNotes(lot);
  const allocPieces = sumScaled(lot.purchasers.map((x) => parseScaled(x.pieces)));
  const allocWeight = sumScaled(lot.purchasers.map((x) => parseScaled(x.weight)));
  return (
    <div className="parties" data-testid="lot-detail" data-lot-index={lotIndex}>
      <div className="parties-head">
        {showHeading && <h3>{fill(t("bill.purchasers"), { n: lotIndex + 1 })}</h3>}
        <div className="row">
          <button type="button" className="secondary small" onClick={() => { desk.setSelected(lotIndex); desk.setDialog("buyers"); }} data-testid="new-buyer">+ {t("bill.newBuyer")}</button>
          {view === "table" && <button type="button" className="secondary small" onClick={() => desk.addPurchaser(lotIndex)} data-testid="add-purchaser">+ {t("bill.addPurchaser")}</button>}
        </div>
      </div>
      {view === "table" ? <PartyTable desk={desk} lotIndex={lotIndex} /> : <PartyCards desk={desk} lotIndex={lotIndex} />}
      {lot.purchasers.length > 0 && (
        <p className="alloc-strip" data-testid="alloc-strip">{fill(t("bill.allocated"), { pieces: formatScaled(allocPieces), weight: formatScaled(allocWeight) })}</p>
      )}
      {notes.map((n, k) => (
        <p key={k} className="note warn" role="status" data-testid="lot-note">
          {n.kind === "noPurchasers" && t("bill.noteNoPurchasers")}
          {n.kind === "pieces" && fill(t("bill.notePieces"), { entered: n.entered ?? "", lot: n.lot ?? "" })}
          {n.kind === "weight" && fill(t("bill.noteWeight"), { entered: n.entered ?? "", lot: n.lot ?? "" })}
        </p>
      ))}
    </div>
  );
}

/** The bill details (date, village, farmer, transport, bhada, bill type) in one of the arrangements. */
export function DetailsPanel({ desk, variant = "strip" }: { desk: Desk; variant?: "strip" | "form" | "column" | "compact" }) {
  return (
    <BillHeaderSection
      draft={desk.draft} patch={desk.patch} dateProblem={desk.dateProblem} variant={variant}
      villageKnown={desk.villageKnown} farmerKnown={desk.farmerKnown} onVillage={desk.onVillage} onFarmer={desk.onFarmer}
      farmersInVillage={desk.farmersInVillage} openDialog={(type) => desk.setDialog(type)}
    />
  );
}

/** The expenses table (the Motor Bhada row of the details is automatic). */
export function ExpensesPanel({ desk }: { desk: Desk }) {
  return (
    <ExpensesSection
      expenses={desk.draft.expenses} heads={desk.heads} rates={desk.rates} pieces={desk.pieces} update={desk.updateExpense} add={desk.addExpense}
      requestRemove={desk.requestRemoveExpense} bhadaHead={desk.bhadaHead} bhadaAmount={desk.bhadaAmount}
    />
  );
}
