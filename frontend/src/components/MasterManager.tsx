"use client";

import Link from "next/link";
import { useState } from "react";
import { ConfirmDialog } from "./ConfirmDialog";
import { api } from "@/lib/api";
import { MasterForm } from "./MasterForm";
import { useI18n } from "@/lib/i18n";
import { useAsync } from "@/lib/useAsync";
import type { Master, MasterType, Page } from "@/lib/types";

const TYPES: MasterType[] = ["farmers", "buyers", "villages", "items", "expense-heads"];
const PAGE_SIZE = 50;

export function MasterManager({ type }: { type: MasterType }) {
  const { t, lang } = useI18n();
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<Master | null>(null);
  const [formKey, setFormKey] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [toDeactivate, setToDeactivate] = useState<Master | null>(null);

  const list = useAsync(
    () => api<Page<Master>>(`/masters/${type}/page?q=${encodeURIComponent(q)}&page=${page}&size=${PAGE_SIZE}`, lang),
    `${type}|${q}|${page}`,
  );
  const rows = list.data?.content ?? [];
  const total = list.data?.totalElements ?? 0;
  const lastPage = Math.max(0, Math.ceil(total / PAGE_SIZE) - 1);

  function startEdit(m: Master | null) {
    setNotice(null);
    setEditing(m);
    setFormKey((k) => k + 1);      // a fresh form for each record, so no value of the previous one is carried over
  }

  async function toggle(m: Master) {
    setError(null);
    setNotice(null);
    setToDeactivate(null);
    try {
      await api<Master>(`/masters/${type}/${m.id}/active`, lang, { method: "PATCH", body: { active: !m.active } });
      list.reload();
    } catch (err) {
      setError((err as Error).message);
    }
  }

  return (
    <div>
      <div className="page-head">
        <h1 data-testid="master-title">{t(`masters.${type}`)}</h1>
      </div>
      <nav className="tabs no-print" aria-label="Master types">
        {TYPES.map((x) => (
          <Link key={x} href={`/masters/${x}`} className="tab" aria-current={x === type ? "page" : undefined} data-testid={`master-tab-${x}`}>
            {t(`masters.${x}`)}
          </Link>
        ))}
      </nav>

      <section className="card">
        <p className="muted" style={{ marginTop: 0 }}>{t("masters.hint")}</p>
        <MasterForm
          key={formKey} type={type} editing={editing} layout="inline"
          onCancel={() => startEdit(null)}
          onSaved={() => { startEdit(null); setNotice(t("common.saved")); list.reload(); }}
        />
        {error && (
          <div className="error" role="alert" data-testid="master-toggle-error">
            {error}
          </div>
        )}
        {notice && (
          <div className="success" role="status" data-testid="master-saved">
            {notice}
          </div>
        )}
      </section>

      <section className="card">
        <label style={{ maxWidth: 320 }}>
          {t("common.search")}
          <input value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} data-testid="master-search" />
        </label>
        <div className="table-scroll" style={{ marginTop: 10 }}>
          <table className="data-table" data-testid="master-table">
            <thead>
              <tr>
                <th>{t("common.name")}</th>
                <th>{t("common.nameHi")}</th>
                {type === "farmers" && <th>{t("bill.village")}</th>}
                {type === "expense-heads" && <th className="num">{t("master.col.rate")}</th>}
                <th>{t("common.active")}</th>
                <th>{t("common.actions")}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((m) => (
                <tr key={m.id} data-testid="master-row" className={m.active ? undefined : "inactive"}>
                  <td>{m.name}</td>
                  <td lang="hi">{m.nameHi ?? "—"}</td>
                  {type === "farmers" && <td data-testid="master-village-cell">{m.villageName ?? "—"}</td>}
                  {type === "expense-heads" && <td className="num" data-testid="master-rate-cell">{m.rate !== null && m.rate !== undefined ? m.rate : "—"}</td>}
                  <td>{m.active ? t("common.active") : t("common.inactive")}</td>
                  <td className="row" style={{ alignItems: "center" }}>
                    <button
                      className="secondary small"
                      onClick={() => startEdit(m)}
                      data-testid="master-edit"
                    >
                      {t("common.edit")}
                    </button>
                    <button className="secondary small" onClick={() => (m.active ? setToDeactivate(m) : toggle(m))} data-testid="master-toggle">
                      {m.active ? t("common.deactivate") : t("common.activate")}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {list.loading && <p className="muted">{t("common.loading")}</p>}
          {!list.loading && rows.length === 0 && <p className="muted">{t("common.noRecords")}</p>}
          {list.error && <div className="error">{list.error}</div>}
        </div>
        <div className="row" style={{ marginTop: 10, alignItems: "center" }}>
          <button className="secondary small" disabled={page <= 0} onClick={() => setPage(page - 1)} data-testid="master-prev">{t("common.prev")}</button>
          <span className="muted" data-testid="master-page">{page + 1} / {lastPage + 1}</span>
          <button className="secondary small" disabled={page >= lastPage} onClick={() => setPage(page + 1)} data-testid="master-next">{t("common.next")}</button>
          <span className="muted" data-testid="master-count">{t("master.count").replace("{n}", String(total))}</span>
        </div>
      </section>

      <ConfirmDialog
        open={toDeactivate !== null}
        title={t("master.confirmDeactivateTitle")}
        message={t("master.confirmDeactivateText")}
        confirmLabel={t("common.deactivate")}
        cancelLabel={t("common.cancel")}
        onConfirm={() => toDeactivate && toggle(toDeactivate)}
        onCancel={() => setToDeactivate(null)}
        testId="deactivate-dialog"
      />
    </div>
  );
}
