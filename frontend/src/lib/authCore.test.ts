import assert from "node:assert/strict";
import { test } from "node:test";
import { csrfTokenFromCookie, needsFirmCheck, passwordBytes, passwordProblem, securityEventFor, tokenFromHash, isUnsafeMethod } from "./authCore.ts";

test("security events: 401, stale firm and no-access codes are recognised, ordinary errors are not", () => {
  assert.deepEqual(securityEventFor(401, undefined), { kind: "unauthenticated" });
  assert.deepEqual(securityEventFor(409, "FIRM_MISMATCH"), { kind: "firm-stale", code: "FIRM_MISMATCH" });
  assert.deepEqual(securityEventFor(409, "FIRM_CHECK_REQUIRED"), { kind: "firm-stale", code: "FIRM_CHECK_REQUIRED" });
  assert.deepEqual(securityEventFor(409, "FIRM_SELECTION_REQUIRED"), { kind: "firm-stale", code: "FIRM_SELECTION_REQUIRED" });
  assert.deepEqual(securityEventFor(403, "NO_MEMBERSHIP"), { kind: "blocked", code: "NO_MEMBERSHIP" });
  assert.deepEqual(securityEventFor(403, "ACCOUNT_BLOCKED"), { kind: "blocked", code: "ACCOUNT_BLOCKED" });
  assert.deepEqual(securityEventFor(403, "FIRM_INACTIVE"), { kind: "blocked", code: "FIRM_INACTIVE" });
  // a refusal of one action must not look like a lost session or a lost firm
  assert.equal(securityEventFor(403, "PERMISSION_DENIED"), null);
  assert.equal(securityEventFor(403, "CSRF_REJECTED"), null);
  assert.equal(securityEventFor(409, "ALREADY_MEMBER"), null);
  assert.equal(securityEventFor(404, undefined), null);
  assert.equal(securityEventFor(422, "INVALID_PASSWORD"), null);
  assert.equal(securityEventFor(500, undefined), null);
});

test("CSRF token is read from either cookie name and nothing else", () => {
  assert.equal(csrfTokenFromCookie("a=1; XSRF-TOKEN=abc-123; b=2"), "abc-123");
  assert.equal(csrfTokenFromCookie("__Host-XSRF-TOKEN=tok%2Fen"), "tok/en");
  assert.equal(csrfTokenFromCookie("mandi_session=secret; other=1"), null);
  assert.equal(csrfTokenFromCookie("XSRF-TOKEN="), null);
  assert.equal(csrfTokenFromCookie(""), null);
  assert.equal(csrfTokenFromCookie("NOT-XSRF-TOKEN=x"), null);
});

test("the firm check header goes on every business route and on no sign-in route", () => {
  assert.equal(needsFirmCheck("/masters/farmers"), true);
  assert.equal(needsFirmCheck("/aadat/bills/12"), true);
  assert.equal(needsFirmCheck("/firm/members"), true);
  assert.equal(needsFirmCheck("/config"), true);
  assert.equal(needsFirmCheck("/auth/me"), false);
  assert.equal(needsFirmCheck("/auth/active-firm"), false);
  assert.equal(needsFirmCheck("/auth/login"), false);
});

test("state-changing methods need a CSRF token, reads do not", () => {
  for (const m of ["POST", "put", "PATCH", "DELETE"]) assert.equal(isUnsafeMethod(m), true, m);
  for (const m of ["GET", "get", "HEAD", "OPTIONS"]) assert.equal(isUnsafeMethod(m), false, m);
});

test("the invitation token is taken from the URL fragment only, in the exact format of the server", () => {
  const t = "A".repeat(43);
  assert.equal(tokenFromHash(`#token=${t}`), t);
  assert.equal(tokenFromHash(`token=${t}`), t);
  assert.equal(tokenFromHash(`#x=1&token=${t}&y=2`), t);
  assert.equal(tokenFromHash(`#token=${"A".repeat(42)}`), null);
  assert.equal(tokenFromHash(`#token=${"A".repeat(44)}`), null);
  assert.equal(tokenFromHash(`#token=${"!".repeat(43)}`), null);
  assert.equal(tokenFromHash(""), null);
});

test("password helper counts UTF-8 bytes the way the server does", () => {
  assert.equal(passwordBytes("abc"), 3);
  assert.equal(passwordBytes("क".repeat(24)), 72);
  assert.equal(passwordProblem("क".repeat(24)), null);
  assert.equal(passwordProblem("क".repeat(25)), "tooLongBytes");
  assert.equal(passwordProblem("a".repeat(73)), "tooLongBytes");
  assert.equal(passwordProblem("a".repeat(72)), null);
  assert.equal(passwordProblem("a".repeat(9)), "tooShort");
  assert.equal(passwordProblem("क".repeat(10)), null);
});
