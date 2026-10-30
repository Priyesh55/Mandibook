import type { MessageKey } from "./dictionary";

// The Mandi Book menu. One list drives the menu, the active-page marker and the breadcrumbs.
// An item is "available" only if a working screen exists behind its route. Everything else is
// "unavailable": shown as a disabled label, never as a link, and never given a placeholder route.
export type NavStatus = "available" | "unavailable";

export interface NavItem {
  id: string;
  labelKey: MessageKey;
  status: NavStatus;
  /** Route of the working screen. Present exactly when status is "available". */
  href?: string;
  /** True when this item is the current page for the given pathname. */
  isCurrent?: (path: string) => boolean;
}

export interface NavGroup {
  id: string;
  labelKey: MessageKey;
  items: NavItem[];
}

const exact = (href: string) => (path: string) => path === href;
const isBillPage = (path: string) => /^\/bills\/(?!new(\/|$))[^/]+/.test(path);

export const NAV_GROUPS: NavGroup[] = [
  {
    id: "mandi-book",
    labelKey: "nav.group.mandiBook",
    items: [
      { id: "bills", labelKey: "nav.bills", status: "available", href: "/", isCurrent: (p) => p === "/" || isBillPage(p) },
      { id: "new-bill", labelKey: "nav.newBill", status: "available", href: "/bills/new", isCurrent: exact("/bills/new") },
      { id: "rate-difference", labelKey: "nav.item.rateDifference", status: "unavailable" },
    ],
  },
  {
    id: "farmers-buyers",
    labelKey: "nav.group.farmersBuyers",
    items: [
      { id: "farmers", labelKey: "masters.farmers", status: "available", href: "/masters/farmers", isCurrent: exact("/masters/farmers") },
      { id: "buyers", labelKey: "masters.buyers", status: "available", href: "/masters/buyers", isCurrent: exact("/masters/buyers") },
    ],
  },
  {
    id: "masters",
    labelKey: "nav.masters",
    items: [
      { id: "villages", labelKey: "masters.villages", status: "available", href: "/masters/villages", isCurrent: exact("/masters/villages") },
      { id: "items", labelKey: "masters.items", status: "available", href: "/masters/items", isCurrent: exact("/masters/items") },
      { id: "expense-heads", labelKey: "masters.expense-heads", status: "available", href: "/masters/expense-heads", isCurrent: exact("/masters/expense-heads") },
      { id: "applicable-expenses", labelKey: "nav.item.applicableExpenses", status: "unavailable" },
    ],
  },
  {
    id: "accounts",
    labelKey: "nav.group.accounts",
    items: [
      { id: "ledger", labelKey: "nav.item.ledger", status: "unavailable" },
      { id: "trial-balance", labelKey: "nav.item.trialBalance", status: "unavailable" },
      { id: "journal", labelKey: "nav.item.journal", status: "unavailable" },
    ],
  },
  {
    id: "receipts-payments",
    labelKey: "nav.group.receiptsPayments",
    items: [
      { id: "receipt", labelKey: "nav.item.receipt", status: "unavailable" },
      { id: "payment", labelKey: "nav.item.payment", status: "unavailable" },
      { id: "cash-bank", labelKey: "nav.item.cashBank", status: "unavailable" },
    ],
  },
  {
    id: "reports",
    labelKey: "nav.group.reports",
    items: [
      { id: "nakal", labelKey: "nav.item.nakal", status: "unavailable" },
      { id: "pana-print", labelKey: "nav.item.panaPrint", status: "unavailable" },
    ],
  },
  {
    id: "firm-year",
    labelKey: "nav.group.firmYear",
    items: [
      { id: "firm-settings", labelKey: "nav.item.firmSettings", status: "available", href: "/settings/firm", isCurrent: exact("/settings/firm") },
      { id: "year-setup", labelKey: "nav.item.yearSetup", status: "unavailable" },
    ],
  },
];

/** The group that contains the current page, if any. */
export function activeGroupId(path: string): string | null {
  for (const g of NAV_GROUPS) if (g.items.some((i) => i.isCurrent?.(path))) return g.id;
  return null;
}

/** Dictionary key of the page title for a pathname (used for the browser tab title). Null for unknown routes. */
export function titleKeyFor(path: string): MessageKey | null {
  if (path === "/") return "bill.searchTitle";
  if (path === "/bills/new") return "bill.newTitle";
  if (/^\/bills\/[^/]+\/print$/.test(path)) return "title.print";
  if (isBillPage(path)) return "bill.viewTitle";
  const m = /^\/masters\/(farmers|buyers|villages|items|expense-heads)$/.exec(path);
  return m ? (`masters.${m[1]}` as MessageKey) : null;
}

export interface Crumb {
  labelKey: MessageKey;
  /** Omitted for the current page (last crumb). */
  href?: string;
}

/** Breadcrumb trail for a pathname. Empty for routes the menu does not know. */
export function breadcrumbsFor(path: string): Crumb[] {
  const billsCrumb: Crumb = { labelKey: "nav.bills", href: "/" };
  const bookCrumb: Crumb = { labelKey: "nav.group.mandiBook" };
  if (path === "/") return [bookCrumb, { labelKey: "nav.bills" }];
  if (path === "/bills/new") return [bookCrumb, billsCrumb, { labelKey: "nav.newBill" }];
  if (/^\/bills\/[^/]+\/print$/.test(path)) return [bookCrumb, billsCrumb, { labelKey: "bill.viewTitle" }, { labelKey: "print.title" }];
  if (isBillPage(path)) return [bookCrumb, billsCrumb, { labelKey: "bill.viewTitle" }];
  for (const g of NAV_GROUPS) {
    const item = g.items.find((i) => i.href && i.isCurrent?.(path) && g.id !== "mandi-book");
    if (item) return [{ labelKey: g.labelKey }, { labelKey: item.labelKey }];
  }
  return [];
}
