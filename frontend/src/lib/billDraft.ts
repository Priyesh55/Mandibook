// The bill as it is typed, before it is saved. Plain data and plain functions, so it can be tested without a browser.
// The operator enters weight, rate and pieces; the amounts are CALCULATED by the owner-confirmed rules (2026-10-25) and shown
// live. The server calculates and stores its own figures with the same rules and is the one of record.

import { formatScaled, mulDivRound, mulRound, parseScaled, percentRound, roundTo, sumScaled } from "./decimal.ts";
import type { BillRequest, BillType, Master } from "./types";

/** Commission charged to the buyer, fixed for every bill. */
export const COMMISSION_PERCENT = 5;

export interface PurchaserDraft {
  key: string;
  buyerId: string;
  haste: string;
  pieces: string;
  weight: string;
  rate: string;
}

export interface LotDraft {
  key: string;
  itemId: string;
  pieces: string;
  weight: string;
  rate: string;
  marka: string;
  purchasers: PurchaserDraft[];
}

export interface ExpenseDraft {
  key: string;
  expenseHeadId: string;
  /** Typed only for a head without a predefined rate; ignored (and not sent) for a head that has one. */
  amount: string;
}

export interface BillDraft {
  billDate: string;
  farmerId: string;
  villageId: string;
  vehicleNo: string;
  transport: string;
  bhadaPaid: "" | "yes" | "no";
  bhadaAmount: string;
  billType: "" | BillType;
  panjiyanNo: string;
  bookNo: string;
  receiptNo: string;
  entryNo: string;
  wsNo: string;
  lots: LotDraft[];
  expenses: ExpenseDraft[];
}

/** Predefined rate per piece of each expense head, by head id (only heads that have one are present). */
export type HeadRates = Record<string, string>;

let counter = 0;
const nextKey = () => `k${++counter}`;

export const emptyPurchaser = (): PurchaserDraft => ({ key: nextKey(), buyerId: "", haste: "", pieces: "", weight: "", rate: "" });
export const emptyLot = (): LotDraft => ({ key: nextKey(), itemId: "", pieces: "", weight: "", rate: "", marka: "", purchasers: [] });
export const emptyExpense = (): ExpenseDraft => ({ key: nextKey(), expenseHeadId: "", amount: "" });

// ---- financial year: 1 April to 31 March (only the bill list filters by it; a bill shows just its date) ------------------------

/** The year a date's financial year starts in: 2026-03-31 -> 2025, 2026-04-01 -> 2026. null for an unreadable date. */
export function financialYearOf(iso: string): number | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  if (!m) return null;
  const year = Number(m[1]);
  const month = Number(m[2]);
  const day = Number(m[3]);
  const real = new Date(Date.UTC(year, month - 1, day));
  if (real.getUTCFullYear() !== year || real.getUTCMonth() !== month - 1 || real.getUTCDate() !== day) return null;
  return month >= 4 ? year : year - 1;
}

export const fyLabel = (startYear: number) => `${startYear}-${String((startYear + 1) % 100).padStart(2, "0")}`;

export function emptyDraft(todayIso: string): BillDraft {
  return {
    billDate: todayIso, farmerId: "", villageId: "", vehicleNo: "", transport: "",
    bhadaPaid: "yes", bhadaAmount: "", billType: "CREDIT", panjiyanNo: "", bookNo: "", receiptNo: "", entryNo: "", wsNo: "",
    lots: [emptyLot()], expenses: [],
  };
}

// ---- the calculation (mirrors BillCalculator on the server) -----------------------------------------------------------------

/** weight (kg) x rate per kg to a whole rupee, where the ENTERED rate is per 100 kg; null when either is missing or unreadable. */
export function lotValue(l: Pick<LotDraft, "weight" | "rate">): bigint | null {
  const w = parseScaled(l.weight);
  const r = parseScaled(l.rate);
  return w === null || r === null ? null : mulDivRound(w, r, 100, 0);
}
export const purchaserValue = (p: Pick<PurchaserDraft, "weight" | "rate">): bigint | null => lotValue(p);
/** 5 percent of the purchaser value to one decimal. */
export function purchaserCommission(p: Pick<PurchaserDraft, "weight" | "rate">): bigint | null {
  const v = purchaserValue(p);
  return v === null ? null : percentRound(v, COMMISSION_PERCENT, 1);
}
/** Value plus commission of one purchaser row; null when the value cannot be calculated yet. */
export function purchaserTotal(p: Pick<PurchaserDraft, "weight" | "rate">): bigint | null {
  const v = purchaserValue(p);
  const c = purchaserCommission(p);
  return v === null || c === null ? null : v + c;
}

/** Pieces (bags) of all lots of the bill: the quantity of every per-piece expense. */
export function totalPieces(d: Pick<BillDraft, "lots">): bigint {
  return sumScaled(d.lots.map((l) => parseScaled(l.pieces)));
}

