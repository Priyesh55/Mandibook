"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import { useI18n } from "@/lib/i18n";
import { useSession } from "@/lib/session";
import { useAsync } from "@/lib/useAsync";
import type { FirmProfile, NextNumber } from "@/lib/types";
import { SectionHead } from "./bill/SectionHead";

/**
 * What the printed bill shows about the firm, and where the bill numbers start. Both are optional and owner-only: the server
 * refuses anyone else, this page only reflects that. The starting number can be set once, before the firm's first numbered bill.
 */
export function FirmSettings() {
  const { t, lang } = useI18n();
  const { state } = useSession();
  const isOwner = state.status === "ready" && state.firm.role === "OWNER";
  const profile = useAsync(() => api<FirmProfile>("/firm/profile", lang), "profile");
  const numbering = useAsync(() => api<NextNumber>("/aadat/bills/next-number", lang), "numbering");
  // what the owner has typed; until then the form shows the saved profile (no copying of state in an effect)
  const [edited, setEdited] = useState<{ address: string; addressHi: string; phone: string; licenceNo: string; shopNo: string } | null>(null);
  const p = profile.data;
  const form = edited ?? { address: p?.address ?? "", addressHi: p?.addressHi ?? "", phone: p?.phone ?? "", licenceNo: p?.licenceNo ?? "", shopNo: p?.shopNo ?? "" };
  const [start, setStart] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function saveProfile(e: React.FormEvent) {
    e.preventDefault();
    setMessage(null);
    setError(null);
    try {
      await api<FirmProfile>("/firm/profile", lang, { method: "PUT", body: form });
      setMessage(t("settings.firm.saved"));
      setEdited(null);
      profile.reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : t("common.error"));
    }
  }

  async function saveStart(e: React.FormEvent) {
    e.preventDefault();
    setMessage(null);
    setError(null);
    try {
      await api<NextNumber>("/firm/bill-numbering", lang, { method: "PUT", body: { nextNumber: Number(start) } });
      setMessage(t("settings.numbering.done"));
      setStart("");
      numbering.reload();
    } catch (err) {
      setError(err instanceof ApiError || err instanceof Error ? err.message : t("common.error"));
    }
  }

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setEdited({ ...form, [k]: e.target.value });

  return (
    <div data-testid="firm-settings">
      <div className="page-head"><h1>{t("settings.firm.title")}</h1></div>
      {!isOwner && <div className="notice" role="status" data-testid="firm-owner-only">{t("settings.firm.ownerOnly")}</div>}
      {message && <div className="success" role="status" data-testid="settings-saved">{message}</div>}
      {error && <div className="error" role="alert" data-testid="settings-error">{error}</div>}

      <form onSubmit={saveProfile} className="card sec">
        <SectionHead id="s-profile" number={1} title={t("settings.firm.title")} />
        <p className="hint">{t("settings.firm.intro")}</p>
        <div className="grid">
          <div className="field"><span className="label-text">{t("settings.firm.name")}</span><output data-testid="firm-name">{profile.data?.name ?? "—"}</output></div>
          <label>{t("settings.firm.address")}<input value={form.address} maxLength={300} disabled={!isOwner} onChange={set("address")} data-testid="firm-address" /></label>
          <label>{t("settings.firm.addressHi")}<input value={form.addressHi} maxLength={300} disabled={!isOwner} onChange={set("addressHi")} data-testid="firm-address-hi" /></label>
          <label>{t("settings.firm.phone")}<input value={form.phone} maxLength={30} disabled={!isOwner} onChange={set("phone")} data-testid="firm-phone" /></label>
          <label>{t("settings.firm.licence")}<input value={form.licenceNo} maxLength={60} disabled={!isOwner} onChange={set("licenceNo")} data-testid="firm-licence" /></label>
          <label>{t("settings.firm.shop")}<input value={form.shopNo} maxLength={30} disabled={!isOwner} onChange={set("shopNo")} data-testid="firm-shop" /></label>
        </div>
        {isOwner && <div className="row" style={{ marginTop: 12 }}><button type="submit" data-testid="firm-save">{t("settings.firm.save")}</button></div>}
      </form>

      <form onSubmit={saveStart} className="card sec">
        <SectionHead id="s-numbering" number={2} title={t("settings.numbering.title")} />
        <p className="hint">{t("settings.numbering.intro")}</p>
        <div className="grid">
          <div className="field"><span className="label-text">{t("settings.numbering.next")}</span><output data-testid="numbering-next">{numbering.data?.expectedNext ?? "—"}</output></div>
          {isOwner && !numbering.data?.startLocked && (
            <label>
              {t("settings.numbering.start")}
              <input type="number" min="1" step="1" value={start} onChange={(e) => setStart(e.target.value)} data-testid="numbering-start" />
              <span className="hint">{t("settings.numbering.startHint")}</span>
            </label>
          )}
        </div>
        {numbering.data?.startLocked && <p className="note" data-testid="numbering-locked">{t("settings.numbering.locked")}</p>}
        {isOwner && !numbering.data?.startLocked && (
          <div className="row" style={{ marginTop: 12 }}><button type="submit" disabled={start === ""} data-testid="numbering-set">{t("settings.numbering.set")}</button></div>
        )}
      </form>
    </div>
  );
}
