"use client";

import Link from "next/link";
import { nameOf } from "@/lib/format";
import { useI18n } from "@/lib/i18n";
import { useSession } from "@/lib/session";

/** Who is signed in, which firm, and the way out. Shown only with a ready session. */
export function SessionBar() {
  const { t, lang } = useI18n();
  const { state, chooseFirm, signOut } = useSession();
  if (state.status !== "ready") return null;
  return (
    <div className="session-bar" data-testid="session-bar">
      <span data-testid="session-user" title={t("auth.signedInAs")}>{state.user.displayName ?? state.user.email}</span>
      <span aria-hidden="true">·</span>
      <span data-testid="session-firm">
        {t("firm.current")}: <strong>{nameOf(state.firm, lang)}</strong> ({t(`firm.role.${state.firm.role}`)})
      </span>
      <button type="button" className="secondary small" onClick={chooseFirm} data-testid="switch-firm">{t("firm.switch")}</button>
      {state.firm.role === "OWNER" && (
        <Link href="/team" className="button secondary small" data-testid="nav-team">{t("team.link")}</Link>
      )}
      <button type="button" className="secondary small" onClick={() => void signOut()} data-testid="sign-out">{t("auth.signOut")}</button>
    </div>
  );
}
