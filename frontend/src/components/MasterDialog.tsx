"use client";

import { useEffect, useId, useRef, useState } from "react";
import { useI18n } from "@/lib/i18n";
import type { Master, MasterType } from "@/lib/types";
import { MasterForm } from "./MasterForm";

interface Props {
  open: boolean;
  type: MasterType;
  /** Adding a farmer from a bill: the bill's village (fixed). */
  lockedVillage?: Master | null;
  onClose: () => void;
  onCreated: (created: Master) => void;
}

const TITLES: Partial<Record<MasterType, "dialog.addFarmer" | "dialog.addVillage" | "dialog.addBuyer">> = {
  farmers: "dialog.addFarmer",
  villages: "dialog.addVillage",
  buyers: "dialog.addBuyer",
};

/**
 * A centered modal that adds a master record without leaving the bill. It reuses the masters form (same API, same server
 * validation). Cancelling, pressing Escape or a failed save leaves the bill exactly as it was; a saved record is handed back to
 * the bill, which selects it. The browser's modal dialog traps focus and returns it to the button that opened it.
 */
export function MasterDialog({ open, type, lockedVillage = null, onClose, onCreated }: Props) {
  const { t } = useI18n();
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const [busy, setBusy] = useState(false);
  const titleKey = TITLES[type] ?? "dialog.addFarmer";

  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) {
      d.showModal();
      // the browser focuses the first control (the close button); the operator should land in the first field
      d.querySelector<HTMLElement>("[data-testid=master-name]")?.focus();
    }
    if (!open && d.open) d.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      className="dialog master-dialog"
      aria-labelledby={titleId}
      data-testid={`master-dialog-${type}`}
      onCancel={(e) => {              // Escape
        e.preventDefault();
        if (!busy) onClose();
      }}
      onClick={(e) => { if (e.target === ref.current && !busy) onClose(); }}
    >
      {open && (
        <div className="dialog-body">
          <header className="dialog-head">
            <h2 id={titleId}>{t(titleKey)}</h2>
            <button type="button" className="icon-button" onClick={onClose} disabled={busy} aria-label={t("common.cancel")} data-testid="master-dialog-close">✕</button>
          </header>
          <p className="muted small-text">{type === "farmers" ? t("dialog.farmerIntro") : t("masters.hint")}</p>
          <MasterForm
            type={type} editing={null} lockedVillage={lockedVillage} layout="dialog"
            onBusy={setBusy} onCancel={onClose} onSaved={(created) => onCreated(created)}
          />
        </div>
      )}
    </dialog>
  );
}
