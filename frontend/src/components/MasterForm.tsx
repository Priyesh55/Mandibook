"use client";

import { useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { useI18n } from "@/lib/i18n";
import type { Master, MasterType } from "@/lib/types";
import { MasterPicker } from "./MasterPicker";

interface Props {
  type: MasterType;
  /** The record being edited, or null to add a new one. */
  editing: Master | null;
  /** Farmers added from a bill: the village is the bill's village and cannot be changed here. */
  lockedVillage?: Master | null;
  layout: "inline" | "dialog";
  onSaved: (saved: Master, wasNew: boolean) => void;
  onCancel?: () => void;
  /** Reports that a request is in flight, so a dialog can refuse to close under it. */
  onBusy?: (busy: boolean) => void;
}

/**
 * The ONE form that adds or edits a master record, used by the masters pages (inline) and by the dialogs of the bill (modal).
 * It sends the same request to the same API, so the server's validation is the only validation of record; the checks here only
 * save a round trip. Farmers also carry their village (required here: the bill is village first).
 */
export function MasterForm({ type, editing, lockedVillage = null, layout, onSaved, onCancel, onBusy }: Props) {
  const { t, lang } = useI18n();
  const [name, setName] = useState(editing?.name ?? "");
  const [nameHi, setNameHi] = useState(editing?.nameHi ?? "");
  const [villageId, setVillageId] = useState(lockedVillage ? String(lockedVillage.id) : editing?.villageId ? String(editing.villageId) : "");
  const [villageKnown, setVillageKnown] = useState<Master | null>(
    lockedVillage ?? (editing?.villageId ? { id: editing.villageId, name: editing.villageName ?? "", nameHi: null, active: true } : null),
  );
  const [rate, setRate] = useState(editing?.rate !== null && editing?.rate !== undefined ? String(editing.rate) : "");
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);
  const isFarmer = type === "farmers";
  const isHead = type === "expense-heads";

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (inFlight.current) return;          // a double click or a repeated Enter never sends a second request
    setError(null);
    setFields({});
    if (name.trim() === "") {
      // caught here so an empty form costs no request; the server checks the name again
      setFields({ name: t("master.err.name"), ...(isFarmer && villageId === "" ? { villageId: t("master.err.village") } : {}) });
      setError(t("master.err.name"));
      return;
    }
    if (isFarmer && villageId === "") {
      setFields({ villageId: t("master.err.village") });
      setError(t("master.err.village"));
      return;
    }
    if (isHead && rate.trim() !== "" && !/^\d+(\.\d{1,4})?$/.test(rate.trim())) {
      setFields({ rate: t("master.err.rate") });
      setError(t("master.err.rate"));
      return;
    }
    inFlight.current = true;
    setBusy(true);
    onBusy?.(true);
    try {
      const body = {
        name, nameHi: nameHi.trim() === "" ? null : nameHi,
        ...(isFarmer ? { villageId: Number(villageId) } : {}),
        ...(isHead ? { rateBasis: rate.trim() === "" ? null : "PER_PIECE", rate: rate.trim() === "" ? null : Number(rate) } : {}),
      };
      const saved = editing === null
        ? await api<Master>(`/masters/${type}`, lang, { method: "POST", body })
        : await api<Master>(`/masters/${type}/${editing.id}`, lang, { method: "PUT", body });
      onSaved(saved, editing === null);
    } catch (err) {
      if (err instanceof ApiError && err.fieldErrors.length) {
        setFields(Object.fromEntries(err.fieldErrors.map((f) => [f.field, f.message])));
        setError(err.fieldErrors.map((f) => f.message).join(" "));
      } else {
        setError((err as Error).message);
      }
    } finally {
      inFlight.current = false;
      setBusy(false);
      onBusy?.(false);
    }
  }

  const nameField = (
    <label className="grow">
      <span>{t("common.name")} <em className="req">{t("master.required")}</em></span>
      <input value={name} onChange={(e) => setName(e.target.value)} data-testid="master-name" required aria-invalid={Boolean(fields.name)} maxLength={200} autoFocus={layout === "dialog"} />
      {fields.name && <span className="field-error" role="alert">{fields.name}</span>}
    </label>
  );
  const nameHiField = (
    <label className="grow">
      <span>{t("common.nameHi")}</span>
      <input value={nameHi} onChange={(e) => setNameHi(e.target.value)} data-testid="master-name-hi" lang="hi" maxLength={200} aria-invalid={Boolean(fields.nameHi)} />
      {fields.nameHi && <span className="field-error" role="alert">{fields.nameHi}</span>}
    </label>
  );
  const villageField = isFarmer && (
    <label className="grow">
      <span>{t("bill.village")} <em className="req">{t("master.required")}</em></span>
      {lockedVillage ? (
        <output className="locked-value" data-testid="master-village-locked">{lockedVillage.name}</output>
      ) : (
        <MasterPicker
          type="villages" value={villageId} known={villageKnown}
          onChange={(id, m) => { setVillageId(id); setVillageKnown(m); }}
          testId="master-village" ariaLabel={t("bill.village")}
        />
      )}
      {fields.villageId && <span className="field-error" role="alert" data-testid="master-village-error">{fields.villageId}</span>}
    </label>
  );
  const rateField = isHead && (
    <label className="grow">
      <span>{t("master.rateLabel")}</span>
      <input type="number" min="0" step="any" value={rate} onChange={(e) => setRate(e.target.value)} data-testid="master-rate" aria-invalid={Boolean(fields.rate)} />
      {fields.rate && <span className="field-error" role="alert">{fields.rate}</span>}
    </label>
  );
  const buttons = (
    <div className={layout === "dialog" ? "dialog-actions" : "row"}>
      {layout === "dialog" && onCancel && (
        <button type="button" className="secondary" onClick={onCancel} disabled={busy} data-testid="master-cancel">{t("common.cancel")}</button>
      )}
      <button type="submit" disabled={busy} data-testid="master-save">
        {busy ? t("bill.saving") : editing === null ? (layout === "dialog" ? t("common.save") : t("common.add")) : t("common.save")}
      </button>
      {layout === "inline" && editing !== null && onCancel && (
        <button type="button" className="secondary" onClick={onCancel}>{t("common.cancel")}</button>
      )}
    </div>
  );

  return (
    <form onSubmit={submit} className={layout === "dialog" ? "master-form stacked" : "master-form row"} data-testid="master-form" noValidate={layout === "dialog"}>
      {nameField}
      {nameHiField}
      {villageField}
      {rateField}
      {buttons}
      {isHead && <p className="hint full" data-testid="master-rate-hint">{t("master.rateHint")}</p>}
      {error && (layout === "inline" || Object.keys(fields).length === 0) && <div className="error full" role="alert" data-testid="master-error">{error}</div>}
    </form>
  );
}
