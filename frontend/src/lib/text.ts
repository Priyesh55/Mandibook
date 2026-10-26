import type { Problem } from "./billDraft.ts";

/** Replaces {name} placeholders. Unknown placeholders are left visible so a missing value is noticed, not hidden. */
export function fill(template: string, vars?: Record<string, string | number>): string {
  if (!vars) return template;
  return template.replace(/\{(\w+)\}/g, (whole, name: string) => (name in vars ? String(vars[name]) : whole));
}

/** Dictionary key of the label of a field named in a validation message. */
const FIELD_LABELS: Record<string, string> = {
  pieces: "bill.pieces",
  weight: "bill.weight",
  rate: "bill.rate",
  lotValue: "bill.lotValue",
  value: "bill.value",
  commissionPercent: "bill.commissionPercent",
  commissionAmount: "bill.commissionAmount",
};

/** The text of a validation problem in the current language. */
export function problemText(t: (key: string) => string, p: Problem): string {
  const vars: Record<string, string> = { ...(p.vars ?? {}) };
  if (vars.field && FIELD_LABELS[vars.field]) vars.field = t(FIELD_LABELS[vars.field]);
  return fill(t(p.key), vars);
}
