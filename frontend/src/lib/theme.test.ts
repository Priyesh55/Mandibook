import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

// Reads the real stylesheet so the test cannot drift from what ships.
const css = readFileSync(new URL("../app/globals.css", import.meta.url), "utf8");
const root = css.slice(css.indexOf(":root"), css.indexOf("}", css.indexOf(":root")));
const tokens = new Map<string, string>();
for (const m of root.matchAll(/--([a-z-]+):\s*(#[0-9a-fA-F]{6})\s*;/g)) tokens.set(m[1], m[2]);

function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((c) =>
    c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4,
  );
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(fg: string, bg: string): number {
  const a = luminance(tokens.get(fg)!);
  const b = luminance(tokens.get(bg)!);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

// WCAG AA for normal text is 4.5:1.
const PAIRS: [string, string][] = [
  // body text on every surface it can appear on
  ["text", "page"], ["text", "surface"], ["text", "surface-alt"], ["text", "blue-pale"], ["text", "blue-pale-strong"],
  ["muted", "page"], ["muted", "surface"], ["muted", "surface-alt"], ["muted", "blue-pale"],
  // links and key headings
  ["blue", "page"], ["blue", "surface"], ["blue", "blue-pale"], ["blue", "blue-pale-strong"], ["blue", "surface-alt"],
  ["blue-deep", "page"], ["blue-deep", "surface"], ["blue-deep", "blue-pale"], ["blue-deep", "blue-pale-strong"],
  // buttons
  ["blue-ink", "blue"], ["blue-ink", "blue-hover"],
  // navigation bar: brand, links, hover, active link, call-to-action
  ["nav-text", "nav"], ["nav-muted", "nav"], ["nav-muted", "nav-hover"], ["nav-text", "nav-hover"],
  ["nav", "blue-pale"], ["nav", "surface"], ["nav", "blue-pale-strong"],
  // status colours
  ["green", "green-pale"], ["green", "surface"],
  ["amber", "amber-pale"],
  ["red", "red-pale"], ["red", "surface"], ["red-dark", "red-pale"],
];

test("stylesheet defines the deep-blue / white / blue-gray tokens", () => {
  for (const name of ["page", "surface", "nav", "blue", "blue-deep", "blue-pale", "green", "amber", "red", "text"]) {
    assert.ok(tokens.has(name), `missing token --${name}`);
  }
  assert.equal(tokens.get("surface"), "#ffffff", "work surfaces must be white");
});

test("page background is a soft blue-gray (light, blue-dominant), not white and not dark", () => {
  const hex = tokens.get("page")!;
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
  assert.ok(b > g && g > r, "blue must dominate");
  assert.ok(luminance(hex) > 0.75 && luminance(hex) < 0.95, "must be light but distinguishable from white");
});

test("navigation is a deep blue (dark enough for white text)", () => {
  assert.ok(luminance(tokens.get("nav")!) < 0.05);
  assert.ok(contrast("nav-text", "nav") >= 7, "white on navigation should exceed AAA");
});

test("all text/background token pairs meet WCAG AA contrast (4.5:1)", () => {
  for (const [fg, bg] of PAIRS) {
    const ratio = contrast(fg, bg);
    assert.ok(ratio >= 4.5, `--${fg} on --${bg} is ${ratio.toFixed(2)}:1 (needs 4.5)`);
  }
});

test("white text on the destructive solid button has AA contrast", () => {
  const white = luminance("#ffffff");
  for (const bg of ["red", "red-dark"]) {
    const ratio = (white + 0.05) / (luminance(tokens.get(bg)!) + 0.05);
    assert.ok(ratio >= 4.5, `white on --${bg} is ${ratio.toFixed(2)}:1`);
  }
});

test("focus indicator uses a visible outline and the dark-mode override is gone", () => {
  assert.match(css, /:focus-visible\s*\{[^}]*outline:\s*3px solid/);
  assert.doesNotMatch(css, /prefers-color-scheme:\s*dark/, "design is intentionally light only");
});

test("palette keeps to blue plus green/amber/red status colours (no stray hues)", () => {
  // Every hex colour used outside :root must be one of the allowed neutrals/white/black or a token value.
  const known = new Set([...tokens.values()].map((v) => v.toLowerCase()));
  const allowedLiteral = new Set(["#ffffff", "#fff", "#000", "#eee", "#999", "#ccc"]);
  const rest = css.slice(css.indexOf("}", css.indexOf(":root")));
  for (const m of rest.matchAll(/#[0-9a-fA-F]{3,6}\b/g)) {
    const v = m[0].toLowerCase();
    assert.ok(known.has(v) || allowedLiteral.has(v), `unexpected colour ${v} outside the token list`);
  }
});
