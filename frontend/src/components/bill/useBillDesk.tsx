"use client";

import { useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import {
  allocationNotes, bhadaExpenseAmount, buildRequest, draftTotals, emptyDraft, emptyExpense, emptyLot, emptyPurchaser, expenseHasData, findBhadaHead,
  lotHasData, purchaserHasData, totalPieces, validateDraft, type BillDraft, type ExpenseDraft, type HeadRates, type LotDraft, type PurchaserDraft,
} from "@/lib/billDraft";
import { todayIso } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import { fill, problemText } from "@/lib/text";
import { useAsync } from "@/lib/useAsync";
import type { BillResponse, Master, Page } from "@/lib/types";

export type Pending =
  | { kind: "lot"; index: number }
  | { kind: "expense"; index: number }
  | { kind: "purchaser"; index: number; row: number }
  | { kind: "leave" }
  | null;
export type DialogType = "villages" | "farmers" | "buyers";

const DATE_KEYS = new Set(["bill.err.date"]);

/**
 * The bill workflow, shared by every layout of the bill entry: ONE draft (a single state object) feeds whatever panels a layout shows,
 * so nothing a panel does can lose what another one holds. Changing the date or an item never resets anything else; changing the
 * village clears only the farmer (who belongs to the old village). Master records are added in dialogs on top of the bill, which stays
 * untouched. The amounts shown are calculated live with the owner-confirmed rules; the server calculates and stores its own figures
 * with the same rules. A layout only decides how this is laid out, never what it does.
 */
export function useBillDesk() {
  const { t, lang } = useI18n();
  const router = useRouter();

  const [truncated, setTruncated] = useState(false);
  const noteTruncation = (res: Response) => {
    if (res.headers.get("X-Result-Truncated") === "true") setTruncated(true);
  };
  const items = useAsync(() => api<Master[]>("/masters/items?activeOnly=true", lang, { onResponse: noteTruncation }), "items");
  const heads = useAsync(() => api<Master[]>("/masters/expense-heads?activeOnly=true", lang, { onResponse: noteTruncation }), "heads");

  const [draft, setDraft] = useState<BillDraft>(() => emptyDraft(todayIso()));
  const [selected, setSelected] = useState(0);
  const [dirty, setDirty] = useState(false);
  const [errors, setErrors] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const [attempted, setAttempted] = useState(false);   // the list of what to fix is shown once Save was pressed with something missing
  const [pending, setPending] = useState<Pending>(null);
  const [dialog, setDialog] = useState<DialogType | null>(null);
  const [villageKnown, setVillageKnown] = useState<Master | null>(null);
  const [farmerKnown, setFarmerKnown] = useState<Master | null>(null);
  const [buyerKnown, setBuyerKnown] = useState<Record<string, Master>>({});
  // One key per form: a retry after a lost answer is the SAME save and returns the same bill, never a second one.
  const saveKey = useRef(crypto.randomUUID());

  const edit = (fn: (d: BillDraft) => BillDraft) => {
    setDraft(fn);
    setDirty(true);
  };
  const patch = (p: Partial<BillDraft>) => edit((d) => ({ ...d, ...p }));
  const mapLot = (i: number, fn: (l: LotDraft) => LotDraft) => edit((d) => ({ ...d, lots: d.lots.map((l, idx) => (idx === i ? fn(l) : l)) }));

  // the farmers of the chosen village (only their number is needed here, to say so when there are none)
  const farmersInVillage = useAsync(
    () => (draft.villageId ? api<Page<Master>>(`/masters/farmers/page?activeOnly=true&villageId=${draft.villageId}&size=1`, lang) : Promise.resolve(undefined)),
    `farmers-in|${draft.villageId}`,
  );

  // a bill that was typed but not saved is not lost by a stray click on a link or a refresh without a warning
  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = "";
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  // the predefined rate per piece of the expense heads that have one (Hammali, Khadi Karai ...)
  const rates = useMemo<HeadRates>(() => {
    const out: HeadRates = {};
    for (const h of heads.data ?? []) if (h.rateBasis === "PER_PIECE" && h.rate !== null && h.rate !== undefined) out[String(h.id)] = String(h.rate);
    return out;
  }, [heads.data]);
  // the bhada of the bill details is an expense of its own head (Motor Bhada), added to the bill automatically
  const bhadaHead = useMemo(() => findBhadaHead(heads.data), [heads.data]);
  const bhadaHeadId = bhadaHead ? String(bhadaHead.id) : null;
  const problems = useMemo(() => validateDraft(draft, rates, bhadaHeadId), [draft, rates, bhadaHeadId]);
  const dateProblem = problems.find((p) => DATE_KEYS.has(p.key));
  const totals = useMemo(() => draftTotals(draft, rates), [draft, rates]);
  const pieces = totalPieces(draft);

  // the desk is a one-screen workspace: the breadcrumb row is dropped while it is open
  useEffect(() => {
    document.body.classList.add("desk-page");
    return () => document.body.classList.remove("desk-page");
  }, []);

  function showError(err: unknown) {
    if (err instanceof ApiError && err.fieldErrors.length) setErrors(err.fieldErrors.map((f) => f.message));
    else setErrors([err instanceof Error ? err.message : t("common.error")]);
  }

  async function onSave(e?: React.FormEvent) {
    e?.preventDefault();
    if (saving) return;
    setErrors([]);
    if (problems.length > 0) {
      setAttempted(true);
      requestAnimationFrame(() => document.getElementById("sec-checks")?.scrollIntoView({ block: "nearest" }));
      return;
    }
    setSaving(true);
    try {
      const bill = await api<BillResponse>("/aadat/bills", lang, { method: "POST", body: buildRequest(draft, rates, bhadaHeadId), headers: { "Idempotency-Key": saveKey.current } });
      saveKey.current = crypto.randomUUID();
      setDirty(false);
      router.push(`/bills/${bill.id}?saved=1`);
    } catch (err) {
      // An answer from the server (a refusal) is final: the next attempt is a new save with a new key. No answer at all, or a
      // server error, leaves the outcome unknown: keep the key so that pressing Save again cannot create a second bill.
      const unknown = !(err instanceof ApiError) || err.status >= 500;
      if (!unknown) saveKey.current = crypto.randomUUID();
      if (unknown) setErrors([t("bill.retrying")]);
      else showError(err);
      setSaving(false);
    }
  }

  // ---- village first: the farmer belongs to the village ----
  function onVillage(id: string, village: Master | null) {
    edit((d) => ({ ...d, villageId: id, farmerId: d.villageId === id ? d.farmerId : "" }));
    setVillageKnown(village);
    if (id !== draft.villageId) setFarmerKnown(null);
  }
  function onFarmer(id: string, farmer: Master | null) {
    patch({ farmerId: id });
    setFarmerKnown(farmer);
  }

  // ---- lots, parties, expenses (always the whole draft, never a copy) ----
  const selectedIndex = Math.min(selected, draft.lots.length - 1);

  function created(master: Master) {
    if (dialog === "villages") {
      edit((d) => ({ ...d, villageId: String(master.id), farmerId: "" }));
      setVillageKnown(master);
      setFarmerKnown(null);
    } else if (dialog === "farmers") {
      edit((d) => ({ ...d, farmerId: String(master.id) }));
      setFarmerKnown(master);
      farmersInVillage.reload();
    } else if (dialog === "buyers") {
      setBuyerKnown((k) => ({ ...k, [String(master.id)]: master }));
      mapLot(selectedIndex, (l) => {
        const emptyAt = l.purchasers.findIndex((r) => r.buyerId === "" && !purchaserHasData(r));
        if (emptyAt >= 0) return { ...l, purchasers: l.purchasers.map((r, k) => (k === emptyAt ? { ...r, buyerId: String(master.id) } : r)) };
        return { ...l, purchasers: [...l.purchasers, { ...emptyPurchaser(), buyerId: String(master.id), rate: l.rate }] };
      });
    }
    setDialog(null);
  }

  const addLot = () => {
    edit((d) => ({ ...d, lots: [...d.lots, emptyLot()] }));
    setSelected(draft.lots.length);
  };
  const duplicateLot = (i: number) => {
    edit((d) => {
      const src = d.lots[i];
      const copy: LotDraft = { ...src, key: emptyLot().key, purchasers: src.purchasers.map((p) => ({ ...p, key: emptyPurchaser().key })) };
      const lots = [...d.lots];
      lots.splice(i + 1, 0, copy);
      return { ...d, lots };
    });
    setSelected(i + 1);
  };
  const removeLot = (i: number) => {
    edit((d) => ({ ...d, lots: d.lots.filter((_, idx) => idx !== i) }));
    setSelected((s) => Math.max(0, Math.min(s, draft.lots.length - 2)));
    setPending(null);
  };
  const updateLot = (i: number, p: Partial<LotDraft>) => mapLot(i, (l) => ({ ...l, ...p }));
  const addPurchaser = (i: number) => mapLot(i, (l) => ({ ...l, purchasers: [...l.purchasers, { ...emptyPurchaser(), rate: l.rate }] }));
  const duplicatePurchaser = (i: number, row: number) =>
    mapLot(i, (l) => {
      const purchasers = [...l.purchasers];
      purchasers.splice(row + 1, 0, { ...l.purchasers[row], key: emptyPurchaser().key });
      return { ...l, purchasers };
    });
  const updatePurchaser = (i: number, row: number, p: Partial<PurchaserDraft>) =>
    mapLot(i, (l) => ({ ...l, purchasers: l.purchasers.map((r, idx) => (idx === row ? { ...r, ...p } : r)) }));
  const removePurchaser = (i: number, row: number) => {
    mapLot(i, (l) => ({ ...l, purchasers: l.purchasers.filter((_, idx) => idx !== row) }));
    setPending(null);
  };
  const addExpense = () => edit((d) => ({ ...d, expenses: [...d.expenses, emptyExpense()] }));
  const updateExpense = (i: number, p: Partial<ExpenseDraft>) => edit((d) => ({ ...d, expenses: d.expenses.map((x, idx) => (idx === i ? { ...x, ...p } : x)) }));
  const removeExpense = (i: number) => {
    edit((d) => ({ ...d, expenses: d.expenses.filter((_, idx) => idx !== i) }));
    setPending(null);
  };
  /** For a layout that lists every expense head: set (or clear) the amount of one head; a head with a predefined rate is just switched on or off. */
  const setHeadAmount = (headId: string, amount: string | null) =>
    edit((d) => {
      const at = d.expenses.findIndex((x) => x.expenseHeadId === headId);
      if (amount === null || (amount === "" && rates[headId] === undefined)) return at < 0 ? d : { ...d, expenses: d.expenses.filter((_, i) => i !== at) };
      if (at < 0) return { ...d, expenses: [...d.expenses, { ...emptyExpense(), expenseHeadId: headId, amount: rates[headId] === undefined ? amount : "" }] };
      return { ...d, expenses: d.expenses.map((x, i) => (i === at ? { ...x, amount: rates[headId] === undefined ? amount : "" } : x)) };
    });

  const requestRemoveLot = (i: number) => (lotHasData(draft.lots[i]) ? setPending({ kind: "lot", index: i }) : removeLot(i));
  const requestRemoveExpense = (i: number) => (expenseHasData(draft.expenses[i]) ? setPending({ kind: "expense", index: i }) : removeExpense(i));
  const requestRemovePurchaser = (i: number, row: number) =>
    purchaserHasData(draft.lots[i].purchasers[row]) ? setPending({ kind: "purchaser", index: i, row }) : removePurchaser(i, row);
  const cancel = () => (dirty ? setPending({ kind: "leave" }) : router.push("/"));

  function confirmPending() {
    if (!pending) return;
    if (pending.kind === "lot") removeLot(pending.index);
    else if (pending.kind === "expense") removeExpense(pending.index);
    else if (pending.kind === "purchaser") removePurchaser(pending.index, pending.row);
    else {
      setDirty(false);
      router.push("/");
    }
  }
  const dlg = {
    title:
      pending?.kind === "lot" ? fill(t("bill.confirmRemoveLotTitle"), { n: pending.index + 1 })
      : pending?.kind === "purchaser" ? t("bill.confirmRemovePurchaserTitle")
      : pending?.kind === "leave" ? t("bill.leaveTitle") : t("bill.confirmRemoveExpenseTitle"),
    text:
      pending?.kind === "lot" ? t("bill.confirmRemoveLotText")
      : pending?.kind === "purchaser" ? t("bill.confirmRemovePurchaserText")
      : pending?.kind === "leave" ? t("bill.leaveText") : t("bill.confirmRemoveExpenseText"),
    ok: pending?.kind === "leave" ? t("bill.leave") : t("common.remove"),
  };

  const warnings = draft.lots.flatMap((l, i) => allocationNotes(l).map((n) => ({ lot: i + 1, note: n })));
  const bhadaAmount = bhadaExpenseAmount(draft) === null ? null : draft.bhadaAmount;
  const problemTexts = problems.map((p) => problemText(t, p));

  return {
    t, lang, draft, patch, edit, items: items.data, heads: heads.data, rates, pieces, totals, problems, problemTexts, warnings,
    attempted, errors, truncated, saving, dirty, dateProblem: dateProblem ? problemText(t, dateProblem) : null,
    villageKnown, farmerKnown, buyerKnown, setBuyerKnown, farmersInVillage: farmersInVillage.data?.totalElements,
    bhadaHead, bhadaAmount,
    selectedIndex, setSelected, onSave, onVillage, onFarmer, cancel,
    addLot, duplicateLot, updateLot, requestRemoveLot,
    addPurchaser, duplicatePurchaser, updatePurchaser, requestRemovePurchaser,
    addExpense, updateExpense, requestRemoveExpense, setHeadAmount,
    // dialogs
    pending, setPending, confirmPending, dlg, dialog, setDialog, created,
  };
}

export type Desk = ReturnType<typeof useBillDesk>;
