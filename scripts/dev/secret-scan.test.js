// Run: node --test scripts/dev/secret-scan.test.js
// Fake credentials are assembled at run time from fragments so that no secret-shaped literal exists in the repository.
const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { scanText, findings, digest, loadAllowlist, isAllowed } = require("./secret-scan.js");

const awsLike = "AK" + "IA" + "Q".repeat(16);
const ghLike = "gh" + "p_" + "a1B2".repeat(9);
const keyBlock = "-----BEGIN " + "PRIVATE KEY-----";
const assignment = 'api_key = "' + "z9".repeat(10) + '"';

test("flags secret-shaped values and reports positions only", () => {
  const hits = scanText(["ok line", `value ${awsLike}`, ghLike, keyBlock, assignment].join("\n"));
  assert.deepStrictEqual(hits.map((h) => h.line), [2, 3, 4, 5]);
  for (const h of hits) assert.deepStrictEqual(Object.keys(h).sort(), ["kind", "line"]);
});

test("ignores placeholders, environment references and ordinary text", () => {
  assert.deepStrictEqual(scanText('password = "changeme-changeme"'), []);
  assert.deepStrictEqual(scanText("token: process.env.SOME_TOKEN_VALUE_HERE"), []);
  assert.deepStrictEqual(scanText('"auth.password": "password label"'), []);
  assert.deepStrictEqual(scanText("const total = weight * rate / 100;"), []);
});

// ---- the allow-list suppresses exactly what was reviewed and nothing else -------------------------------------------------
const secretLine = (value) => `const PASSWORD = "${value}";`;
const reviewed = "q7".repeat(10);

test("an allow-list entry suppresses only the same file, kind and exact matched text", () => {
  const [hit] = findings(secretLine(reviewed));
  assert.ok(hit, "the fake credential is a finding");
  const entry = { file: "a/Test.java", kind: hit.kind, sha256: digest(hit.match), reason: "synthetic" };
  assert.strictEqual(isAllowed([entry], "a/Test.java", hit), true);
  assert.strictEqual(isAllowed([entry], "b/Test.java", hit), false, "another file is still reported");
  assert.strictEqual(isAllowed([{ ...entry, kind: "Private key block" }], "a/Test.java", hit), false, "another kind is still reported");
  const [changed] = findings(secretLine("z3".repeat(10)));
  assert.strictEqual(isAllowed([entry], "a/Test.java", changed), false, "a changed value is reported again");
  assert.strictEqual(isAllowed([], "a/Test.java", hit), false, "without an entry nothing is suppressed");
});

test("an allow-list entry without a reason or with a malformed digest is refused", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "secret-scan-allow-"));
  const write = (entries) => { const f = path.join(dir, "allow.json"); fs.writeFileSync(f, JSON.stringify({ entries })); return f; };
  const ok = { file: "x", kind: "Credential assignment", sha256: "a".repeat(64), reason: "synthetic" };
  assert.deepStrictEqual(loadAllowlist(write([ok])), [ok]);
  assert.throws(() => loadAllowlist(write([{ ...ok, reason: " " }])), /reason/);
  assert.throws(() => loadAllowlist(write([{ ...ok, sha256: "abc" }])), /sha256/);
  assert.throws(() => loadAllowlist(write([{ file: "x", kind: "k", reason: "r" }])), /sha256/);
});

test("the shipped allow-list is well-formed and has no stale entry: each one still matches a live finding", () => {
  const root = path.resolve(__dirname, "..", "..");
  const entries = loadAllowlist();
  assert.ok(entries.length > 0);
  for (const e of entries) {
    const live = findings(fs.readFileSync(path.join(root, e.file), "utf8"));
    assert.ok(live.some((h) => h.kind === e.kind && digest(h.match) === e.sha256), `stale allow-list entry for ${e.file}`);
  }
});
