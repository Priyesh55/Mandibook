"use client";

import { useEffect, useId, useRef } from "react";

interface Props {
  open: boolean;
  title: string;
  message: string;
  confirmLabel: string;
  cancelLabel: string;
  onConfirm: () => void;
  onCancel: () => void;
  testId?: string;
}

/** Native modal dialog (focus trap and Esc handling come from the browser). Cancel is focused first. */
export function ConfirmDialog({ open, title, message, confirmLabel, cancelLabel, onConfirm, onCancel, testId }: Props) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();

  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    if (!open && d.open) d.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      className="confirm"
      aria-labelledby={titleId}
      data-testid={testId ?? "confirm-dialog"}
      onCancel={(e) => {
        e.preventDefault(); // Esc: let the parent decide, so state and DOM never disagree
        onCancel();
      }}
    >
      <h2 id={titleId}>{title}</h2>
      <p>{message}</p>
      <div className="actions">
        <button type="button" className="secondary" autoFocus onClick={onCancel} data-testid="confirm-cancel">
          {cancelLabel}
        </button>
        <button type="button" className="danger-solid" onClick={onConfirm} data-testid="confirm-ok">
          {confirmLabel}
        </button>
      </div>
    </dialog>
  );
}
