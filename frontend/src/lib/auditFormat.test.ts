import assert from "node:assert/strict";
import { test } from "node:test";
import { formatAuditDetails } from "./auditFormat.ts";
import { dict } from "./dictionary.ts";

const tr = (lang: "en" | "hi") => (key: string) => (dict[lang] as Record<string, string>)[key] ?? key;
const STORED =
  "farmer='Test Farmer'; village=-; vehicle='TN-01'; lots=2; expenses=1; calculationStatus=NOT_CALCULATED; ruleSet=NONE";

test("bill creation details are shown in English", () => {
  assert.equal(
    formatAuditDetails(STORED, tr("en")),
    "Farmer: Test Farmer · Village: — · Vehicle number: TN-01 · Lots: 2 · Expenses: 1 · Calculation status: Not calculated · Calculation rule set: NONE",
  );
});

test("bill creation details are shown in Hindi, with no raw English field names or enum values", () => {
  const hi = formatAuditDetails(STORED, tr("hi"));
  assert.match(hi, /किसान: Test Farmer/);
  assert.match(hi, /लॉट: 2/);
  assert.match(hi, /गणना नहीं की गई/);
  assert.doesNotMatch(hi, /calculationStatus|NOT_CALCULATED|ruleSet=|lots=/);
});

test("demonstration status is translated too", () => {
  const hi = formatAuditDetails(STORED.replace("NOT_CALCULATED", "DEMO_UNVERIFIED"), tr("hi"));
  assert.match(hi, /डेमो \(असत्यापित\)/);
});

test("unexpected shapes are shown exactly as stored (never guessed)", () => {
  assert.equal(formatAuditDetails("from name='A' to name='B'", tr("en")), "from name='A' to name='B'");
  assert.equal(formatAuditDetails("farmer='A; B'; lots=1", tr("en")), "farmer='A; B'; lots=1"); // name containing "; "
  assert.equal(formatAuditDetails(null, tr("en")), "—");
});

test("every audit field label exists in both languages", () => {
  for (const key of ["farmer", "village", "vehicle", "lots", "expenses", "calculationStatus", "ruleSet"]) {
    assert.ok(dict.en[`audit.field.${key}` as keyof typeof dict.en], `en audit.field.${key}`);
    assert.ok(dict.hi[`audit.field.${key}` as keyof typeof dict.hi], `hi audit.field.${key}`);
  }
});
