"use client";

import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { nameOf } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import { useSession } from "@/lib/session";
import { FirmPicker } from "./FirmPicker";
import { LoginForm } from "./LoginForm";

/** Pages that work without a session: the invitation page (the person has no account yet). */
const PUBLIC_PREFIXES = ["/accept-invitation"];

/**
 * Decides what the person sees. The page never trusts its own idea of who is signed in: the state comes from the server
 * session (see SessionProvider). There are no redirects, so there are no redirect loops: the right panel is simply shown
 * at the same address.
 *
 *   loading            wait for the server's answer
 *   anonymous          the sign-in form (no content behind it)
 *   anonymous, expired the content stays on screen (inert, nothing is lost) under a sign-in dialog
 *   needFirm           choose a firm
 *   stale              another window changed the firm: nothing is sent until the person chooses
 *   blocked            the firm is closed to this person (membership removed, account suspended or switched off)
 *   ready              the application, re-created whenever the firm changes
 */
export function AuthGate({ children }: { children: ReactNode }) {
  const path = usePathname();
  const { t, lang } = useI18n();
  const { state, hadSession, lastFirmId, chooseFirm, adoptServerFirm, signOut, retry } = useSession();

  if (PUBLIC_PREFIXES.some((p) => path === p || path.startsWith(`${p}/`))) return <>{children}</>;

  switch (state.status) {
    case "loading":
      return <p className="muted" role="status" data-testid="auth-loading">{t("auth.loading")}</p>;

    case "anonymous":
      if (state.expired && hadSession) {
        // the very same wrapper as in the "ready" state (same type, position and key): the page keeps its state, nothing typed is lost
        return (
          <>
            <div key={lastFirmId ?? 0} inert aria-hidden="true" data-testid="content-inert">{children}</div>
            <div className="overlay" role="dialog" aria-modal="true" aria-label={t("auth.sessionExpiredTitle")} data-testid="relogin-dialog">
              <LoginForm expired />
            </div>
          </>
        );
      }
      return <LoginForm expired={state.expired} />;

    case "needFirm":
      return <FirmPicker />;

    case "stale":
      return (
        <section className="card login-card" role="alertdialog" aria-labelledby="stale-title" data-testid="stale-firm">
          <h1 id="stale-title" style={{ marginTop: 0 }}>{t("firm.staleTitle")}</h1>
          <p>{t("firm.staleBody")}</p>
          <div className="row">
            {state.serverFirm && (
              <button type="button" onClick={adoptServerFirm} data-testid="stale-adopt">
                {t("firm.stayWith")} {nameOf(state.serverFirm, lang)}
              </button>
            )}
            <button type="button" className="secondary" onClick={chooseFirm} data-testid="stale-choose">{t("firm.chooseAnother")}</button>
            <button type="button" className="secondary" onClick={() => void signOut()}>{t("auth.signOut")}</button>
          </div>
        </section>
      );

    case "blocked":
      return (
        <section className="card login-card" role="alert" data-testid="no-access">
          <h1 style={{ marginTop: 0 }}>{t("access.title")}</h1>
          <p data-testid="no-access-reason">{t(`access.${state.code}`)}</p>
          <div className="row">
            <button type="button" onClick={chooseFirm} data-testid="no-access-choose">{t("firm.chooseAnother")}</button>
            <button type="button" className="secondary" onClick={() => void signOut()}>{t("auth.signOut")}</button>
          </div>
        </section>
      );

    case "error":
      return (
        <section className="card login-card" role="alert" data-testid="auth-error">
          <h1 style={{ marginTop: 0 }}>{t("auth.errorTitle")}</h1>
          <p>{state.message}</p>
          <button type="button" onClick={retry}>{t("auth.retry")}</button>
        </section>
      );

    case "ready":
      // the key re-creates every page when the firm changes, so no data or half-typed form of one firm can reach another
      return (
        <>
          <div key={state.firm.id}>{children}</div>
          {null}
        </>
      );
  }
}
