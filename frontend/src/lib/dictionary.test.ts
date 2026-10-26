import assert from "node:assert/strict";
import { test } from "node:test";
import { dict } from "./dictionary.ts";
import { nameOf, num, toNumberOrNull } from "./format.ts";

test("English and Hindi dictionaries have exactly the same keys", () => {
  assert.deepEqual(Object.keys(dict.hi).sort(), Object.keys(dict.en).sort());
});

test("no dictionary value is empty", () => {
  for (const lang of ["en", "hi"] as const) {
    for (const [key, value] of Object.entries(dict[lang])) {
      assert.ok(value.trim().length > 0, `${lang}.${key} is empty`);
    }
  }
});

test("unverified-calculation notices exist in both languages and say so", () => {
  assert.match(dict.en["calculation.demoUnverified"], /NOT VERIFIED/);
  assert.match(dict.hi["calculation.demoUnverified"], /सत्यापित नहीं/);
  assert.match(dict.en["calculation.notCalculated"], /not calculated/i);
  assert.match(dict.hi["calculation.notCalculated"], /गणना नहीं/);
});

test("num() shows stored values exactly as entered: no rounding or grouping", () => {
  assert.equal(num(1000.5), "1000.5");
  assert.equal(num(18.25), "18.25");
  assert.equal(num(1234567.891), "1234567.891");
  assert.equal(num(null), "—");
});

test("nameOf() falls back to the English name when no Hindi name exists", () => {
  assert.equal(nameOf({ name: "Onion", nameHi: "प्याज़" }, "hi"), "प्याज़");
  assert.equal(nameOf({ name: "Onion", nameHi: null }, "hi"), "Onion");
  assert.equal(nameOf({ name: "Onion", nameHi: "प्याज़" }, "en"), "Onion");
  assert.equal(nameOf(null, "en"), "—");
});

test("toNumberOrNull() maps blanks to null and keeps decimals", () => {
  assert.equal(toNumberOrNull(""), null);
  assert.equal(toNumberOrNull("  "), null);
  assert.equal(toNumberOrNull("19.37"), 19.37);
  assert.equal(toNumberOrNull("abc"), null);
});
