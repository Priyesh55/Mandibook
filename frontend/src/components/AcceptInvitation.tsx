"use client";

import Link from "next/link";
import { useEffect, useId, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { PASSWORD_MAX_BYTES, passwordBytes, passwordProblem, tokenFromHash } from "@/lib/authCore";
import { useI18n } from "@/lib/i18n";
import { useSession } from "@/lib/session";

/**
 * Accepting an invitation. The token is in the URL FRAGMENT of the emailed link (a fragment is never sent to any server);
 * it is read once into memory and removed from the address bar. The person chooses their own password here; nobody else
 * ever sees it. Signing in is a separate, normal step afterwards.
 */
export function AcceptInvitation() {
  const { t, lang } = useI18n();
  const { state } = useSession();
  const id = useId();
  const [token, setToken] = useState<string | null | undefined>(undefined);
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const [again, setAgain] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [exists, setExists] = useState(false);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    function read() {
      const found = tokenFromHash(window.location.hash);
      setToken((current) => found ?? (current === undefined ? null : current));
      if (found) window.history.replaceState(null, "", window.location.pathname + window.location.search);
    }
    read();
    // a link opened while this page is already showing only changes the fragment
    window.addEventListener("hashchange", read);
    return () => window.removeEventListener("hashchange", read);
  }, []);

  if (token === undefined) return <p className="muted" role="status">{t("auth.loading")}</p>;
  if (token === null) return <div className="notice" role="alert" data-testid="accept-no-token">{t("accept.noToken")}</div>;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    const problem = passwordProblem(password);
    if (problem === "tooShort") return setError(t("accept.tooShort"));
    if (problem === "tooLongBytes") return setError(t("accept.tooLong"));
    if (password !== again) return setError(t("accept.mismatch"));
    setBusy(true);
    try {
      await api("/auth/invitations/accept", lang, { method: "POST", body: { token, password, displayName: name.trim() || null }, quiet: true });
      setPassword("");
      setAgain("");
      setDone(true);
    } catch (err) {
      setPassword("");
      setAgain("");
      if (err instanceof ApiError && err.code === "ACCOUNT_EXISTS") setExists(true);
      else if (err instanceof ApiError && err.code === "INVITATION_INVALID") setError(t("accept.invalid"));
      else if (err instanceof ApiError && err.code === "INVALID_PASSWORD") setError(err.reason === "TOO_LONG_BYTES" ? t("accept.tooLong") : t("accept.tooShort"));
      else if (err instanceof ApiError && err.status === 429) setError(t("auth.tooManyAttempts"));
      else setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function acceptSignedIn() {
    setError(null);
    setBusy(true);
    try {
      await api("/auth/invitations/accept-signed-in", lang, { method: "POST", body: { token }, quiet: true });
      setDone(true);
    } catch (err) {
      setError(err instanceof ApiError && err.code === "INVITATION_INVALID" ? t("accept.invalid") : (err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <section className="card login-card" role="status" data-testid="accept-done">
        <h1 style={{ marginTop: 0 }}>{t("accept.doneTitle")}</h1>
        <p>{t("accept.doneBody")}</p>
        <Link href="/" className="button">{t("accept.goSignIn")}</Link>
      </section>
    );
  }

  if (exists) {
    const me = state.status === "ready" || state.status === "needFirm" ? state.user : null;
    return (
      <section className="card login-card" data-testid="accept-exists">
        <h1 style={{ marginTop: 0 }}>{t("accept.title")}</h1>
        <p>{t("accept.exists")}</p>
        {me && (
          <button type="button" disabled={busy} onClick={acceptSignedIn} data-testid="accept-signed-in">
            {t("accept.acceptAs")} {me.email}
          </button>
        )}
        {error && <div className="error" role="alert" style={{ marginTop: 8 }}>{error}</div>}
      </section>
    );
  }

  return (
    <section className="card login-card" data-testid="accept">
      <h1 style={{ marginTop: 0 }}>{t("accept.title")}</h1>
      <p className="muted">{t("accept.intro")}</p>
      <form onSubmit={submit} noValidate>
        <label htmlFor={`${id}-name`}>{t("accept.name")}</label>
        <input id={`${id}-name`} value={name} onChange={(e) => setName(e.target.value)} autoComplete="name" maxLength={200} data-testid="accept-name" />
        <label htmlFor={`${id}-pw`}>{t("accept.password")}</label>
        <input id={`${id}-pw`} type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password"
          aria-describedby={`${id}-hint`} data-testid="accept-password" />
        <p id={`${id}-hint`} className="hint" style={{ margin: "4px 0" }}>
          {t("accept.hint")} ({passwordBytes(password)}/{PASSWORD_MAX_BYTES})
        </p>
        <label htmlFor={`${id}-pw2`}>{t("accept.confirm")}</label>
        <input id={`${id}-pw2`} type="password" value={again} onChange={(e) => setAgain(e.target.value)} autoComplete="new-password" data-testid="accept-confirm" />
        {error && <div className="error" role="alert" data-testid="accept-error" style={{ marginTop: 8 }}>{error}</div>}
        <button type="submit" disabled={busy} style={{ marginTop: 12 }} data-testid="accept-submit">{t("accept.submit")}</button>
      </form>
    </section>
  );
}