// ---- Motor Bhada: the bhada paid in the bill details IS an expense, added to the bill automatically --------------------------------

/** The expense head that carries the bhada (found by its name, in English or Hindi); undefined when the firm has none. */
export function findBhadaHead(heads: Pick<Master, "id" | "name" | "nameHi">[] | undefined): Pick<Master, "id" | "name" | "nameHi"> | undefined {
  const clean = (v: string | null | undefined) => (v ?? "").toLowerCase().replace(/\s+/g, " ").trim();
  return (heads ?? []).find((h) => clean(h.name) === "motor bhada" || clean(h.nameHi) === "मोटर भाड़ा");
}

/** The bhada amount of the bill details when bhada was paid and an amount is typed: the amount of the Motor Bhada expense. */
export function bhadaExpenseAmount(d: Pick<BillDraft, "bhadaPaid" | "bhadaAmount">): bigint | null {
  return d.bhadaPaid === "yes" ? parseScaled(d.bhadaAmount) : null;
}

/** pieces x predefined rate for a head with a rate; the typed amount otherwise (null when not typed). */
export function expenseAmount(e: ExpenseDraft, rates: HeadRates, pieces: bigint): bigint | null {
  const rate = rates[e.expenseHeadId];
  if (rate !== undefined) {
    const r = parseScaled(rate);
    return r === null ? null : mulRound(pieces, r, 2);
  }
  return parseScaled(e.amount);
}

// ---- request ---------------------------------------------------------------------------------------------------------------

const num = (s: string): number | null => {
  const v = parseScaled(s);
  return v === null ? null : Number(s);
};
const text = (s: string): string | null => (s.trim() === "" ? null : s.trim());

/** A purchaser row counts as typed into when anything but the rate is filled (the rate alone is only a convenience default). */
export function purchaserHasData(p: PurchaserDraft): boolean {
  return p.buyerId !== "" || p.haste !== "" || p.pieces !== "" || p.weight !== "";
}
export function lotHasData(l: LotDraft): boolean {
  return l.itemId !== "" || l.pieces !== "" || l.weight !== "" || l.rate !== "" || l.marka !== "" || l.purchasers.some(purchaserHasData);
}
export const expenseHasData = (x: ExpenseDraft) => x.expenseHeadId !== "" || x.amount !== "";

export function buildRequest(d: BillDraft, rates: HeadRates, bhadaHeadId: string | null = null): BillRequest {
  return {
    billDate: d.billDate,
    financialYear: null,
    farmerId: Number(d.farmerId),
    villageId: d.villageId ? Number(d.villageId) : null,
    vehicleNo: text(d.vehicleNo),
    transport: text(d.transport),
    bhadaPaid: d.bhadaPaid === "" ? null : d.bhadaPaid === "yes",
    bhadaAmount: d.bhadaPaid === "yes" ? num(d.bhadaAmount) : null,
    billType: d.billType === "" ? null : d.billType,
    panjiyanNo: text(d.panjiyanNo),
    bookNo: text(d.bookNo),
    receiptNo: text(d.receiptNo),
    entryNo: text(d.entryNo),
    wsNo: text(d.wsNo),
    lots: d.lots.map((l) => ({
      itemId: Number(l.itemId),
      pieces: num(l.pieces),
      weight: num(l.weight),
      rate: num(l.rate),
      marka: text(l.marka),
      purchasers: l.purchasers.map((p) => ({
        buyerId: Number(p.buyerId),
        haste: text(p.haste),
        pieces: num(p.pieces),
        weight: num(p.weight),
        rate: num(p.rate),
      })),
    })),
    // a head with a predefined rate is calculated by the server from the pieces; only a typed expense carries an amount
    expenses: [
      ...d.expenses.map((e) => ({ expenseHeadId: Number(e.expenseHeadId), amount: rates[e.expenseHeadId] !== undefined ? null : num(e.amount) })),
      // the Motor Bhada expense: the bhada amount of the details, added automatically
      ...(bhadaHeadId !== null && bhadaExpenseAmount(d) !== null ? [{ expenseHeadId: Number(bhadaHeadId), amount: num(d.bhadaAmount) }] : []),
    ],
  };
}

// ---- totals (calculated; farmer side and buyer side never combined) -----------------------------------------------------------

export interface DraftTotals {
  gross: string;
  expenses: string;
  net: string;
  /** Lots that cannot be valued yet because a weight or a rate is missing. */
  lotsWithoutValue: number;
  buyerValue: string;
  buyerCommission: string;
  buyerPurchaseValue: string;
  purchaserRowsWithoutValue: number;
}

