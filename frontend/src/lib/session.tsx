"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api, ApiError, onSecurityEvent, setFirmCheck } from "./api";
import type { SecurityEvent } from "./authCore";
import type { Lang } from "./types";

export interface SessionUser {
  id: number;
  email: string;
  displayName: string | null;
}

export interface FirmInfo {
  id: number;
  name: string;
  nameHi: string | null;
  role: "OWNER" | "EMPLOYEE";
}

interface MeResponse {
  user: SessionUser;
  activeFirmId?: number;
}

interface FirmPage {
  content: FirmInfo[];
  totalElements: number;
}

/**
 * What the page knows about the sign-in. The SERVER session is the authority; this is only a mirror kept in memory (nothing is
 * written to localStorage or sessionStorage, and no identifier or token is ever read by the page except the CSRF token).
 */
export type SessionState =
  | { status: "loading" }
  | { status: "anonymous"; expired: boolean }
  | { status: "needFirm"; user: SessionUser }
  | { status: "ready"; user: SessionUser; firm: FirmInfo }
  /** another window changed the active firm: the user must choose to follow it, nothing is sent meanwhile */
  | { status: "stale"; user: SessionUser; previous: FirmInfo | null; serverFirm: FirmInfo | null }
  | { status: "blocked"; user: SessionUser; code: string }
  | { status: "error"; message: string };

interface SessionApi {
  state: SessionState;
  /** true after a session was lost while the user was working: the page keeps its content under the sign-in dialog */
  hadSession: boolean;
  /** the firm of the last ready state: the page under the sign-in dialog keeps this identity so nothing is re-created */
  lastFirmId: number | null;
  signIn: (email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
  selectFirm: (firmId: number) => Promise<void>;
  /** leave the current firm to pick another (the session keeps the user signed in) */
  chooseFirm: () => void;
  /** follow the firm the server session has (after another window changed it) */
  adoptServerFirm: () => void;
  retry: () => void;
}

const Ctx = createContext<SessionApi | null>(null);

export function useSession(): SessionApi {
  const v = useContext(Ctx);
  if (!v) throw new Error("useSession must be used inside <SessionProvider>");
  return v;
}

export function SessionProvider({ lang, children }: { lang: Lang; children: ReactNode }) {
  const [state, setStateRaw] = useState<SessionState>({ status: "loading" });
  const [hadSession, setHadSession] = useState(false);
  const [lastFirmId, setLastFirmId] = useState<number | null>(null);
  const stateRef = useRef<SessionState>(state);
  const setState = useCallback((s: SessionState) => {
    stateRef.current = s;
    setStateRaw(s);
    // the header on every business request follows the firm of the ready state, and nothing else
    setFirmCheck(s.status === "ready" ? s.firm.id : null);
    if (s.status === "ready") {
      setHadSession(true);
      setLastFirmId(s.firm.id);
    }
    if (s.status === "anonymous" && !s.expired) setHadSession(false);
  }, []);

  /** Reads the server's view (who am I, which firm) and moves to the matching state. Single-firm users get their firm selected. */
  const resolve = useCallback(async (): Promise<void> => {
    let me: MeResponse;
    try {
      me = await api<MeResponse>("/auth/me", lang, { quiet: true });
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        const was = stateRef.current.status;
        setState({ status: "anonymous", expired: was === "ready" || was === "stale" || was === "blocked" || was === "needFirm" });
        return;
      }
      setState({ status: "error", message: e instanceof Error ? e.message : String(e) });
      return;
    }
    try {
      const firms = await api<FirmPage>("/auth/firms?size=100", lang, { quiet: true });
      const active = me.activeFirmId !== undefined ? firms.content.find((f) => f.id === me.activeFirmId) : undefined;
      if (active) {
        setState({ status: "ready", user: me.user, firm: active });
      } else if (firms.totalElements === 1) {
        const only = firms.content[0];
        await api("/auth/active-firm", lang, { method: "POST", body: { firmId: only.id }, quiet: true });
        setState({ status: "ready", user: me.user, firm: only });
      } else {
        setState({ status: "needFirm", user: me.user });
      }
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        setState({ status: "anonymous", expired: true });
        return;
      }
      setState({ status: "error", message: e instanceof Error ? e.message : String(e) });
    }
  }, [lang, setState]);

  useEffect(() => {
    // the start-up probe asks the server whether a session exists; this is the only place that decides "signed in"
    void resolve();
  }, [resolve]);

  // 401 / stale firm / no access discovered by an ordinary call of any page
  useEffect(() => {
    return onSecurityEvent((e: SecurityEvent) => {
      const cur = stateRef.current;
      if (e.kind === "unauthenticated") {
        if (cur.status !== "anonymous") setState({ status: "anonymous", expired: cur.status !== "loading" });
      } else if (e.kind === "firm-stale") {
        if (cur.status === "ready") {
          const previous = cur.firm;
          const user = cur.user;
          setState({ status: "stale", user, previous, serverFirm: null });
          void (async () => {
            try {
              const me = await api<MeResponse>("/auth/me", lang, { quiet: true });
              const firms = await api<FirmPage>("/auth/firms?size=100", lang, { quiet: true });
              const serverFirm = me.activeFirmId !== undefined ? (firms.content.find((f) => f.id === me.activeFirmId) ?? null) : null;
              if (stateRef.current.status === "stale") setState({ status: "stale", user, previous, serverFirm });
            } catch {
              /* the dialog still offers to choose a firm or sign out */
            }
          })();
        }
      } else if (e.kind === "blocked") {
        if (cur.status === "ready") setState({ status: "blocked", user: cur.user, code: e.code });
      }
    });
  }, [lang, setState]);

  const signIn = useCallback(
    async (email: string, password: string) => {
      // A fresh token first (the previous one belonged to no sign-in); the server replaces it again on success.
      await api("/auth/csrf", lang, { quiet: true });
      await api("/auth/login", lang, { method: "POST", body: { email, password }, quiet: true });
      await resolve();
    },
    [lang, resolve],
  );

  const signOut = useCallback(async () => {
    try {
      await api("/auth/logout", lang, { method: "POST", quiet: true });
    } finally {
      setState({ status: "anonymous", expired: false });
    }
  }, [lang, setState]);

  const selectFirm = useCallback(
    async (firmId: number) => {
      const chosen = await api<FirmInfo>("/auth/active-firm", lang, { method: "POST", body: { firmId }, quiet: true });
      const cur = stateRef.current;
      const user = cur.status === "needFirm" || cur.status === "ready" || cur.status === "stale" || cur.status === "blocked" ? cur.user : null;
      if (user) setState({ status: "ready", user, firm: chosen });
      else await resolve();
    },
    [lang, resolve, setState],
  );

  const chooseFirm = useCallback(() => {
    const cur = stateRef.current;
    if (cur.status === "ready" || cur.status === "stale" || cur.status === "blocked") setState({ status: "needFirm", user: cur.user });
  }, [setState]);

  const adoptServerFirm = useCallback(() => {
    const cur = stateRef.current;
    if (cur.status === "stale" && cur.serverFirm) setState({ status: "ready", user: cur.user, firm: cur.serverFirm });
    else void resolve();
  }, [resolve, setState]);

  const retry = useCallback(() => {
    setState({ status: "loading" });
    void resolve();
  }, [resolve, setState]);

  const value = useMemo<SessionApi>(
    () => ({ state, hadSession, lastFirmId, signIn, signOut, selectFirm, chooseFirm, adoptServerFirm, retry }),
    [state, hadSession, lastFirmId, signIn, signOut, selectFirm, chooseFirm, adoptServerFirm, retry],
  );
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
