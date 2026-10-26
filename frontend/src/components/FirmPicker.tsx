"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import { nameOf } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import { useSession, type FirmInfo } from "@/lib/session";
import { useAsync } from "@/lib/useAsync";

interface FirmPage {
  content: FirmInfo[];
  totalElements: number;
}

/** Choose the firm to work in. Only firms with an ACTIVE membership of this person are offered, paged and searchable. */
export function FirmPicker() {
  const { t, lang } = useI18n();
  const { selectFirm, signOut } = useSession();
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [name, setName] = useState("");
  const [nameHi, setNameHi] = useState("");
  const [createError, setCreateError] = useState<string | null>(null);
  const SIZE = 10;

  const firms = useAsync(
    () => api<FirmPage>(`/auth/firms?q=${encodeURIComponent(q)}&page=${page}&size=${SIZE}`, lang, { quiet: true }),
    `${q}|${page}`,
  );
  const rows = firms.data?.content ?? [];
  const total = firms.data?.totalElements ?? 0;
  const last = Math.max(0, Math.ceil(total / SIZE) - 1);

  async function open(id: number) {
    setError(null);
    setBusy(true);
    try {
      await selectFirm(id);
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  }

  async function create(e: React.FormEvent) {
    e.preventDefault();
    setCreateError(null);
    if (name.trim() === "") return setCreateError(t("firm.nameRequired"));
    setBusy(true);
    try {
      const made = await api<{ id: number }>("/auth/firms", lang, { method: "POST", body: { name: name.trim(), nameHi: nameHi.trim() || null }, quiet: true });
      await selectFirm(made.id);
    } catch (err) {
      setBusy(false);
      if (err instanceof ApiError && err.code === "NOT_AN_OWNER") setCreateError(t("firm.notOwner"));
      else if (err instanceof ApiError && err.code === "ACCOUNT_BLOCKED") setCreateError(t("firm.accountBlocked"));
      else setCreateError((err as Error).message);
    }
  }

  return (
    <section className="card login-card" data-testid="firm-picker">
      <h1 style={{ marginTop: 0 }}>{t("firm.pickTitle")}</h1>
      <p className="muted">{t("firm.pickHint")}</p>
      {(total > SIZE || q) && (
        <input
          type="search"
          aria-label={t("firm.search")}
          placeholder={t("firm.search")}
          value={q}
          onChange={(e) => {
            setQ(e.target.value);
            setPage(0);
          }}
          data-testid="firm-search"
        />
      )}
      {firms.loading && <p className="muted">{t("auth.loading")}</p>}
      {!firms.loading && rows.length === 0 && !firms.error && <p className="muted" data-testid="firm-none">{t("firm.none")}</p>}
      {firms.error && <div className="error" role="alert">{firms.error}</div>}
      <ul className="firm-list" data-testid="firm-list">
        {rows.map((f) => (
          <li key={f.id}>
            <button type="button" className="secondary" disabled={busy} onClick={() => open(f.id)} data-testid="firm-option">
              <strong>{nameOf(f, lang)}</strong> <span className="muted">{t(`firm.role.${f.role}`)}</span>
            </button>
          </li>
        ))}
      </ul>
      {total > SIZE && (
        <div className="row" style={{ alignItems: "center" }}>
          <button type="button" className="secondary small" disabled={page <= 0} onClick={() => setPage(page - 1)}>‹</button>
          <span className="muted">{page + 1} / {last + 1}</span>
          <button type="button" className="secondary small" disabled={page >= last} onClick={() => setPage(page + 1)}>›</button>
        </div>
      )}
      {error && <div className="error" role="alert" data-testid="firm-error">{error}</div>}

      <details style={{ marginTop: 14 }} data-testid="firm-create">
        <summary>{t("firm.create")}</summary>
        <form onSubmit={create} noValidate>
          <label htmlFor="firm-name">{t("firm.name")}</label>
          <input id="firm-name" value={name} onChange={(e) => setName(e.target.value)} maxLength={200} data-testid="firm-name" />
          <label htmlFor="firm-name-hi">{t("firm.nameHi")}</label>
          <input id="firm-name-hi" value={nameHi} onChange={(e) => setNameHi(e.target.value)} maxLength={200} data-testid="firm-name-hi" />
          {createError && <div className="error" role="alert" data-testid="firm-create-error" style={{ marginTop: 8 }}>{createError}</div>}
          <button type="submit" disabled={busy} style={{ marginTop: 10 }} data-testid="firm-create-submit">{t("firm.createButton")}</button>
        </form>
      </details>
      <p style={{ marginBottom: 0 }}>
        <button type="button" className="secondary small" onClick={() => void signOut()} data-testid="picker-signout">{t("auth.signOut")}</button>
      </p>
    </section>
  );
}
