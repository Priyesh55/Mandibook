"use client";

import Link from "next/link";
import { api } from "@/lib/api";
import { nameOf, num } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import { useAsync } from "@/lib/useAsync";
import type { BillResponse } from "@/lib/types";

/**
 * The printable FARMER bill (A4). It shows exactly the saved bill as the server returns it: every figure is read from the
 * response, this component performs NO arithmetic. The purchasers and the commission charged to them are not on the farmer bill,
 * and neither is the financial year: the bill shows its date. Labels come from the dictionary, so the Hindi layout is a matter of
 * translations, not of new code.
 */
export function BillPrint({ id }: { id: string }) {
  const { t, lang } = useI18n();
  const bill = useAsync(() => api<BillResponse>(`/aadat/bills/${id}`, lang), `${id}|${lang}`);

  if (bill.error) return <div className="error" role="alert">{bill.error}</div>;
  const b = bill.data;
  if (!b) return <p className="muted">{t("common.loading")}</p>;

  const demo = b.calculation.mode === "DEMO_UNVERIFIED";
  const calculated = b.totals.basis === "CALCULATED";
  const firmName = lang === "hi" && b.firm.nameHi ? b.firm.nameHi : b.firm.name;
  const address = lang === "hi" && b.firm.addressHi ? b.firm.addressHi : b.firm.address;
  const contact = [
    b.firm.phone ? `${t("print.phone")}: ${b.firm.phone}` : null,
    b.firm.licenceNo ? `${t("print.licence")}: ${b.firm.licenceNo}` : null,
    b.firm.shopNo ? `${t("print.shop")}: ${b.firm.shopNo}` : null,
  ].filter((x): x is string => x !== null);
  const number = b.billNo !== null ? String(b.billNo) : `#${b.id}`;
  const billType = b.billType === "CREDIT" ? t("bill.billType.credit") : b.billType === "CASH" ? t("bill.billType.cash") : null;
  const bhada = b.bhadaPaid === null ? null : b.bhadaPaid ? `${t("bill.yes")}${b.bhadaAmount !== null ? ` (${num(b.bhadaAmount)})` : ""}` : t("bill.no");
  const refs = [
    [t("bill.panjiyan"), b.panjiyanNo], [t("bill.bookNo"), b.bookNo], [t("bill.receiptNo"), b.receiptNo],
    [t("bill.entryNo"), b.entryNo], [t("bill.wsNo"), b.wsNo],
  ].filter((r): r is [string, string] => r[1] !== null);
  const calculatedExpenses = b.expenses.some((e) => e.quantity !== null);

  return (
    <div>
      <div className="row no-print" style={{ marginBottom: 12 }}>
        <button onClick={() => window.print()} data-testid="print-button">{t("print.button")}</button>
        <Link href={`/bills/${b.id}`} className="button secondary">{t("common.back")}</Link>
      </div>

      <article className="bill-sheet" data-testid="print-sheet" lang={lang}>
        {demo && <div className="watermark" data-testid="print-watermark" aria-hidden>{t("print.watermark")}</div>}
        <h1 data-testid="print-title">{firmName}</h1>
        {address && <p className="firm-line" data-testid="print-address">{address}</p>}
        {contact.length > 0 && <p className="firm-line" data-testid="print-contact">{contact.join("  ·  ")}</p>}
        <p className="firm-line"><strong data-testid="print-doc-title">{t("print.title")}</strong></p>
        <hr className="rule" />

        <table className="meta">
          <tbody>
            <tr>
              <th>{t("print.billNo")}</th><td data-testid="print-ref"><strong>{number}</strong></td>
              <th>{t("print.date")}</th><td data-testid="print-date">{b.billDate}</td>
            </tr>
            <tr>
              <th>{t("print.farmer")}</th><td data-testid="print-farmer"><strong>{nameOf(b.farmer, lang)}</strong></td>
              <th>{t("print.village")}</th><td data-testid="print-village">{nameOf(b.village, lang)}</td>
            </tr>
            <tr>
              <th>{t("bill.vehicleNo")}</th><td data-testid="print-vehicle">{b.vehicleNo ?? "—"}</td>
              <th>{t("bill.transport")}</th><td data-testid="print-transport">{b.transport ?? "—"}</td>
            </tr>
            {(billType || bhada) && (
              <tr>
                <th>{t("bill.billType")}</th><td data-testid="print-bill-type">{billType ?? "—"}</td>
                <th>{t("bill.bhadaPaid")}</th><td data-testid="print-bhada">{bhada ?? "—"}</td>
              </tr>
            )}
            {refs.length > 0 && (
              <tr><th>{t("bill.refNumbers")}</th><td colSpan={3} data-testid="print-refs">{refs.map(([k, v]) => `${k}: ${v}`).join("   ")}</td></tr>
            )}
          </tbody>
        </table>

        <h3>{t("print.lotsTitle")}</h3>
        <table data-testid="print-lots">
          <thead>
            <tr>
              <th>#</th><th>{t("bill.item")}</th><th>{t("bill.marka")}</th><th className="num">{t("bill.pieces")}</th>
              <th className="num">{t("bill.weight")}</th><th className="num">{t("bill.rate")}</th>
              <th className="num">{t("bill.col.lotValue")}</th>
            </tr>
          </thead>
          <tbody>
            {b.lots.map((l) => (
              <tr key={l.lineNo} data-testid="print-lot-row">
                <td>{l.lineNo}</td><td>{nameOf(l.item, lang)}</td><td>{l.marka ?? "—"}</td>
                <td className="num">{num(l.pieces)}</td><td className="num">{num(l.weight)}</td><td className="num">{num(l.rate)}</td>
                <td className="num" data-testid="print-lot-value">{num(l.lotValue)}</td>
              </tr>
            ))}
          </tbody>
        </table>

        {b.expenses.length > 0 && (
          <>
            <h3>{t("print.expensesTitle")}</h3>
            <table data-testid="print-expenses">
              <thead>
                <tr>
                  <th>{t("bill.expenseHead")}</th>
                  {calculatedExpenses && <th className="num">{t("bill.pieces")}</th>}
                  {calculatedExpenses && <th className="num">{t("bill.col.expenseRate")}</th>}
                  <th className="num">{t("bill.amount")}</th>
                </tr>
              </thead>
              <tbody>
                {b.expenses.map((e) => (
                  <tr key={e.seq} data-testid="print-expense-row">
                    <td>{nameOf(e.expenseHead, lang)}</td>
                    {calculatedExpenses && <td className="num">{e.quantity !== null ? num(e.quantity) : ""}</td>}
                    {calculatedExpenses && <td className="num">{e.rate !== null ? num(e.rate) : ""}</td>}
                    <td className="num">{num(e.amount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}

        <table className="sums" data-testid="print-sums">
          <tbody>
            <tr><th>{t("print.gross")}</th><td className="num" data-testid="print-gross">{num(b.totals.farmerSide.grossOfEnteredLotValues)}</td></tr>
            <tr><th>{t("print.totalExpenses")}</th><td className="num" data-testid="print-total-expenses">{num(b.totals.farmerSide.totalOfEnteredExpenses)}</td></tr>
            <tr className="net"><th>{t("print.net")}</th><td className="num" data-testid="print-net">{num(b.totals.farmerSide.netOfEnteredAmounts)}</td></tr>
          </tbody>
        </table>
        <p className="note-line" data-testid="print-totals-note">{calculated ? t("print.calcNote") : t("print.totalsNote")}</p>
        {b.calculation.noticeKey && <p className="note-line" data-testid="print-notice" style={{ fontWeight: demo ? 700 : 400 }}>{t(b.calculation.noticeKey)}</p>}

        <div className="signs">
          <div>{t("print.signBuyer")}</div>
          <div>{t("print.signFarmer")}</div>
        </div>
      </article>
    </div>
  );
}
