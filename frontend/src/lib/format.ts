import type { Lang, MasterRef } from "./types";

/** Display a stored value exactly as entered: no rounding, no digit grouping, no unit conversion. */
export function num(value: number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  return String(value);
}

/** Localized name with English fallback. */
export function nameOf(ref: MasterRef | { name: string; nameHi: string | null } | null, lang: Lang): string {
  if (!ref) return "—";
  return lang === "hi" && ref.nameHi ? ref.nameHi : ref.name;
}

export function todayIso(): string {
  const d = new Date();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${m}-${day}`;
}

/** Empty string -> null, otherwise a finite number (or null if unparsable). */
export function toNumberOrNull(s: string): number | null {
  if (s.trim() === "") return null;
  const n = Number(s);
  return Number.isFinite(n) ? n : null;
}
