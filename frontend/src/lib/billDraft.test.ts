import assert from "node:assert/strict";
import { test } from "node:test";
import {
  allocationNotes, bhadaExpenseAmount, buildRequest, findBhadaHead, draftTotals, emptyDraft, emptyExpense, emptyLot, emptyPurchaser, expenseAmount, financialYearOf, fyLabel,
  lotValue, purchaserCommission, purchaserHasData, purchaserTotal, purchaserValue, totalPieces, validateDraft, type HeadRates,
} from "./billDraft.ts";
import { formatScaled } from "./decimal.ts";

test("the financial year runs from 1 April to 31 March", () => {
  assert.equal(financialYearOf("2026-03-31"), 2025);
  assert.equal(financialYearOf("2026-04-01"), 2026);
  assert.equal(financialYearOf("2026-12-31"), 2026);
  assert.equal(financialYearOf("2027-01-01"), 2026);
  assert.equal(financialYearOf("2026-02-30"), null);
  assert.equal(financialYearOf(""), null);
  assert.equal(fyLabel(2026), "2026-27");
  assert.equal(fyLabel(2099), "2099-00");
});

// the same scenario as the server test BillWorkflowApiIntegrationTest: two lots (35 pieces), one split between two purchasers,
// two rate-based expenses (heads 1 and 2) and three typed ones
const RATES: HeadRates = { "1": "2.4", "2": "1.91" };

function sample() {
  const d = emptyDraft("2026-10-24");
  d.farmerId = "1";
  d.bhadaPaid = "no";                       // a new bill says yes by default; this scenario has no bhada
  const lot1 = emptyLot();
  Object.assign(lot1, { itemId: "1", pieces: "20", weight: "1000", rate: "1800" });
  const a = emptyPurchaser();
  Object.assign(a, { buyerId: "5", haste: "Synthetic Person", pieces: "12", weight: "600", rate: "1800" });
  const b = emptyPurchaser();
  Object.assign(b, { buyerId: "6", pieces: "8", weight: "400", rate: "1800" });
  lot1.purchasers = [a, b];
  const lot2 = emptyLot();
  Object.assign(lot2, { itemId: "2", pieces: "15", weight: "1500", rate: "1500" });
  d.lots = [lot1, lot2];
  const rows = [["1", ""], ["2", ""], ["3", "100"], ["4", "75.50"], ["5", "1000"]];
  d.expenses = rows.map(([h, amount]) => Object.assign(emptyExpense(), { expenseHeadId: h, amount }));
  return d;
}

test("lot and purchaser values are weight x rate, commission is 5 percent of the purchaser value", () => {
  const d = sample();
  assert.equal(formatScaled(lotValue(d.lots[0]) as bigint), "18000");
  assert.equal(formatScaled(lotValue(d.lots[1]) as bigint), "22500");
  assert.equal(formatScaled(purchaserValue(d.lots[0].purchasers[0]) as bigint), "10800");
  assert.equal(formatScaled(purchaserCommission(d.lots[0].purchasers[0]) as bigint), "540");
  assert.equal(formatScaled(purchaserCommission(d.lots[0].purchasers[1]) as bigint), "360");
  assert.equal(formatScaled(purchaserTotal(d.lots[0].purchasers[0]) as bigint), "11340");
  assert.equal(lotValue({ weight: "", rate: "1800" }), null);
  assert.equal(purchaserTotal({ weight: "10", rate: "" }), null);
});

test("a per-piece expense is pieces x its predefined rate; a typed one is as typed", () => {
  const d = sample();
  const pieces = totalPieces(d);
  assert.equal(formatScaled(pieces), "35");
  assert.equal(formatScaled(expenseAmount(d.expenses[0], RATES, pieces) as bigint), "84");
  assert.equal(formatScaled(expenseAmount(d.expenses[1], RATES, pieces) as bigint), "66.85");
  assert.equal(formatScaled(expenseAmount(d.expenses[3], RATES, pieces) as bigint), "75.5");
  assert.equal(expenseAmount(emptyExpense(), RATES, pieces), null);
});

test("totals match the server: farmer side and buyer side apart, net to the rupee", () => {
  const t = draftTotals(sample(), RATES);
  assert.equal(t.gross, "40500");
  assert.equal(t.expenses, "1326.35");
  assert.equal(t.net, "39174");
  assert.equal(t.buyerValue, "18000");
  assert.equal(t.buyerCommission, "900");
  assert.equal(t.buyerPurchaseValue, "18900");
  assert.equal(t.lotsWithoutValue, 0);
});

test("a lot without weight or rate is reported, not guessed", () => {
  const d = sample();
  d.lots[1].rate = "";
  const t = draftTotals(d, RATES);
  assert.equal(t.gross, "18000");
  assert.equal(t.lotsWithoutValue, 1);
});

test("the request sends weights and rates, never a typed value, and no amount for a per-piece expense", () => {
  const d = sample();
  d.vehicleNo = "  MP09 ";
  const r = buildRequest(d, RATES);
  assert.equal(r.vehicleNo, "MP09");
  assert.equal(r.lots[0].weight, 1000);
  assert.equal(r.lots[0].purchasers[0].weight, 600);
  assert.equal("lotValue" in r.lots[0], false);
  assert.equal("value" in r.lots[0].purchasers[0], false);
  assert.equal(r.expenses[0].amount, null);   // per piece: the server calculates it
  assert.equal(r.expenses[3].amount, 75.5);   // typed
  assert.equal(r.financialYear, null);
  assert.equal(r.villageId, null);
});