export function draftTotals(d: BillDraft, rates: HeadRates = {}): DraftTotals {
  const gross = sumScaled(d.lots.map(lotValue));
  const pieces = totalPieces(d);
  const expenses = sumScaled(d.expenses.map((e) => expenseAmount(e, rates, pieces))) + (bhadaExpenseAmount(d) ?? BigInt(0));
  const purchasers = d.lots.flatMap((l) => l.purchasers);
  const buyerValue = sumScaled(purchasers.map(purchaserValue));
  const buyerCommission = sumScaled(purchasers.map(purchaserCommission));
  return {
    gross: formatScaled(gross),
    expenses: formatScaled(expenses),
    net: formatScaled(roundTo(gross - expenses, 0)),
    lotsWithoutValue: d.lots.filter((l) => lotValue(l) === null).length,
    buyerValue: formatScaled(buyerValue),
    buyerCommission: formatScaled(buyerCommission),
    buyerPurchaseValue: formatScaled(buyerValue + buyerCommission),
    purchaserRowsWithoutValue: purchasers.filter((p) => purchaserValue(p) === null).length,
  };
}

// ---- review warnings: what the purchasers were entered with versus the lot. Information only, never changes data ---------------

export interface AllocationNote {
  kind: "noPurchasers" | "pieces" | "weight";
  entered?: string;
  lot?: string;
}

export function allocationNotes(l: LotDraft): AllocationNote[] {
  if (l.purchasers.length === 0) return lotHasData(l) ? [{ kind: "noPurchasers" }] : [];
  const notes: AllocationNote[] = [];
  const lotPieces = parseScaled(l.pieces);
  if (lotPieces !== null) {
    const entered = sumScaled(l.purchasers.map((p) => parseScaled(p.pieces)));
    if (entered !== lotPieces) notes.push({ kind: "pieces", entered: formatScaled(entered), lot: formatScaled(lotPieces) });
  }
  const lotWeight = parseScaled(l.weight);
  if (lotWeight !== null) {
    const entered = sumScaled(l.purchasers.map((p) => parseScaled(p.weight)));
    if (entered !== lotWeight) notes.push({ kind: "weight", entered: formatScaled(entered), lot: formatScaled(lotWeight) });
  }
  return notes;
}

// ---- validation before sending (the server checks everything again) -------------------------------------------------------------

export interface Problem {
  key: string;
  vars?: Record<string, string>;
}

export function validateDraft(d: BillDraft, rates: HeadRates = {}, bhadaHeadId: string | null = null): Problem[] {
  const out: Problem[] = [];
  if (financialYearOf(d.billDate) === null) out.push({ key: "bill.err.date" });
  if (d.farmerId === "") out.push({ key: "bill.err.farmer" });
  if (d.bhadaPaid === "yes" && parseScaled(d.bhadaAmount) === null) out.push({ key: "bill.err.bhadaAmount" });
  if (bhadaExpenseAmount(d) !== null && bhadaHeadId === null) out.push({ key: "bill.err.bhadaHead" });
  d.lots.forEach((l, i) => {
    const n = String(i + 1);
    if (l.itemId === "") out.push({ key: "bill.err.lotItem", vars: { n } });
    for (const [label, value] of [["pieces", l.pieces], ["weight", l.weight], ["rate", l.rate]] as const) {
      if (value.trim() !== "" && parseScaled(value) === null) out.push({ key: "bill.err.lotNumber", vars: { n, field: label } });
    }
    if (parseScaled(l.weight) === null || parseScaled(l.rate) === null) out.push({ key: "bill.err.lotWeightRate", vars: { n } });
    l.purchasers.forEach((p, j) => {
      const r = `${n}.${j + 1}`;
      if (p.buyerId === "") out.push({ key: "bill.err.purchaserBuyer", vars: { r } });
      for (const [label, value] of [["pieces", p.pieces], ["weight", p.weight], ["rate", p.rate]] as const) {
        if (value.trim() !== "" && parseScaled(value) === null) out.push({ key: "bill.err.purchaserNumber", vars: { r, field: label } });
      }
      if (parseScaled(p.weight) === null || parseScaled(p.rate) === null) out.push({ key: "bill.err.purchaserWeightRate", vars: { r } });
    });
  });
  const seen = new Set<string>();
  const pieces = totalPieces(d);
  d.expenses.forEach((e, i) => {
    const n = String(i + 1);
    if (e.expenseHeadId === "") out.push({ key: "bill.err.expenseHead", vars: { n } });
    else if (seen.has(e.expenseHeadId) || (bhadaHeadId !== null && e.expenseHeadId === bhadaHeadId && bhadaExpenseAmount(d) !== null)) out.push({ key: "bill.err.expenseDuplicate", vars: { n } });
    seen.add(e.expenseHeadId);
    if (rates[e.expenseHeadId] !== undefined) {
      if (pieces === BigInt(0)) out.push({ key: "bill.err.expensePieces", vars: { n } });
    } else if (parseScaled(e.amount) === null) out.push({ key: "bill.err.expenseAmount", vars: { n } });
  });
  return out;
}
