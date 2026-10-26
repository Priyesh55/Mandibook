"use client";

import { useRef } from "react";
import { allocationNotes, lotValue, purchaserCommission, purchaserTotal, purchaserValue, type LotDraft, type PurchaserDraft } from "@/lib/billDraft";
import { formatScaled, parseScaled, sumScaled } from "@/lib/decimal";
import { nameOf } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import { fill } from "@/lib/text";
import type { Master } from "@/lib/types";
import { MasterPicker } from "../MasterPicker";

interface Props {
  lots: LotDraft[];
  items: Master[] | undefined;
  selected: number;
  onSelect: (index: number) => void;
  updateLot: (index: number, patch: Partial<LotDraft>) => void;
  addLot: () => void;
  duplicateLot: (index: number) => void;
  requestRemoveLot: (index: number) => void;
  addPurchaser: (lotIndex: number) => void;
  duplicatePurchaser: (lotIndex: number, row: number) => void;
  updatePurchaser: (lotIndex: number, row: number, patch: Partial<PurchaserDraft>) => void;
  removePurchaser: (lotIndex: number, row: number) => void;
  /** Purchasers created in the dialog, so their names show for the ids already put into rows. */
  buyerKnown: Record<string, Master>;
  rememberBuyer: (master: Master | null) => void;
  openBuyerDialog: () => void;
}

const show = (v: bigint | null) => (v === null ? "—" : formatScaled(v));

/** Enter moves to the next field of the container, Shift+Enter to the previous. `last` is true when Enter was pressed in the last field. */
export function moveOnEnter(e: React.KeyboardEvent<HTMLElement>): { last: boolean } | null {
  if (e.key !== "Enter") return null;
  const target = e.target as HTMLElement;
  if (!target.matches("[data-nav]")) return null;
  e.preventDefault();
  const fields = Array.from(e.currentTarget.querySelectorAll<HTMLElement>("[data-nav]:not(:disabled)"));
  const at = fields.indexOf(target);
  if (e.shiftKey) fields[Math.max(0, at - 1)]?.focus();
  else if (at < fields.length - 1) fields[at + 1].focus();
  else return { last: true };
  return { last: false };
}

/**
 * The farmer's lots as a grid, one row per lot (item, pieces, weight, rate, marka and the CALCULATED value), and below it the
 * purchasers of the lot being edited. The lot you work in is marked, and focusing any field of a row selects it. Every cell is
 * always editable in place: there is no hidden editor. Nothing is shared out automatically; a difference between the purchasers
 * and the lot is a note, never a change.
 */