test("a rate alone does not make a purchaser row count as typed into", () => {
  const p = emptyPurchaser();
  p.rate = "18";
  assert.equal(purchaserHasData(p), false);
  p.weight = "5";
  assert.equal(purchaserHasData(p), true);
});

test("the review notes compare the purchasers with the lot and change nothing", () => {
  const d = sample();
  assert.deepEqual(allocationNotes(d.lots[0]), []);                       // 12 + 8 = 20 pieces, 600 + 400 = 1000 kg
  assert.deepEqual(allocationNotes(d.lots[1]).map((n) => n.kind), ["noPurchasers"]);
  d.lots[0].purchasers[1].pieces = "5";
  d.lots[0].purchasers[1].weight = "370";
  const notes = allocationNotes(d.lots[0]);
  assert.deepEqual(notes.map((n) => n.kind), ["pieces", "weight"]);
  assert.equal(notes[0].entered, "17");
  assert.equal(notes[1].entered, "970");
  assert.equal(d.lots[0].pieces, "20");
});

test("validation asks for what the calculation needs", () => {
  const d = emptyDraft("2026-10-24");
  let keys = validateDraft(d, RATES).map((p) => p.key);
  assert.ok(keys.includes("bill.err.farmer"));
  assert.ok(keys.includes("bill.err.lotItem"));
  assert.ok(keys.includes("bill.err.lotWeightRate"));
  assert.deepEqual(validateDraft(sample(), RATES), []);

  const e = sample();
  e.lots[0].purchasers[0].rate = "";
  e.expenses[3].amount = "";
  e.expenses.push(Object.assign(emptyExpense(), { expenseHeadId: "1" }));
  keys = validateDraft(e, RATES).map((p) => p.key);
  assert.ok(keys.includes("bill.err.purchaserWeightRate"));
  assert.ok(keys.includes("bill.err.expenseAmount"));
  assert.ok(keys.includes("bill.err.expenseDuplicate"));

  const noPieces = sample();
  noPieces.lots.forEach((l) => { l.pieces = ""; });
  assert.ok(validateDraft(noPieces, RATES).some((p) => p.key === "bill.err.expensePieces"));
  assert.deepEqual(validateDraft(noPieces, {}).filter((p) => p.key === "bill.err.expensePieces"), []);

  const badDate = sample();
  badDate.billDate = "2026-02-30";
  assert.deepEqual(validateDraft(badDate, RATES).map((p) => p.key), ["bill.err.date"]);
});

test("the rate is entered per 100 kg: onion at 30 rupees a kg is entered as 3000", () => {
  assert.equal(formatScaled(lotValue({ weight: "250", rate: "3000" }) as bigint), "7500");
  assert.equal(formatScaled(purchaserValue({ weight: "40", rate: "3000" }) as bigint), "1200");
  assert.equal(formatScaled(purchaserCommission({ weight: "40", rate: "3000" }) as bigint), "60");
  assert.equal(formatScaled(lotValue({ weight: "1000.5", rate: "1825" }) as bigint), "18259");   // 18259.125
});

test("the bhada of the bill details is the Motor Bhada expense, added automatically", () => {
  const heads = [{ id: 7, name: "Hammali", nameHi: null }, { id: 9, name: " Motor  Bhada ", nameHi: null }];
  assert.equal(findBhadaHead(heads)?.id, 9);
  assert.equal(findBhadaHead([{ id: 3, name: "Other", nameHi: "मोटर भाड़ा" }])?.id, 3);
  assert.equal(findBhadaHead([{ id: 1, name: "Hammali", nameHi: null }]), undefined);
  assert.equal(findBhadaHead(undefined), undefined);

  const d = sample();
  d.expenses = [];
  assert.equal(bhadaExpenseAmount(d), null);                                   // not stated
  d.bhadaPaid = "no";
  d.bhadaAmount = "1000";
  assert.equal(bhadaExpenseAmount(d), null);                                   // not paid: no expense
  d.bhadaPaid = "yes";
  assert.equal(formatScaled(bhadaExpenseAmount(d) as bigint), "1000");
  // it is part of the expenses and of the net: 40500 - 1000
  const t = draftTotals(d, RATES);
  assert.equal(t.expenses, "1000");
  assert.equal(t.net, "39500");
  // and it is sent as an expense of its own head, with no button pressed
  const r = buildRequest(d, RATES, "9");
  assert.deepEqual(r.expenses, [{ expenseHeadId: 9, amount: 1000 }]);
  assert.deepEqual(buildRequest(d, RATES, null).expenses, []);                 // no head: nothing can be sent (validation says why)
  // without a Motor Bhada head the bill cannot be saved, and the same head chosen by hand twice is a duplicate
  assert.ok(validateDraft(d, RATES, null).some((p) => p.key === "bill.err.bhadaHead"));
  assert.deepEqual(validateDraft(d, RATES, "9"), []);
  d.expenses = [Object.assign(emptyExpense(), { expenseHeadId: "9", amount: "5" })];
  assert.ok(validateDraft(d, RATES, "9").some((p) => p.key === "bill.err.expenseDuplicate"));
});
