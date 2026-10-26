#!/usr/bin/env node
// DEVELOPMENT / DISPOSABLE-DATABASE TOOL: the platform-side step of onboarding an owner, done as the DATABASE OWNER of a
// local docker PostgreSQL container, exactly what the platform console will do later (docs/AUTHENTICATION_SECURITY_DECISIONS.md).
//
//   node scripts/dev/provision-owner.js owner@example.test "Organisation name"
//
// It creates a platform.owner_account (status TRIAL) and a platform.owner_invitation that holds ONLY the SHA-256 digest of a
// fresh 256-bit token, and prints the invitation link to THIS terminal once. The owner then opens the link, chooses their
// own password in the application and signs in normally. Nothing here sets, hashes or shows a password, and the token is
// never written to a file or a log.
//
// Boundaries (all deliberate):
//   * it needs the database OWNER credential of the container (docker exec as the container's admin user): not a platform
//     administrator, not the application's runtime role, and no business user can run it;
//   * it refuses to run when MANDI_ENV is production, and talks only to a local docker container;
//   * it writes only to schema platform (owner_account, owner_invitation); it cannot read or touch any business data.
"use strict";

const crypto = require("node:crypto");
const { spawnSync } = require("node:child_process");

function base64url(buf) {
  return buf.toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** 256-bit token (43 URL-safe characters) and the SHA-256 digest of its 32 bytes, as the server stores and looks it up. */
function newToken() {
  const bytes = crypto.randomBytes(32);
  return { token: base64url(bytes), hashHex: crypto.createHash("sha256").update(bytes).digest("hex") };
}

function provisionOwner({ email, organisation, status = "TRIAL", container = process.env.PG_CONTAINER || "mandi-postgres",
  db = process.env.PG_DB || "mandi_demo", admin = process.env.PG_ADMIN_USER || "mandi", baseUrl = process.env.MANDI_PUBLIC_BASE_URL || "http://localhost:3000" }) {
  if (/^(prod|production)$/i.test(process.env.MANDI_ENV || "")) throw new Error("refusing to run: MANDI_ENV is production");
  if (!/^[\x21-\x7e]+@[a-z0-9.-]+\.[a-z0-9-]+$/.test(email) || email !== email.toLowerCase() || email.length > 254) {
    throw new Error("the email must be lower-case printable ASCII, for example owner@example.test");
  }
  if (!organisation || organisation.length > 200) throw new Error("an organisation name of at most 200 characters is required");
  if (!["TRIAL", "ACTIVE"].includes(status)) throw new Error("status must be TRIAL or ACTIVE");
  const { token, hashHex } = newToken();
  const sql = `
begin;
with acc as (insert into platform.owner_account (display_name, status) values (:'org', :'status') returning id)
insert into platform.owner_invitation (owner_account_id, email, token_hash, expires_at)
select id, :'email', decode(:'hash', 'hex'), now() + interval '7 days' from acc;
commit;`;
  const r = spawnSync("docker", ["exec", "-i", container, "psql", "-U", admin, "-d", db, "-X", "-q", "-v", "ON_ERROR_STOP=1",
    "-v", `org=${organisation}`, "-v", `status=${status}`, "-v", `email=${email}`, "-v", `hash=${hashHex}`], { input: sql, encoding: "utf8" });
  if (r.status !== 0) throw new Error(`database step failed: ${(r.stderr || "").trim().split("\n").slice(-2).join(" ")}`);
  return { token, link: `${baseUrl}/accept-invitation#token=${token}` };
}

module.exports = { provisionOwner, newToken };

if (require.main === module) {
  const [email, organisation] = process.argv.slice(2);
  if (!email || !organisation) {
    console.error('usage: node scripts/dev/provision-owner.js owner@example.test "Organisation name"');
    process.exit(2);
  }
  try {
    const { link } = provisionOwner({ email, organisation });
    console.log(`Invitation for ${email} (valid 7 days, single use). Give this link to the owner; it is not stored anywhere:\n${link}`);
  } catch (e) {
    console.error(e.message);
    process.exit(1);
  }
}
