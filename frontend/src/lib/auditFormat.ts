/**
 * Turns the stored (English, key=value) audit details of a bill into a localized, readable line.
 * Anything that does not match the expected shape is shown exactly as stored, never guessed at.
 */
const BILL_KEYS = ["billNo", "fy", "farmer", "village", "vehicle", "lots", "purchaserRows", "expenses", "calculationStatus", "ruleSet"] as const;

export function formatAuditDetails(details: string | null, t: (key: string) => string): string {
  if (!details) return "—";
  const out: string[] = [];
  for (const part of details.split("; ")) {
    const i = part.indexOf("=");
    if (i < 0) return details;
    const key = part.slice(0, i);
    if (!(BILL_KEYS as readonly string[]).includes(key)) return details;
    if (key === "fy") continue;   // a bill shows its date, not its financial year
    let value = part.slice(i + 1).replace(/^'(.*)'$/, "$1");
    if (value === "-") value = "—";
    if (key === "calculationStatus") value = t(`calc.${value}`);
    out.push(`${t(`audit.field.${key}`)}: ${value}`);
  }
  return out.join(" · ");
}
