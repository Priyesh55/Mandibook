// Pure, testable rules of the sign-in layer. No DOM, no network. The server session is the only authority: nothing here is
// stored in the browser except what React keeps in memory.

/** Error codes of the security layer, as sent by the backend: {"error": CODE}. */
export const FIRM_STALE_CODES = ["FIRM_SELECTION_REQUIRED", "FIRM_CHECK_REQUIRED", "FIRM_MISMATCH"] as const;
export const BLOCKED_CODES = ["NO_MEMBERSHIP", "FIRM_INACTIVE", "ACCOUNT_BLOCKED"] as const;

export type SecurityEvent =
  | { kind: "unauthenticated" }
  | { kind: "firm-stale"; code: string }
  | { kind: "blocked"; code: string };

/** What a failed API response means for the session, or null when it is an ordinary error of the feature. */
export function securityEventFor(status: number, code: string | undefined): SecurityEvent | null {
  if (status === 401) return { kind: "unauthenticated" };
  if (status === 409 && code && (FIRM_STALE_CODES as readonly string[]).includes(code)) return { kind: "firm-stale", code };
  if (status === 403 && code && (BLOCKED_CODES as readonly string[]).includes(code)) return { kind: "blocked", code };
  return null;
}

/** Reads the CSRF token from a cookie string. The cookie is readable by the page by design; its name has a prefix in production. */
export function csrfTokenFromCookie(cookies: string): string | null {
  for (const part of cookies.split(";")) {
    const eq = part.indexOf("=");
    if (eq < 0) continue;
    const name = part.slice(0, eq).trim();
    if (name === "__Host-XSRF-TOKEN" || name === "XSRF-TOKEN") {
      const value = part.slice(eq + 1).trim();
      return value.length > 0 ? decodeURIComponent(value) : null;
    }
  }
  return null;
}

/** Routes of the sign-in family never carry the firm check header; every other /api route must. */
export function needsFirmCheck(path: string): boolean {
  return !path.startsWith("/auth/") && !path.startsWith("/dev/");
}

export function isUnsafeMethod(method: string): boolean {
  return !["GET", "HEAD", "OPTIONS"].includes(method.toUpperCase());
}

/** The invitation token travels in the URL fragment (never sent to a server); this extracts it. */
export function tokenFromHash(hash: string): string | null {
  const m = /^#?(?:.*&)?token=([A-Za-z0-9_-]{43})(?:&.*)?$/.exec(hash);
  return m ? m[1] : null;
}

/** 72 bytes is the most a password may have (BCrypt reads no more); the server enforces it, the page only helps. */
export function passwordBytes(password: string): number {
  return new TextEncoder().encode(password).length;
}

export const PASSWORD_MAX_BYTES = 72;
export const PASSWORD_MIN_LENGTH = 10;

export type PasswordProblem = "tooShort" | "tooLongBytes" | null;

export function passwordProblem(password: string): PasswordProblem {
  if ([...password].length < PASSWORD_MIN_LENGTH) return "tooShort";
  if (passwordBytes(password) > PASSWORD_MAX_BYTES) return "tooLongBytes";
  return null;
}