export function LotsSection(p: Props) {
  const { t, lang } = useI18n();
  const detail = useRef<HTMLElement>(null);
  const lot = p.lots[p.selected];
  const i = p.selected;

  function onPurchaserKey(e: React.KeyboardEvent<HTMLDivElement>) {
    const r = moveOnEnter(e);
    if (r?.last && lot.purchasers.length > 0 && lot.purchasers[lot.purchasers.length - 1].buyerId !== "") {
      p.addPurchaser(i);
      requestAnimationFrame(() => {
        const rows = detail.current?.querySelectorAll("[data-testid='purchaser-row']");
        rows?.[rows.length - 1]?.querySelector<HTMLElement>("[data-nav]")?.focus();
      });
    }
  }

  const notes = allocationNotes(lot);
  const allocPieces = sumScaled(lot.purchasers.map((x) => parseScaled(x.pieces)));
  const allocWeight = sumScaled(lot.purchasers.map((x) => parseScaled(x.weight)));
  const itemName = (l: LotDraft) => {
    const m = (p.items ?? []).find((x) => String(x.id) === l.itemId);
    return m ? nameOf(m, lang) : t("bill.lotNoItem");
  };

  return (
    <>
      <section className="card2" aria-labelledby="sec-lots" data-testid="section-lots">
        <header className="card2-head">
          <h2 id="sec-lots">{t("bill.lotsTitle")}</h2>
          <button type="button" className="secondary small" onClick={p.addLot} data-testid="add-lot">+ {t("bill.addLot")}</button>
        </header>
        <div onKeyDown={(e) => moveOnEnter(e)} data-testid="lot-grid">
          <table className="lt">
            <colgroup>
              <col style={{ width: "52px" }} /><col /><col style={{ width: "9%" }} /><col style={{ width: "11%" }} /><col style={{ width: "10%" }} />
              <col style={{ width: "13%" }} /><col style={{ width: "12%" }} /><col style={{ width: "76px" }} />
            </colgroup>
            <thead>
              <tr>
                <th scope="col"><span className="sr-only">{t("bill.currentLot")}</span></th>
                <th scope="col">{t("bill.item")}</th>
                <th scope="col" className="num">{t("bill.pieces")}</th>
                <th scope="col" className="num">{t("bill.weight")}</th>
                <th scope="col" className="num">{t("bill.rate")}</th>
                <th scope="col">{t("bill.marka")}</th>
                <th scope="col" className="num" title={t("bill.calcHint")}>{t("bill.col.value")}</th>
                <th scope="col"><span className="sr-only">{t("common.actions")}</span></th>
              </tr>
            </thead>
            <tbody>
              {p.lots.map((l, idx) => {
                const n = allocationNotes(l).length;
                const current = idx === i;
                return (
                  <tr key={l.key} className={current ? "current" : undefined} data-testid="lot-row" aria-current={current ? "true" : undefined} onFocusCapture={() => { if (!current) p.onSelect(idx); }}>
                    <td data-label="#">
                      <button
                        type="button" className={`lot-pick${current ? " current" : ""}`} aria-current={current ? "true" : undefined}
                        aria-label={`${fill(t("bill.lotLabel"), { n: idx + 1 })} ${itemName(l)}`} title={t("bill.pickLot")}
                        onClick={() => p.onSelect(idx)} data-testid="lot-tab"
                      >
                        {idx + 1}
                        {n > 0 && <span className="dot" aria-label={t("bill.warnings")} />}
                      </button>
                    </td>
                    <td data-label={t("bill.item")}>
                      <select value={l.itemId} onChange={(e) => p.updateLot(idx, { itemId: e.target.value })} data-nav data-testid="lot-item" aria-label={`${t("bill.item")} ${idx + 1}`}>
                        <option value="">{t("common.select")}</option>
                        {(p.items ?? []).map((m) => <option key={m.id} value={m.id}>{nameOf(m, lang)}</option>)}
                      </select>
                    </td>
                    <td data-label={t("bill.pieces")}><input className="num" type="number" min="0" step="1" value={l.pieces} onChange={(e) => p.updateLot(idx, { pieces: e.target.value })} data-nav data-testid="lot-pieces" aria-label={`${t("bill.pieces")} ${idx + 1}`} /></td>
                    <td data-label={t("bill.weight")}><input className="num" type="number" min="0" step="any" value={l.weight} onChange={(e) => p.updateLot(idx, { weight: e.target.value })} data-nav data-testid="lot-weight" aria-label={`${t("bill.weight")} ${idx + 1}`} /></td>
                    <td data-label={t("bill.rate")}><input className="num" type="number" min="0" step="any" value={l.rate} onChange={(e) => p.updateLot(idx, { rate: e.target.value })} data-nav data-testid="lot-rate" aria-label={`${t("bill.rate")} ${idx + 1}`} /></td>
                    <td data-label={t("bill.marka")}><input value={l.marka} maxLength={60} onChange={(e) => p.updateLot(idx, { marka: e.target.value })} data-nav data-testid="lot-marka" aria-label={`${t("bill.marka")} ${idx + 1}`} /></td>
                    <td className="num calc" data-label={t("bill.col.value")}><output data-testid="lot-value" title={t("bill.valueAuto")}>{show(lotValue(l))}</output></td>
                    <td>
                      <div className="row-actions">
                        <button type="button" className="icon-button" onClick={() => p.duplicateLot(idx)} aria-label={`${t("bill.duplicateLot")} ${idx + 1}`} title={t("bill.duplicateLot")} data-testid="duplicate-lot">⧉</button>
                        <button type="button" className="icon-button danger-icon" disabled={p.lots.length === 1} onClick={() => p.requestRemoveLot(idx)} aria-label={`${t("bill.removeLot")} ${idx + 1}`} title={t("bill.removeLot")} data-testid="lot-remove">✕</button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </section>

      <section className="card2" aria-labelledby="sec-purchasers" ref={detail} data-testid="lot-detail" data-lot-index={i}>
        <header className="card2-head">
          <h2 id="sec-purchasers">{fill(t("bill.purchasers"), { n: i + 1 })} <span className="sub">{itemName(lot)}</span></h2>
          <div className="row">
            <button type="button" className="secondary small" onClick={p.openBuyerDialog} data-testid="new-buyer">+ {t("bill.newBuyer")}</button>
            <button type="button" className="secondary small" onClick={() => p.addPurchaser(i)} data-testid="add-purchaser">+ {t("bill.addPurchaser")}</button>
          </div>
        </header>
        {lot.purchasers.length === 0 ? (
          <p className="empty-state" data-testid="purchasers-empty">{t("bill.noPurchasersYet")}</p>
        ) : (
          <div onKeyDown={onPurchaserKey}>
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
                        type="buyers" value={r.buyerId} known={p.buyerKnown[r.buyerId] ?? null}
                        onChange={(id, m) => { p.rememberBuyer(m); p.updatePurchaser(i, row, { buyerId: id }); }}
                        nav testId="purchaser-buyer" ariaLabel={`${t("bill.buyer")} ${row + 1}`}
                      />
                    </td>
                    <td data-label={t("bill.col.haste")}><input value={r.haste} maxLength={100} onChange={(e) => p.updatePurchaser(i, row, { haste: e.target.value })} data-nav data-testid="purchaser-haste" aria-label={`${t("bill.haste")} ${row + 1}`} /></td>
                    <td data-label={t("bill.col.pcs")}><input className="num" type="number" min="0" step="1" value={r.pieces} onChange={(e) => p.updatePurchaser(i, row, { pieces: e.target.value })} data-nav data-testid="purchaser-pieces" aria-label={`${t("bill.pieces")} ${row + 1}`} /></td>
                    <td data-label={t("bill.weight")}><input className="num" type="number" min="0" step="any" value={r.weight} onChange={(e) => p.updatePurchaser(i, row, { weight: e.target.value })} data-nav data-testid="purchaser-weight" aria-label={`${t("bill.weight")} ${row + 1}`} /></td>
                    <td data-label={t("bill.rate")}>
                      <input
                        className="num" type="number" min="0" step="any" value={r.rate} onChange={(e) => p.updatePurchaser(i, row, { rate: e.target.value })}
                        title={lot.rate ? fill(t("bill.sameAsLot"), { rate: lot.rate }) : undefined}
                        data-nav data-testid="purchaser-rate" aria-label={`${t("bill.rate")} ${row + 1}`}
                      />
                    </td>
                    <td className="num calc" data-label={t("bill.col.value")}><output data-testid="purchaser-value">{show(purchaserValue(r))}</output></td>
                    <td className="num calc" data-label={t("bill.col.commission")}><output data-testid="purchaser-commission-amount">{show(purchaserCommission(r))}</output></td>
                    <td className="num calc total" data-label={t("bill.col.total")}><output data-testid="purchaser-total">{show(purchaserTotal(r))}</output></td>
                    <td>
                      <div className="row-actions">
                        <button type="button" className="icon-button" onClick={() => p.duplicatePurchaser(i, row)} aria-label={`${t("bill.duplicateRow")} ${row + 1}`} title={t("bill.duplicateRow")} data-testid="purchaser-duplicate">⧉</button>
                        <button type="button" className="icon-button danger-icon" onClick={() => p.removePurchaser(i, row)} aria-label={`${t("bill.removePurchaser")} ${row + 1}`} title={t("bill.removePurchaser")} data-testid="purchaser-remove">✕</button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="alloc-strip" data-testid="alloc-strip">
              {fill(t("bill.allocated"), { pieces: formatScaled(allocPieces), weight: formatScaled(allocWeight) })}
            </p>
          </div>
        )}
        {notes.map((n, k) => (
          <p key={k} className="note warn" role="status" data-testid="lot-note">
            {n.kind === "noPurchasers" && t("bill.noteNoPurchasers")}
            {n.kind === "pieces" && fill(t("bill.notePieces"), { entered: n.entered ?? "", lot: n.lot ?? "" })}
            {n.kind === "weight" && fill(t("bill.noteWeight"), { entered: n.entered ?? "", lot: n.lot ?? "" })}
          </p>
        ))}
      </section>
    </>
  );
}
