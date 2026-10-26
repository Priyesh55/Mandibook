import assert from "node:assert/strict";
import { test } from "node:test";
import { formatScaled, mulDivRound, mulRound, parseScaled, percentRound, roundTo, sumScaled } from "./decimal.ts";

test("parseScaled reads typed decimals exactly and rejects everything else", () => {
  assert.equal(parseScaled("12.5"), BigInt(12500000));
  assert.equal(parseScaled("0.000001"), BigInt(1));
  assert.equal(parseScaled("1800"), BigInt(1800000000));
  assert.equal(parseScaled(75.5), BigInt(75500000));
  for (const bad of ["", "  ", "abc", "-1", "1.1234567", "1e3", null, undefined]) assert.equal(parseScaled(bad as string), null, String(bad));
});

test("sums are exact where floating point is not", () => {
  assert.notEqual(0.1 + 0.2, 0.3);
  assert.equal(formatScaled(sumScaled([parseScaled("0.1"), parseScaled("0.2")])), "0.3");
  assert.equal(formatScaled(sumScaled([parseScaled("90"), parseScaled("90"), parseScaled("100"), parseScaled("75.50"), parseScaled("1000")])), "1355.5");
  assert.equal(formatScaled(sumScaled([null, parseScaled("5")])), "5");
});

test("a difference can be negative and is formatted without trailing zeros", () => {
  const gross = sumScaled([parseScaled("18000"), parseScaled("22500")]);
  const expenses = sumScaled([parseScaled("1355.50")]);
  assert.equal(formatScaled(gross - expenses), "39144.5");
  assert.equal(formatScaled(expenses - gross), "-39144.5");
  assert.equal(formatScaled(BigInt(0)), "0");
});

const p = (s: string) => parseScaled(s) as bigint;

test("weight x rate is rounded half-up to the rupee, like the server", () => {
  assert.equal(formatScaled(mulRound(p("1000.5"), p("18.25"), 0)), "18259");   // 18259.125
  assert.equal(formatScaled(mulRound(p("333"), p("19.37"), 0)), "6450");       // 6450.21
  assert.equal(formatScaled(mulRound(p("2.5"), p("1"), 0)), "3");              // a tie goes up
  assert.equal(formatScaled(mulRound(p("0"), p("18"), 0)), "0");
});

test("pieces x rate per piece keeps two decimals", () => {
  assert.equal(formatScaled(mulRound(p("35"), p("2.5"), 2)), "87.5");
  assert.equal(formatScaled(mulRound(p("35"), p("1.75"), 2)), "61.25");
  assert.equal(formatScaled(mulRound(p("3"), p("0.333"), 2)), "1");            // 0.999
  assert.equal(formatScaled(mulRound(p("1"), p("0.005"), 2)), "0.01");         // a tie goes up
});

test("commission is 5 percent to one decimal and the net is a whole rupee", () => {
  assert.equal(formatScaled(percentRound(p("10800"), 5, 1)), "540");
  assert.equal(formatScaled(percentRound(p("6450"), 5, 1)), "322.5");
  assert.equal(formatScaled(percentRound(p("3"), 5, 1)), "0.2");               // 0.15 goes up
  assert.equal(formatScaled(roundTo(p("39173.65"), 0)), "39174");
  assert.equal(formatScaled(roundTo(p("39173.5"), 0)), "39174");
  assert.equal(formatScaled(roundTo(-p("0.5"), 0)), "-1");                     // away from zero, like BigDecimal HALF_UP
});

test("a rate per 100 kg times a weight in kg: one rounding, at the end", () => {
  assert.equal(formatScaled(mulDivRound(p("250"), p("3000"), 100, 0)), "7500");      // onion at 30 a kg is entered as 3000
  assert.equal(formatScaled(mulDivRound(p("1000.5"), p("1825"), 100, 0)), "18259");  // 18259.125
  assert.equal(formatScaled(mulDivRound(p("333"), p("1937"), 100, 0)), "6450");      // 6450.21
  assert.equal(formatScaled(mulDivRound(p("2.5"), p("100"), 100, 0)), "3");          // 2.5 goes up
  assert.equal(formatScaled(mulDivRound(p("0"), p("3000"), 100, 0)), "0");
});
