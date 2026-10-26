import type { Lang } from "./types";
import { csrfTokenFromCookie, isUnsafeMethod, needsFirmCheck, securityEventFor, type SecurityEvent } from "./authCore";

export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public fieldErrors: { field: string; message: string }[] = [],
    /** Stable code of the security layer or onboarding ({"error": CODE}), when the server sent one. */
    public code?: string,
    public reason?: string,
    public retryAfterSeconds?: number,
  ) {
    super(message);
  }
}

// ---- session awareness (set by the session provider; kept in memory only, never in storage) -------------------------

let securityListener: ((e: SecurityEvent) => void) | null = null;

export function onSecurityEvent(listener: (e: SecurityEvent) => void): () => void {
  securityListener = listener;
  return () => {
    if (securityListener === listener) securityListener = null;
  };
}

/** The firm this window believes it is working in. Sent as X-Mandi-Firm-Check; the server compares it with its session and
 * never lets it choose a firm. */
let firmCheck: number | null = null;

export function setFirmCheck(id: number | null) {
  firmCheck = id;
}

// ---- CSRF ------------------------------------------------------------------------------------------------------------------

async function fetchCsrfToken(): Promise<string> {
  const res = await fetch("/api/v1/auth/csrf", { credentials: "same-origin" });
  if (!res.ok) throw new ApiError(res.statusText, res.status);
  const body = (await res.json()) as { token: string };
  return body.token;
}

/** The current token: the cookie when present (it is replaced at every sign-in), otherwise a fresh one from the server. */
async function csrfToken(forceFetch = false): Promise<string> {
  if (!forceFetch && typeof document !== "undefined") {
    const fromCookie = csrfTokenFromCookie(document.cookie);
    if (fromCookie) return fromCookie;
  }
  return fetchCsrfToken();
}

interface Init {
  method?: string;
  body?: unknown;
  /** Extra request headers (for example Idempotency-Key). The security headers cannot be overridden. */
  headers?: Record<string, string>;
  /** Called with a successful response before its body is read (to look at headers such as X-Result-Truncated). */
  onResponse?: (res: Response) => void;
  /** Do not tell the session layer about a 401/409/403 (sign-in and the start-up probe handle them themselves). */
  quiet?: boolean;
}

async function send(path: string, lang: Lang, init: Init | undefined, forceCsrf: boolean): Promise<Response> {
  const method = (init?.method ?? "GET").toUpperCase();
  const headers: Record<string, string> = { ...(init?.headers ?? {}), "Accept-Language": lang };
  if (init?.body !== undefined) headers["Content-Type"] = "application/json";
  if (isUnsafeMethod(method)) headers["X-XSRF-TOKEN"] = await csrfToken(forceCsrf);
  if (needsFirmCheck(path) && firmCheck !== null) headers["X-Mandi-Firm-Check"] = String(firmCheck);
  return fetch(`/api/v1${path}`, {
    method,
    headers,
    credentials: "same-origin",
    body: init?.body !== undefined ? JSON.stringify(init.body) : undefined,
  });
}

export async function api<T>(path: string, lang: Lang, init?: Init): Promise<T> {
  let res = await send(path, lang, init, false);
  let problem: Record<string, unknown> | undefined;
  if (!res.ok) {
    problem = await readProblem(res);
    // A rejected CSRF token (cookie gone, token replaced in another window) is retried once with a fresh one: the server
    // refused the request before running it, so nothing was done twice.
    if (res.status === 403 && problem?.error === "CSRF_REJECTED" && isUnsafeMethod(init?.method ?? "GET")) {
      res = await send(path, lang, init, true);
      problem = res.ok ? undefined : await readProblem(res);
    }
  }
  if (!res.ok) {
    const code = typeof problem?.error === "string" ? problem.error : undefined;
    if (!init?.quiet) {
      const event = securityEventFor(res.status, code);
      if (event) securityListener?.(event);
    }
    const detail = typeof problem?.detail === "string" ? problem.detail : (code ?? res.statusText);
    const errors = Array.isArray(problem?.errors) ? (problem?.errors as { field: string; message: string }[]) : [];
    const retry = Number(res.headers.get("Retry-After"));
    throw new ApiError(detail, res.status, errors, code, typeof problem?.reason === "string" ? problem.reason : undefined, retry > 0 ? retry : undefined);
  }
  init?.onResponse?.(res);
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

async function readProblem(res: Response): Promise<Record<string, unknown> | undefined> {
  try {
    return (await res.json()) as Record<string, unknown>;
  } catch {
    return undefined; // non-JSON or empty error body
  }
}
