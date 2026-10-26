"use client";

import { useEffect, useId, useRef, useState } from "react";
import { api } from "@/lib/api";
import { nameOf } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import type { Master, MasterType, Page } from "@/lib/types";

const PAGE = 20;

/**
 * Search-as-you-type selection of a master record (farmer, buyer, village). It asks the server for ONE PAGE of matching,
 * active records per search, so a record is never missing just because a list is long (the old pick-lists were cut off at a
 * server-side cap). Behaviour:
 *   - typing searches after a short pause; an answer that arrives after a newer search was started is ignored;
 *   - Arrow keys move through the matches, Enter picks the highlighted one, Escape closes;
 *   - when the list is closed Enter behaves as before (it moves to the next field of the grid);
 *   - the chosen record stays selected while you type another search and is restored in the box when you leave it;
 *   - "required" fields are validated like any other required field of the form.
 * Only an id from the server's own answer can be chosen, never free text.
 */
export function MasterPicker(props: {
  type: MasterType;
  value: string;
  onChange: (id: string, master: Master | null) => void;
  testId: string;
  ariaLabel: string;
  required?: boolean;
  nav?: boolean;
  /** Extra search parameters, for example `villageId=3` (farmers of one village). A different value starts a new search. */
  query?: string;
  disabled?: boolean;
  placeholder?: string;
  /** A record chosen elsewhere (for example one just created in a dialog), so its name can be shown for the value. */
  known?: Master | null;
}) {
  const { t, lang } = useI18n();
  const { type, value, onChange, query = "" } = props;
  const listId = useId();
  const [text, setText] = useState("");          // what is typed while searching
  const [typing, setTyping] = useState(false);
  const [chosen, setChosen] = useState<Master | null>(null);
  const [open, setOpen] = useState(false);
  const [rows, setRows] = useState<Master[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  const [highlight, setHighlight] = useState(-1);
  const input = useRef<HTMLInputElement>(null);
  const latest = useRef(0);                       // number of the newest search: older answers are dropped

  // a value set from outside (cleared by the form, for example) drops the remembered record
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (value === "") setChosen(null);
  }, [value]);

  useEffect(() => {
    if (!open) return;
    const mine = ++latest.current;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    const timer = setTimeout(() => {
      api<Page<Master>>(`/masters/${type}/page?activeOnly=true&q=${encodeURIComponent(typing ? text : "")}&page=0&size=${PAGE}${query ? `&${query}` : ""}`, lang)
        .then((page) => {
          if (mine !== latest.current) return;     // a newer search exists: this answer is stale
          setRows(page.content);
          setTotal(page.totalElements);
          setFailed(false);
          setHighlight(page.content.length > 0 ? 0 : -1);
          setLoading(false);
        })
        .catch(() => {
          if (mine !== latest.current) return;
          setFailed(true);
          setLoading(false);
        });
    }, typing ? 200 : 0);
    return () => clearTimeout(timer);
  }, [open, typing, text, type, lang, query]);

  useEffect(() => {
    input.current?.setCustomValidity(props.required && value === "" ? t("common.select") : "");
  }, [props.required, value, t]);

  function pick(m: Master) {
    setChosen(m);
    setTyping(false);
    setText("");
    setOpen(false);
    onChange(String(m.id), m);
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      if (!open) setOpen(true);
      else setHighlight((h) => Math.min(rows.length - 1, h + 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setHighlight((h) => Math.max(0, h - 1));
    } else if (e.key === "Enter" && open && highlight >= 0 && rows[highlight]) {
      e.preventDefault();
      e.stopPropagation();      // pick, do not move to the next field of the grid
      pick(rows[highlight]);
    } else if (e.key === "Escape" && open) {
      e.preventDefault();
      e.stopPropagation();
      setOpen(false);
      setTyping(false);
      setText("");
    }
  }

  const known = props.known && String(props.known.id) === value ? props.known : null;
  const shown = typing ? text : chosen && String(chosen.id) === value ? nameOf(chosen, lang) : known ? nameOf(known, lang) : "";

  return (
    <span className="picker" style={{ position: "relative", display: "block" }}>
      <input
        ref={input}
        role="combobox"
        aria-expanded={open}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={open && highlight >= 0 ? `${listId}-${highlight}` : undefined}
        aria-label={props.ariaLabel}
        autoComplete="off"
        placeholder={props.placeholder ?? t("common.select")}
        disabled={props.disabled}
        value={shown}
        onChange={(e) => {
          if (props.disabled) return;
          setTyping(true);
          setText(e.target.value);
          setOpen(true);
        }}
        onClick={() => { if (!props.disabled) setOpen(true); }}
        onBlur={(e) => {
          // leaving the box (not into the list): close, keep the chosen record, forget the half-typed search
          if (!e.relatedTarget || !(e.currentTarget.parentElement?.contains(e.relatedTarget as Node))) {
            setOpen(false);
            setTyping(false);
            setText("");
          }
        }}
        onKeyDown={onKeyDown}
        data-nav={props.nav ? "" : undefined}
        data-testid={props.testId}
        data-selected-id={value}
      />
      {open && (
        <ul id={listId} role="listbox" className="picker-list" data-testid="picker-list">
          {rows.map((m, i) => (
            <li
              key={m.id}
              id={`${listId}-${i}`}
              role="option"
              aria-selected={String(m.id) === value}
              data-active={i === highlight}
              data-testid="picker-option"
              onMouseDown={(e) => e.preventDefault()}   // keep focus in the box so blur does not close first
              onClick={() => pick(m)}
            >
              {nameOf(m, lang)}
            </li>
          ))}
          {!loading && !failed && rows.length === 0 && <li className="muted" role="presentation" data-testid="picker-empty">{t("common.noRecords")}</li>}
          {!loading && !failed && total > rows.length && (
            <li className="muted" role="presentation" data-testid="picker-more">
              {t("picker.more").replace("{shown}", String(rows.length)).replace("{total}", String(total))}
            </li>
          )}
          {loading && <li className="muted" role="presentation">{t("common.loading")}</li>}
          {failed && <li className="error" role="alert" data-testid="picker-failed">{t("picker.failed")}</li>}
        </ul>
      )}
    </span>
  );
}
