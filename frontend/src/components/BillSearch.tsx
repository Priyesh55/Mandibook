"use client";

import Link from "next/link";
import { useState } from "react";
import { api } from "@/lib/api";
import { financialYearOf, fyLabel } from "@/lib/billDraft";
import { nameOf, num, todayIso } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import { useAsync } from "@/lib/useAsync";
import type { AppConfig, BillSummary, Page } from "@/lib/types";
import { MasterPicker } from "./MasterPicker";

const PAGE_SIZE = 15;

export function BillSearch() {
  const { t, lang } = useI18n();
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [farmerId, setFarmerId] = useState("");
  const [q, setQ] = useState("");
  const [billNo, setBillNo] = useState("");
  const [fy, setFy] = useState("");
  const [page, setPage] = useState(0);
  const [includeDemo, setIncludeDemo] = useState(false);
  const config = useAsync(() => api<AppConfig>("/config", lang), "config");

  const params = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
  if (from) params.set("from", from);
  if (to) params.set("to", to);
  if (farmerId) params.set("farmerId", farmerId);
  if (q) params.set("q", q);
  if (/^\d+$/.test(billNo.trim())) params.set("billNo", billNo.trim());
  if (fy) params.set("financialYear", fy);
  if (includeDemo) params.set("includeDemo", "true");
  const qs = params.toString();
  const bills = useAsync(() => api<Page<BillSummary>>(`/aadat/bills?${qs}`, lang), qs);

  const total = bills.data?.totalElements ?? 0;
  const lastPage = Math.max(0, Math.ceil(total / PAGE_SIZE) - 1);
  const filtered = Boolean(from || to || farmerId || q || billNo || fy || includeDemo);
  const thisYear = financialYearOf(todayIso()) ?? new Date().getFullYear();
  const yearOptions = [thisYear + 1, thisYear, thisYear - 1, thisYear - 2];

  function clear() {
    setFrom("");
    setTo("");
    setFarmerId("");
    setQ("");
    setBillNo("");
    setFy("");
    setIncludeDemo(false);
    setPage(0);
  }

  return (
    <div>
      <div className="page-head">
        <h1>{t("bill.searchTitle")}</h1>
        <Link href="/bills/new" className="button big" data-testid="list-new-bill">
          + {t("nav.newBill")}
        </Link>
      </div>

      <section className="card">
        <div className="filters">
          <label>
            {t("bill.from")}
            <input type="date" value={from} onChange={(e) => { setFrom(e.target.value); setPage(0); }} data-testid="search-from" />
          </label>
          <label>
            {t("bill.to")}
            <input type="date" value={to} onChange={(e) => { setTo(e.target.value); setPage(0); }} data-testid="search-to" />
          </label>
          <label>
            {t("bill.searchNo")}
            <input inputMode="numeric" value={billNo} onChange={(e) => { setBillNo(e.target.value); setPage(0); }} data-testid="search-bill-no" />
          </label>
          <label>
            {t("bill.searchFy")}
            <select value={fy} onChange={(e) => { setFy(e.target.value); setPage(0); }} data-testid="search-fy">
              <option value="">{t("bill.allYears")}</option>
              {yearOptions.map((y) => <option key={y} value={y}>{fyLabel(y)}</option>)}
            </select>
          </label>
          <label>
            {t("bill.farmer")}
            <MasterPicker type="farmers" value={farmerId} onChange={(id) => { setFarmerId(id); setPage(0); }} testId="search-farmer" ariaLabel={t("bill.farmer")} />
          </label>
          <label>
            {t("common.search")}
            <input value={q} placeholder={t("bill.searchText")} onChange={(e) => { setQ(e.target.value); setPage(0); }} data-testid="search-q" />
          </label>
          <div className="row">
            <button type="button" className="secondary" onClick={clear} disabled={!filtered} data-testid="search-clear">
              {t("bill.clearFilters")}
            </button>
          </div>
        </div>
        {config.data?.demoCalculationEnabled && (
          <label style={{ flexDirection: "row", alignItems: "center", gap: 8, marginTop: 10 }}>
            <input type="checkbox" checked={includeDemo} onChange={(e) => { setIncludeDemo(e.target.checked); setPage(0); }} data-testid="search-include-demo" />
            {t("bill.includeDemo")}
          </label>
        )}
      </section>

      <section className="card">
        <div className="row" style={{ justifyContent: "space-between", alignItems: "center", marginBottom: 8 }}>
          <span className="muted" data-testid="bill-count">{t("bill.count").replace("{n}", String(total))}</span>
        </div>
        <div className="table-scroll">
          <table className="data-table" data-testid="bill-table">
            <thead>
              <tr>
                <th>{t("bill.billNo")}</th>
                <th>{t("bill.date")}</th>
                <th>{t("bill.farmer")}</th>
                <th>{t("bill.village")}</th>
                <th>{t("bill.vehicleNo")}</th>
                <th className="num">{t("bill.lotCount")}</th>
                <th className="num">{t("bill.col.gross")}</th>
                <th className="num">{t("bill.col.expenses")}</th>
                <th className="num">{t("bill.col.net")}</th>
                <th>{t("bill.calcStatus")}</th>
                <th><span className="sr-only">{t("common.actions")}</span></th>
              </tr>
            </thead>
            <tbody>
              {(bills.data?.content ?? []).map((b) => (
                <tr key={b.id} data-testid="bill-row">
                  <td className="ref" data-testid="bill-no"><Link href={`/bills/${b.id}`}>{b.billNo !== null ? b.billNo : `#${b.id}`}</Link></td>
                  <td>{b.billDate}</td>
                  <td>{nameOf(b.farmer, lang)}</td>
                  <td>{nameOf(b.village, lang)}</td>
                  <td>{b.vehicleNo ?? "—"}</td>
                  <td className="num">{b.lotCount}</td>
                  <td className="num" data-testid="bill-gross">{num(b.gross)}</td>
                  <td className="num" data-testid="bill-expenses">{num(b.expenses)}</td>
                  <td className="num strong" data-testid="bill-net">{num(b.net)}</td>
                  <td>
                    <span className={b.calculationStatus === "DEMO_UNVERIFIED" ? "badge demo" : "badge"}>
                      {t(`calc.${b.calculationStatus}`)}
                    </span>
                  </td>
                  <td>
                    <Link href={`/bills/${b.id}`} className="button secondary small" data-testid="bill-open">
                      {t("common.open")}
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {bills.loading && <p className="muted">{t("common.loading")}</p>}
        {!bills.loading && total === 0 && <p className="muted" data-testid="bill-empty">{t("common.noRecords")}</p>}
        {bills.error && <div className="error">{bills.error}</div>}
        <div className="row" style={{ marginTop: 10, alignItems: "center" }}>
          <button className="secondary small" disabled={page <= 0} onClick={() => setPage(page - 1)}>{t("common.prev")}</button>
          <span className="muted">{page + 1} / {lastPage + 1}</span>
          <button className="secondary small" disabled={page >= lastPage} onClick={() => setPage(page + 1)}>{t("common.next")}</button>
        </div>
      </section>
    </div>
  );
}
