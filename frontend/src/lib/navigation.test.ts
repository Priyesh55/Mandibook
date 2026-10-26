import assert from "node:assert/strict";
import { test } from "node:test";
import { dict } from "./dictionary.ts";
import { activeGroupId, breadcrumbsFor, NAV_GROUPS, titleKeyFor } from "./navigation.ts";

const items = NAV_GROUPS.flatMap((g) => g.items);

// Routes that have a real page in src/app. A menu link must point at one of these.
const ROUTES = [/^\/$/, /^\/bills\/new$/, /^\/masters\/(farmers|buyers|villages|items|expense-heads)$/, /^\/settings\/firm$/];

test("every available item links to a route that exists; unavailable items have no link at all", () => {
  for (const i of items) {
    if (i.status === "available") {
      assert.ok(i.href, `${i.id} is available but has no href`);
      assert.ok(i.isCurrent, `${i.id} has no active-page rule`);
      assert.ok(ROUTES.some((r) => r.test((i.href as string).split("?")[0])), `${i.id} points at ${i.href}, which is not a known route`);
    } else {
      assert.equal(i.href, undefined, `${i.id} is unavailable and must not have a placeholder route`);
    }
  }
});

test("item ids and links are unique", () => {
  const ids = items.map((i) => i.id);
  assert.equal(new Set(ids).size, ids.length);
  const hrefs = items.flatMap((i) => (i.href ? [i.href] : []));
  assert.equal(new Set(hrefs).size, hrefs.length);
});

test("every menu label exists in English and Hindi", () => {
  const keys = [...NAV_GROUPS.map((g) => g.labelKey), ...items.map((i) => i.labelKey)];
  for (const k of keys) {
    assert.ok(k in dict.en, `missing English text for ${k}`);
    assert.ok(k in dict.hi, `missing Hindi text for ${k}`);
  }
});

test("deferred accounting features stay marked unavailable (CLAUDE.md rule 5)", () => {
  for (const id of ["ledger", "trial-balance", "journal", "receipt", "payment", "cash-bank", "rate-difference"]) {
    assert.equal(items.find((i) => i.id === id)?.status, "unavailable", `${id} must not be linked until the rule is confirmed`);
  }
});

test("exactly one item is marked current on each known page", () => {
  for (const path of ["/", "/bills/new", "/bills/7", "/bills/7/print", "/masters/farmers", "/masters/buyers", "/masters/villages", "/masters/items", "/masters/expense-heads", "/settings/firm"]) {
    const current = items.filter((i) => i.isCurrent?.(path));
    assert.equal(current.length, 1, `${path}: ${current.map((c) => c.id).join(",") || "none"}`);
  }
});

test("New bill is not also highlighted as Aadat bills", () => {
  const bills = items.find((i) => i.id === "bills");
  assert.equal(bills?.isCurrent?.("/bills/new"), false);
  assert.equal(bills?.isCurrent?.("/bills/12"), true);
  assert.equal(bills?.isCurrent?.("/"), true);
});

test("the active group follows the page", () => {
  assert.equal(activeGroupId("/"), "mandi-book");
  assert.equal(activeGroupId("/bills/new"), "mandi-book");
  assert.equal(activeGroupId("/masters/farmers"), "farmers-buyers");
  assert.equal(activeGroupId("/masters/buyers"), "farmers-buyers");
  assert.equal(activeGroupId("/masters/villages"), "masters");
  assert.equal(activeGroupId("/nowhere"), null);
});

test("breadcrumbs", () => {
  const labels = (p: string) => breadcrumbsFor(p).map((c) => c.labelKey);
  assert.deepEqual(labels("/"), ["nav.group.mandiBook", "nav.bills"]);
  assert.deepEqual(labels("/bills/new"), ["nav.group.mandiBook", "nav.bills", "nav.newBill"]);
  assert.deepEqual(labels("/bills/5"), ["nav.group.mandiBook", "nav.bills", "bill.viewTitle"]);
  assert.deepEqual(labels("/bills/5/print"), ["nav.group.mandiBook", "nav.bills", "bill.viewTitle", "print.title"]);
  assert.deepEqual(labels("/masters/farmers"), ["nav.group.farmersBuyers", "masters.farmers"]);
  assert.deepEqual(labels("/masters/items"), ["nav.masters", "masters.items"]);
  assert.deepEqual(labels("/nowhere"), []);
  // Only the parent bills crumb is a link; the current page is plain text.
  assert.equal(breadcrumbsFor("/bills/new")[1].href, "/");
  assert.equal(breadcrumbsFor("/bills/new")[2].href, undefined);
});

test("tab titles: every known page has a title in both languages, English matches the server-rendered titles", () => {
  const expectedEnglish: Record<string, string> = {
    "/": "Aadat bills",
    "/bills/new": "New Aadat bill",
    "/bills/9": "Aadat bill",
    "/bills/9/print": "Print Aadat bill (draft)",
    "/masters/farmers": "Farmers",
    "/masters/buyers": "Buyers",
    "/masters/villages": "Villages",
    "/masters/items": "Items",
    "/masters/expense-heads": "Expense heads",
  };
  for (const [path, english] of Object.entries(expectedEnglish)) {
    const key = titleKeyFor(path);
    assert.ok(key, `${path} has no title key`);
    assert.equal(dict.en[key], english, path);
    assert.ok(dict.hi[key].trim().length > 0, `${path} has no Hindi title`);
  }
  assert.equal(titleKeyFor("/nowhere"), null);
});

test("the heading of a master page can be the master's own name (no generic Masters heading needed)", () => {
  for (const type of ["farmers", "buyers", "villages", "items", "expense-heads"]) {
    const crumbs = breadcrumbsFor(`/masters/${type}`);
    assert.equal(crumbs[crumbs.length - 1].labelKey, `masters.${type}`);
    assert.equal(titleKeyFor(`/masters/${type}`), `masters.${type}`);
  }
});
