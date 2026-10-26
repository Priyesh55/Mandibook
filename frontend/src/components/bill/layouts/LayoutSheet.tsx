"use client";

import { Fragment } from "react";
import { lotValue, purchaserCommission, purchaserTotal, purchaserValue } from "@/lib/billDraft";
import { formatScaled, sumScaled } from "@/lib/decimal";
import { nameOf } from "@/lib/format";
import { fill } from "@/lib/text";
import { MasterPicker } from "../../MasterPicker";
import { BillChecks } from "../BillSidebar";
import { DeskAlerts, DeskHeader, DetailsPanel, ExpensesPanel, show } from "../DeskParts";
import type { LayoutInfo } from "../layouts";
import { moveOnEnter } from "../LotsSection";
import type { Desk } from "../useBillDesk";

/**
 * Layout 4: the sheet. One dense spreadsheet: a row per lot with its party rows directly under it, cells without borders until you
 * click them, grid lines between rows, flat square buttons. The expenses are a second small sheet beside it, the totals a footer band.
 */
export function LayoutSheet({ desk, layout }: { desk: Desk; layout: LayoutInfo }) {
  const { t, lang } = desk;
  const lots = desk.draft.lots;
  return (
    <>
      <DeskHeader desk={desk} layout={layout} />
      <DeskAlerts desk={desk} />
      <DetailsPanel desk={desk} variant="compact" />
      <div className="sheet-wrap">
        <div className="sheet-main" onKeyDown={(e) => moveOnEnter(e)}>
          <table className="sheet" data-testid="sheet">
            <colgroup>
              <col style={{ width: "46px" }} /><col /><col style={{ width: "8%" }} /><col style={{ width: "10%" }} /><col style={{ width: "10%" }} />
              <col style={{ width: "13%" }} /><col style={{ width: "9%" }} /><col style={{ width: "8%" }} /><col style={{ width: "9%" }} /><col style={{ width: "72px" }} />
            </colgroup>
            <thead>
              <tr>
                <th>#</th><th>{t("bill.item")} / {t("bill.col.purchaser")}</th><th className="num">{t("bill.pieces")}</th><th className="num">{t("bill.weight")}</th>
                <th className="num">{t("bill.rate")}</th><th>{t("bill.marka")} / {t("bill.col.haste")}</th><th className="num">{t("bill.col.value")}</th>
                <th className="num">{t("bill.col.commission")}</th><th className="num">{t("bill.col.total")}</th><th />
              </tr>
            </thead>
            <tbody>
              {lots.map((l, idx) => {
                const current = idx === desk.selectedIndex;
                const commission = sumScaled(l.purchasers.map((p) => purchaserCommission(p)));
                return (
                  <Fragment key={l.key}>
                    <tr className={`sheet-lot${current ? " current" : ""}`} data-testid="lot-row" aria-current={current ? "true" : undefined} onFocusCapture={() => { if (!current) desk.setSelected(idx); }}>
                      <td><button type="button" className={`lot-pick${current ? " current" : ""}`} aria-current={current ? "true" : undefined} onClick={() => desk.setSelected(idx)} title={t("bill.pickLot")} data-testid="lot-tab">{idx + 1}</button></td>
                      <td>
                        <select value={l.itemId} onChange={(e) => desk.updateLot(idx, { itemId: e.target.value })} data-nav data-testid="lot-item" aria-label={`${t("bill.item")} ${idx + 1}`}>
                          <option value="">{t("common.select")}</option>
                          {(desk.items ?? []).map((m) => <option key={m.id} value={m.id}>{nameOf(m, lang)}</option>)}
                        </select>
                      </td>
                      <td><input className="num" type="number" min="0" step="1" value={l.pieces} onChange={(e) => desk.updateLot(idx, { pieces: e.target.value })} data-nav data-testid="lot-pieces" aria-label={`${t("bill.pieces")} ${idx + 1}`} /></td>
                      <td><input className="num" type="number" min="0" step="any" value={l.weight} onChange={(e) => desk.updateLot(idx, { weight: e.target.value })} data-nav data-testid="lot-weight" aria-label={`${t("bill.weight")} ${idx + 1}`} /></td>
                      <td><input className="num" type="number" min="0" step="any" value={l.rate} onChange={(e) => desk.updateLot(idx, { rate: e.target.value })} data-nav data-testid="lot-rate" aria-label={`${t("bill.rate")} ${idx + 1}`} /></td>
                      <td><input value={l.marka} maxLength={60} onChange={(e) => desk.updateLot(idx, { marka: e.target.value })} data-nav data-testid="lot-marka" aria-label={`${t("bill.marka")} ${idx + 1}`} /></td>
                      <td className="num calc"><output data-testid="lot-value">{show(lotValue(l))}</output></td>
                      <td className="num calc muted" title={t("bill.buyerCommission.short")}>{l.purchasers.length ? formatScaled(commission) : ""}</td>
                      <td />
                      <td>
                        <div className="row-actions">
                          <button type="button" className="icon-button" onClick={() => desk.duplicateLot(idx)} aria-label={`${t("bill.duplicateLot")} ${idx + 1}`} title={t("bill.duplicateLot")} data-testid="duplicate-lot">⧉</button>
                          <button type="button" className="icon-button danger-icon" disabled={lots.length === 1} onClick={() => desk.requestRemoveLot(idx)} aria-label={`${t("bill.removeLot")} ${idx + 1}`} title={t("bill.removeLot")} data-testid="lot-remove">✕</button>
                        </div>
                      </td>
                    </tr>
                    {l.purchasers.map((r, row) => (
                      <tr key={r.key} className="sheet-party" data-testid="purchaser-row" onFocusCapture={() => { if (!current) desk.setSelected(idx); }}>
                        <td className="arrow" aria-hidden>↳</td>
                        <td className="cell-picker">
                          <MasterPicker
                            type="buyers" value={r.buyerId} known={desk.buyerKnown[r.buyerId] ?? null}
                            onChange={(id, m) => { if (m) desk.setBuyerKnown((k) => ({ ...k, [String(m.id)]: m })); desk.updatePurchaser(idx, row, { buyerId: id }); }}
                            nav testId="purchaser-buyer" ariaLabel={`${t("bill.buyer")} ${row + 1}`}
                          />
                        </td>
                        <td><input className="num" type="number" min="0" step="1" value={r.pieces} onChange={(e) => desk.updatePurchaser(idx, row, { pieces: e.target.value })} data-nav data-testid="purchaser-pieces" aria-label={`${t("bill.pieces")} ${row + 1}`} /></td>
                        <td><input className="num" type="number" min="0" step="any" value={r.weight} onChange={(e) => desk.updatePurchaser(idx, row, { weight: e.target.value })} data-nav data-testid="purchaser-weight" aria-label={`${t("bill.weight")} ${row + 1}`} /></td>
                        <td><input className="num" type="number" min="0" step="any" value={r.rate} onChange={(e) => desk.updatePurchaser(idx, row, { rate: e.target.value })} data-nav data-testid="purchaser-rate" aria-label={`${t("bill.rate")} ${row + 1}`} /></td>
                        <td><input value={r.haste} maxLength={100} onChange={(e) => desk.updatePurchaser(idx, row, { haste: e.target.value })} data-nav data-testid="purchaser-haste" aria-label={`${t("bill.haste")} ${row + 1}`} /></td>
                        <td className="num calc"><output data-testid="purchaser-value">{show(purchaserValue(r))}</output></td>
                        <td className="num calc"><output data-testid="purchaser-commission-amount">{show(purchaserCommission(r))}</output></td>
                        <td className="num calc total"><output data-testid="purchaser-total">{show(purchaserTotal(r))}</output></td>
                        <td>
                          <div className="row-actions">
                            <button type="button" className="icon-button" onClick={() => desk.duplicatePurchaser(idx, row)} aria-label={`${t("bill.duplicateRow")} ${row + 1}`} title={t("bill.duplicateRow")} data-testid="purchaser-duplicate">⧉</button>
                            <button type="button" className="icon-button danger-icon" onClick={() => desk.requestRemovePurchaser(idx, row)} aria-label={`${t("bill.removePurchaser")} ${row + 1}`} title={t("bill.removePurchaser")} data-testid="purchaser-remove">✕</button>
                          </div>
                        </td>
                      </tr>
                    ))}
                    <tr className="sheet-add">
                      <td />
                      <td colSpan={9}>
                        <button type="button" className="link-button" onClick={() => desk.addPurchaser(idx)} data-testid="add-purchaser">+ {t("bill.addPurchaser")}</button>
                        <button type="button" className="link-button" onClick={() => { desk.setSelected(idx); desk.setDialog("buyers"); }} data-testid="new-buyer">+ {t("bill.newBuyer")}</button>
                      </td>
                    </tr>
                  </Fragment>
                );
              })}
              <tr className="sheet-addlot">
                <td colSpan={10}><button type="button" className="link-button strong" onClick={desk.addLot} data-testid="add-lot">+ {t("bill.addLot")}</button></td>
              </tr>
            </tbody>
          </table>
          {desk.warnings.length > 0 && <p className="sheet-note" data-testid="lot-note">{desk.warnings.map((w) => `${fill(t("bill.lotLabel"), { n: w.lot })}: ${w.note.kind === "noPurchasers" ? t("bill.noteNoPurchasers") : w.note.kind === "pieces" ? fill(t("bill.notePieces"), { entered: w.note.entered ?? "", lot: w.note.lot ?? "" }) : fill(t("bill.noteWeight"), { entered: w.note.entered ?? "", lot: w.note.lot ?? "" })}`).join("  ·  ")}</p>}
        </div>
        <aside className="sheet-side">
          <ExpensesPanel desk={desk} />
          <BillChecks problems={desk.attempted ? desk.problemTexts : []} warnings={[]} />
        </aside>
      </div>
      <div className="sheet-total" role="region" aria-label={t("bill.summary")} data-testid="bill-summary">
        <span><b>{t("bill.farmerSide")}</b></span>
        <span>{t("bill.gross.short")} <strong data-testid="total-gross">{desk.totals.gross}</strong></span>
        <span>{t("bill.expenses.short")} <strong data-testid="total-expenses">{desk.totals.expenses}</strong></span>
        <span className="net">{t("bill.net.short")} <strong data-testid="total-net">{desk.totals.net}</strong></span>
        <span className="sep" />
        <span><b>{t("bill.buyerSide")}</b></span>
        <span>{t("bill.buyerValue.short")} <strong data-testid="total-buyer-value">{desk.totals.buyerValue}</strong></span>
        <span>{t("bill.buyerCommission.short")} <strong data-testid="total-buyer-commission">{desk.totals.buyerCommission}</strong></span>
        <span>{t("bill.buyerTotal.short")} <strong data-testid="total-buyer-purchase">{desk.totals.buyerPurchaseValue}</strong></span>
        <span className="status">
          {desk.problems.length === 0 ? <span data-testid="review-ready">✓ {t("bill.readyShort")}</span> : <span data-testid="review-count">{fill(t("bill.problemCount"), { n: desk.problems.length })}</span>}
        </span>
      </div>
    </>
  );
}
