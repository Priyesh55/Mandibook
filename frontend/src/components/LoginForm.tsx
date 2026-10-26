"use client";

import { useId, useState } from "react";
import { ApiError } from "@/lib/api";
import { useI18n, type MessageKey } from "@/lib/i18n";
import { useSession } from "@/lib/session";

/**
 * Email and password sign-in. Validation is only a convenience (empty fields); the server decides. A wrong email and a wrong
 * password get the same message, exactly like the server's answer, so the page cannot be used to find out which emails exist.
 * Nothing typed here is stored anywhere in the browser.
 */
export function LoginForm({ expired }: { expired: boolean }) {
  const { t } = useI18n();
  const { signIn } = useSession();
  const id = useId();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
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

  return (
    <section className="card login-card" data-testid="login">
      {expired && (
        <div className="notice" role="alert" data-testid="login-expired">
          <strong>{t("auth.sessionExpiredTitle")}</strong>
          <p style={{ margin: "4px 0 0" }}>{t("auth.sessionExpiredBody")}</p>
        </div>
      )}
      <h1 style={{ marginTop: 0 }}>{t("auth.signInTitle")}</h1>
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
          <input
            id={`${id}-password`}
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            aria-invalid={problem?.field === "password"}
            aria-describedby={problem ? `${id}-err` : undefined}
            data-testid="login-password"
          />
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
      <p className="muted" style={{ marginBottom: 0 }}>{t("auth.noPublicSignup")}</p>
    </section>
  );
}
