"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import { useI18n } from "@/lib/i18n";
import { useSession } from "@/lib/session";
import { useAsync } from "@/lib/useAsync";

interface Member {
  id: number;
  role: "OWNER" | "EMPLOYEE";
  status: "ACTIVE" | "INACTIVE";
  email: string;
  displayName: string | null;
}

interface Pending {
  id: number;
  email: string;
  expiresAt: string;
}

/**
 * The owner of the active firm invites employees and switches them on and off. What the buttons call is enforced by the
 * server (OWNER role in the active firm); this page only reflects it. No password or link is ever shown here: the invited
 * person receives the link by email and chooses their own password.
 */
export function TeamManager() {
  const { t, lang } = useI18n();
  const { state } = useSession();
  const isOwner = state.status === "ready" && state.firm.role === "OWNER";
  const [email, setEmail] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const members = useAsync(() => (isOwner ? api<Member[]>("/firm/members", lang) : Promise.resolve([])), `members|${isOwner}`);
  const pending = useAsync(() => (isOwner ? api<Pending[]>("/firm/invitations", lang) : Promise.resolve([])), `pending|${isOwner}`);

  if (!isOwner) return <div className="notice" role="alert" data-testid="team-owner-only">{t("team.ownerOnly")}</div>;

  function describe(e: unknown): string {
    if (e instanceof ApiError) {
      if (e.code === "EMAIL_NOT_CONFIGURED") return t("team.emailNotConfigured");
      if (e.code === "ALREADY_MEMBER") return t("team.alreadyMember");
      if (e.code === "INVALID_EMAIL") return t("team.invalidEmail");
      if (e.code === "PERMISSION_DENIED") return t("team.ownerOnly");
    }
    return (e as Error).message;
  }

  async function invite(ev: React.FormEvent) {
    ev.preventDefault();
    setError(null);
    setNotice(null);
    try {
      await api("/firm/invitations", lang, { method: "POST", body: { email } });
      setEmail("");
      setNotice(t("team.invited"));
      pending.reload();
    } catch (e) {
      setError(describe(e));
    }
  }

  async function act(path: string) {
    setError(null);
    setNotice(null);
    try {
      await api(path, lang, { method: "POST" });
      members.reload();
      pending.reload();
    } catch (e) {
      setError(describe(e));
    }
  }

  return (
    <div data-testid="team">
      <div className="page-head"><h1>{t("team.title")}</h1></div>

      <section className="card">
        <h2 style={{ marginTop: 0 }}>{t("team.invite")}</h2>
        <p className="muted">{t("team.inviteHint")}</p>
        <form onSubmit={invite} className="row" noValidate>
          <label htmlFor="invite-email" className="sr-only">{t("team.email")}</label>
          <input id="invite-email" type="email" autoComplete="off" placeholder={t("team.email")} value={email} onChange={(e) => setEmail(e.target.value)} data-testid="invite-email" />
          <button type="submit" data-testid="invite-submit">{t("team.inviteButton")}</button>
        </form>
        {error && <div className="error" role="alert" data-testid="team-error" style={{ marginTop: 8 }}>{error}</div>}
        {notice && <div className="success" role="status" data-testid="team-notice" style={{ marginTop: 8 }}>{notice}</div>}
      </section>

      <section className="card">
        <h2 style={{ marginTop: 0 }}>{t("team.members")}</h2>
        <div className="table-scroll">
          <table className="data-table" data-testid="members-table">
            <thead>
              <tr><th>{t("team.email")}</th><th>{t("team.name")}</th><th>{t("team.role")}</th><th>{t("team.status")}</th><th /></tr>
            </thead>
            <tbody>
              {(members.data ?? []).map((m) => (
                <tr key={m.id} className={m.status === "INACTIVE" ? "inactive" : undefined} data-testid="member-row">
                  <td>{m.email}</td>
                  <td>{m.displayName ?? "—"}</td>
                  <td>{t(`firm.role.${m.role}`)}</td>
                  <td>{t(`team.status.${m.status}`)}</td>
                  <td>
                    {m.role === "EMPLOYEE" && (
                      <button type="button" className="secondary small" onClick={() => act(`/firm/members/${m.id}/${m.status === "ACTIVE" ? "deactivate" : "activate"}`)} data-testid="member-toggle">
                        {m.status === "ACTIVE" ? t("team.deactivate") : t("team.activate")}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {members.loading && <p className="muted">{t("auth.loading")}</p>}
      </section>

      <section className="card">
        <h2 style={{ marginTop: 0 }}>{t("team.pending")}</h2>
        {(pending.data ?? []).length === 0 && !pending.loading && <p className="muted" data-testid="no-pending">{t("team.noPending")}</p>}
        <ul style={{ listStyle: "none", padding: 0, margin: 0 }}>
          {(pending.data ?? []).map((p) => (
            <li key={p.id} className="row" style={{ alignItems: "center" }} data-testid="pending-row">
              <span>{p.email}</span>
              <span className="muted">{t("team.expires")}: {new Date(p.expiresAt).toLocaleDateString()}</span>
              <button type="button" className="secondary small" onClick={() => act(`/firm/invitations/${p.id}/revoke`)} data-testid="pending-revoke">{t("team.revoke")}</button>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
