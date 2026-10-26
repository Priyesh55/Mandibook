"use client";

import { useEffect, useRef } from "react";
import { expenseAmount, type ExpenseDraft, type HeadRates } from "@/lib/billDraft";
import { formatScaled } from "@/lib/decimal";
import { nameOf } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import { fill } from "@/lib/text";
import type { Master } from "@/lib/types";

interface Props {
  expenses: ExpenseDraft[];
  heads: Master[] | undefined;
  /** Predefined rate per piece of the heads that have one, and the pieces (bags) of the bill they are multiplied by. */
  rates: HeadRates;
  pieces: bigint;
  update: (index: number, patch: Partial<ExpenseDraft>) => void;
  add: () => void;
  requestRemove: (index: number) => void;
  /** The head that carries the bhada, and the bhada amount of the bill details when bhada was paid: shown as an automatic row. */
  bhadaHead: Pick<Master, "id" | "name" | "nameHi"> | undefined;
  bhadaAmount: string | null;
}

/**
 * Expenses deducted from the farmer. A head with a predefined rate (Hammali, Khadi Karai ...) shows its rate and the amount is
 * calculated as pieces x rate; any other head is typed. The rate column shows which is which, so nothing is hidden. When bhada was paid
 * in the bill details, the Motor Bhada expense is there on its own (last row, with that amount): it is not added by a button.
 */
export function ExpensesSection(p: Props) {
  const { t, lang } = useI18n();
  const body = useRef<HTMLTableSectionElement>(null);
  const added = useRef(false);

  // after a row is added, put the cursor in its first field
  useEffect(() => {
    if (!added.current) return;
    added.current = false;
    const rows = body.current?.querySelectorAll("tr");
    rows?.[rows.length - 1]?.querySelector<HTMLElement>("[data-nav]")?.focus();
  }, [p.expenses.length]);

  function onKey(e: React.KeyboardEvent<HTMLTableSectionElement>) {
    if (e.key !== "Enter") return;
    const target = e.target as HTMLElement;
    if (!target.matches("[data-nav]")) return;
    e.preventDefault();
    const fields = Array.from(e.currentTarget.querySelectorAll<HTMLElement>("[data-nav]:not(:disabled)"));
    const at = fields.indexOf(target);
    if (e.shiftKey) fields[Math.max(0, at - 1)]?.focus();
    else if (at < fields.length - 1) fields[at + 1].focus();
    else if (p.expenses[p.expenses.length - 1].expenseHeadId !== "") {
      added.current = true;
      p.add();
    }
  }

  const used = new Set(p.expenses.map((x) => x.expenseHeadId).filter((x) => x !== ""));
  const bhadaId = p.bhadaHead ? String(p.bhadaHead.id) : null;
  const automatic = p.bhadaAmount !== null;

  return (
    <section className="card2" aria-labelledby="sec-expenses" data-testid="section-expenses">
      <header className="card2-head">
        <h2 id="sec-expenses">{t("bill.expensesTitle")}</h2>
        <div className="row">
          <button type="button" className="secondary small" onClick={() => { added.current = true; p.add(); }} data-testid="add-expense">+ {t("bill.addExpense")}</button>
        </div>
      </header>
      {p.expenses.length === 0 && !automatic ? (
        <p className="empty-state" data-testid="expenses-empty">{t("bill.noExpensesYet")}</p>
      ) : (
        <table className="et" data-testid="expense-table">
          <colgroup><col /><col style={{ width: "17%" }} /><col style={{ width: "26%" }} /><col style={{ width: "44px" }} /></colgroup>
          <thead>
            <tr>
              <th scope="col">{t("bill.expenseHead")}</th>
              <th scope="col" className="num">{t("bill.col.expenseRate")}</th>
              <th scope="col" className="num">{t("bill.amount")}</th>
              <th scope="col"><span className="sr-only">{t("common.actions")}</span></th>
            </tr>
          </thead>
          <tbody ref={body} onKeyDown={onKey}>
            {p.expenses.map((x, i) => {
              const rate = p.rates[x.expenseHeadId];
              const amount = rate !== undefined ? expenseAmount(x, p.rates, p.pieces) : null;
              return (
                <tr key={x.key} data-testid="expense-row">
                  <td data-label={t("bill.expenseHead")}>
                    <select value={x.expenseHeadId} onChange={(e) => p.update(i, { expenseHeadId: e.target.value })} data-nav data-testid="expense-head" aria-label={`${t("bill.expenseHead")} ${i + 1}`}>
                      <option value="">{t("common.select")}</option>
                      {(p.heads ?? []).filter((m) => String(m.id) === x.expenseHeadId || (!used.has(String(m.id)) && !(automatic && String(m.id) === bhadaId))).map((m) => <option key={m.id} value={m.id}>{nameOf(m, lang)}</option>)}
                    </select>
                  </td>
                  <td className="num rate" data-label={t("bill.col.expenseRate")} data-testid="expense-rate">
                    {rate !== undefined ? <span title={fill(t("bill.perPiece"), { rate })}>{rate}</span> : <span className="muted">—</span>}
                  </td>
                  <td className="num" data-label={t("bill.amount")}>
                    {rate !== undefined ? (
                      <output className="calc" data-testid="expense-amount-calc" title={t("bill.valueAuto")}>{amount === null ? "—" : formatScaled(amount)}</output>
                    ) : (
                      <input type="number" min="0" step="any" className="num" value={x.amount} onChange={(e) => p.update(i, { amount: e.target.value })} data-nav data-testid="expense-amount" aria-label={`${t("bill.amount")} ${i + 1}`} />
                    )}
                  </td>
                  <td>
                    <button type="button" className="icon-button danger-icon" onClick={() => p.requestRemove(i)} aria-label={`${t("bill.removeExpense")} ${i + 1}`} title={t("bill.removeExpense")} data-testid="expense-remove">✕</button>
                  </td>
                </tr>
              );
            })}
            {automatic && (
              <tr data-testid="expense-row" data-auto="bhada" className="auto">
                <td data-label={t("bill.expenseHead")}>
                  <span className="auto-head" data-testid="expense-auto-head">{p.bhadaHead ? nameOf(p.bhadaHead, lang) : t("bill.bhadaHeadMissing")}</span>
                  <span className="auto-tag">{t("bill.autoFromBhada")}</span>
                </td>
                <td className="num rate" data-label={t("bill.col.expenseRate")}><span className="muted">—</span></td>
                <td className="num" data-label={t("bill.amount")}>
                  <output className="calc" data-testid="expense-amount-bhada">{p.bhadaAmount}</output>
                </td>
                <td />
              </tr>
            )}
          </tbody>
        </table>
      )}
    </section>
  );
}
