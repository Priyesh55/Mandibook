"use client";

import { useId, useState } from "react";
import { ApiError } from "@/lib/api";
import { useI18n, type MessageKey } from "@/lib/i18n";
import { useSession } from "@/lib/session";
import { MandiArt, TruckArt } from "./LoginArt";

function Brand() {
  return (
    <div className="lg-brand">
      <svg className="lg-logo" viewBox="0 0 40 40" aria-hidden="true">
        <rect width="40" height="40" rx="10" fill="#1f4fcf" />
        <path d="M9 29V12l11 9 11-9v17" fill="none" stroke="#fff" strokeWidth="3.2" strokeLinecap="round" strokeLinejoin="round" />
        <circle cx="20" cy="30" r="2" fill="#c7d4f4" />
      </svg>
      <span>Mandi Book</span>
    </div>
  );
}

/**
 * Email and password sign-in. Validation is only a convenience (empty fields); the server decides. A wrong email and a wrong
 * password get the same message, exactly like the server's answer, so the page cannot be used to find out which emails exist.
 * Nothing typed here is stored anywhere in the browser.
 *
 * The page is a split screen sized to the viewport (see "sign-in" in globals.css). `overlay` (session expired over a page that
 * stays mounted) uses the plain card instead.
 */
export function LoginForm({ expired, overlay = false }: { expired: boolean; overlay?: boolean }) {
  const { t } = useI18n();
  const { signIn } = useSession();
  const id = useId();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPw, setShowPw] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<{ field: "email" | "password" | null; key: MessageKey } | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (busy) return;
    if (email.trim() === "") return setProblem({ field: "email", key: "auth.emailRequired" });
    if (password === "") return setProblem({ field: "password", key: "auth.passwordRequired" });
    setProblem(null);
    setBusy(true);
    try {
      await signIn(email.trim(), password);
      setPassword("");
    } catch (err) {
      setPassword("");
      if (err instanceof ApiError) {
        if (err.status === 401) setProblem({ field: null, key: "auth.invalidCredentials" });
        else if (err.status === 429) setProblem({ field: null, key: "auth.tooManyAttempts" });
        else if (err.status === 503) setProblem({ field: null, key: "auth.busy" });
        else setProblem({ field: null, key: "auth.networkError" });
      } else {
        setProblem({ field: null, key: "auth.networkError" });
      }
    } finally {
      setBusy(false);
    }
  }

  const notice = expired && (
    <div className="notice" role="alert" data-testid="login-expired">
      <strong>{t("auth.sessionExpiredTitle")}</strong>
      <p style={{ margin: "4px 0 0" }}>{t("auth.sessionExpiredBody")}</p>
    </div>
  );
  const form = (
    <form onSubmit={submit} noValidate data-testid="login-form">
      <div>
        <label htmlFor={`${id}-email`}>{t("auth.email")}</label>
        <input
          id={`${id}-email`}
          type="email"
          autoComplete="username"
          autoFocus
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          aria-invalid={problem?.field === "email"}
          aria-describedby={problem ? `${id}-err` : undefined}
          data-testid="login-email"
        />
      </div>
      <div style={{ marginTop: 8 }}>
        <label htmlFor={`${id}-password`}>{t("auth.password")}</label>
        <div className="lg-pw">
          <input
            id={`${id}-password`}
            type={showPw ? "text" : "password"}
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            aria-invalid={problem?.field === "password"}
            aria-describedby={problem ? `${id}-err` : undefined}
            data-testid="login-password"
          />
          <button type="button" className="lg-show" onClick={() => setShowPw((v) => !v)} aria-pressed={showPw} data-testid="login-show">
            {showPw ? t("login.hide") : t("login.show")}
          </button>
        </div>
      </div>
      {problem && (
        <div id={`${id}-err`} className="error" role="alert" data-testid="login-error" style={{ marginTop: 8 }}>
          {t(problem.key)}
        </div>
      )}
      <button type="submit" className="big" disabled={busy} style={{ marginTop: 12 }} data-testid="login-submit">
        {busy ? t("auth.signingIn") : t("auth.signIn")}
      </button>
    </form>
  );
  const invite = <p className="muted lg-invite">{t("auth.noPublicSignup")}</p>;
  const title = <h1>{t("auth.signInTitle")}</h1>;

  if (overlay) {
    return (
      <section className="card login-card" data-testid="login">
        {notice}
        <h1 style={{ marginTop: 0 }}>{t("auth.signInTitle")}</h1>
        {form}
        {invite}
      </section>
    );
  }

  return (
    <div className="lg" data-testid="login">
      <div className="lg-panel">
        <Brand />
        <div className="lg-pitch"><h2>{t("login.aHead")}</h2><p>{t("login.aSub")}</p></div>
        <div className="lg-art"><TruckArt className="lg-truck" /></div>
        <div className="lg-chips"><span>{t("login.chip1")}</span><span>{t("login.chip2")}</span><span>{t("login.chip3")}</span></div>
      </div>
      <div className="lg-side">
        <div className="lg-box">{notice}{title}{form}{invite}</div>
        <div className="lg-art lg-art-end"><MandiArt className="lg-mandi" /></div>
      </div>
    </div>
  );
}
