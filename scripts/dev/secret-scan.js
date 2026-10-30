#!/usr/bin/env node
// Dependency-free secret scan for tracked (or staged) files. Prints `file:line  kind` ONLY, never the matched text.
// Used by the pre-commit hook (.githooks/pre-commit) and by CI. Exit code 1 when a likely secret is found.
//   node scripts/dev/secret-scan.js            scan every tracked file
//   node scripts/dev/secret-scan.js --staged   scan the staged version of staged files
//   node scripts/dev/secret-scan.js --file p   scan one file (used by tests)
//   node scripts/dev/secret-scan.js --digests  list every finding with the SHA-256 of its matched text (never the text), to
//                                              review one and, only if it is verified harmless, add it to the allow-list
// Gitleaks is the preferred scanner where it is installed; this script is the fallback that always runs.
//
// Allow-list (scripts/dev/secret-scan-allowlist.json): a finding is suppressed only when file, finding kind AND the SHA-256 of
// the exact matched text all equal an entry that carries a written reason. Changing the value, moving it to another file or
// adding any other finding makes the scanner report it again. Suppressed findings are counted in the output, never hidden.
const { execFileSync } = require("node:child_process");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const ALLOWLIST_FILE = path.join(__dirname, "secret-scan-allowlist.json");

const RULES = [
  ["AWS access key id", /AKIA[0-9A-Z]{16}/],
  ["GitHub token", /gh[pousr]_[A-Za-z0-9]{36}/],
  ["GitHub fine-grained token", /github_pat_[A-Za-z0-9_]{20,}/],
  ["GitLab token", /glpat-[A-Za-z0-9_-]{20}/],
  ["Slack token", /xox[baprs]-[A-Za-z0-9-]{10,}/],
  ["Anthropic API key", /sk-ant-[A-Za-z0-9_-]{20,}/],
  ["API secret key (sk-)", /sk-(proj-)?[A-Za-z0-9_-]{20,}/],
  ["Stripe live key", /[sr]k_live_[A-Za-z0-9]{16,}/],
  ["Google API key", /AIza[0-9A-Za-z_-]{35}/],
  ["Private key block", /-----BEGIN ([A-Z]+ )?PRIVATE KEY-----/],
  ["JSON web token", /eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\./],
  ["Credential assignment", /(api[_-]?key|secret|token|passw(or)?d|client[_-]?secret)["']?\s*[:=]\s*["'][^"'\s]{12,}["']/i],
];
const PLACEHOLDER = /(xxx|changeme|example|placeholder|<[a-z_ -]+>|\$\{|process\.env|dummy|redacted|test-only|not-a-secret)/i;
const SKIP_PATH = /(^|\/)(node_modules|\.next|\.next-e2e|target|playwright-report|test-results)\/|\.(png|jpg|jpeg|gif|pdf|ico|woff2?|jar|class|tsbuildinfo)$|(^|\/)package-lock\.json$/;

/** Every likely secret of a text with the exact matched text (for hashing only; callers must never print it). */
function findings(text) {
  const hits = [];
  text.split(/\r?\n/).forEach((line, i) => {
    for (const [kind, re] of RULES) {
      const m = re.exec(line);
      if (m && !PLACEHOLDER.test(line)) {
        hits.push({ line: i + 1, kind, match: m[0] });
        break;
      }
    }
  });
  return hits;
}

function scanText(text) {
  return findings(text).map(({ line, kind }) => ({ line, kind }));
}

const digest = (match) => crypto.createHash("sha256").update(match, "utf8").digest("hex");

function loadAllowlist(file = ALLOWLIST_FILE) {
  if (!fs.existsSync(file)) return [];
  const entries = JSON.parse(fs.readFileSync(file, "utf8")).entries || [];
  for (const e of entries) {
    if (!e.file || !e.kind || !/^[0-9a-f]{64}$/.test(e.sha256 || "") || !String(e.reason || "").trim()) {
      throw new Error("secret-scan allow-list: every entry needs file, kind, a 64-hex sha256 and a reason");
    }
  }
  return entries;
}

const isAllowed = (allow, file, h) => allow.some((e) => e.file === file && e.kind === h.kind && e.sha256 === digest(h.match));

function git(args) {
  return execFileSync("git", args, { encoding: "utf8", maxBuffer: 256 * 1024 * 1024 });
}

function targets(mode) {
  if (mode === "staged") return git(["diff", "--cached", "--name-only", "--diff-filter=ACM"]).split("\n").filter(Boolean);
  return git(["ls-files"]).split("\n").filter(Boolean);
}

function main(argv) {
  const fileIdx = argv.indexOf("--file");
  const digests = argv.includes("--digests");
  const allow = digests ? [] : loadAllowlist();
  let found = 0;
  let suppressed = 0;
  const report = (file, text) => findings(text).forEach((h) => {
    if (isAllowed(allow, file.split(path.sep).join("/"), h)) { suppressed++; return; }
    found++;
    console.log(digests ? `${file}:${h.line}  ${h.kind}  ${digest(h.match)}` : `${file}:${h.line}  ${h.kind}  [REDACTED]`);
  });
  if (fileIdx >= 0) {
    report(argv[fileIdx + 1], fs.readFileSync(argv[fileIdx + 1], "utf8"));
  } else {
    const staged = argv.includes("--staged");
    for (const f of targets(staged ? "staged" : "all")) {
      if (SKIP_PATH.test(f) || f === "scripts/dev/secret-scan.test.js") continue;
      let text;
      try {
        text = staged ? git(["show", `:${f}`]) : fs.readFileSync(f, "utf8");
      } catch {
        continue;
      }
      if (text.includes("\u0000")) continue;
      report(f, text);
    }
  }
  const note = suppressed > 0 ? ` (${suppressed} verified test constant(s) suppressed by scripts/dev/secret-scan-allowlist.json)` : "";
  console.log(found === 0 ? `secret-scan: no likely secrets found${note}` : `secret-scan: ${found} likely secret(s) found (values not printed)${note}`);
  return found === 0 || digests ? 0 : 1;
}

if (require.main === module) process.exit(main(process.argv.slice(2)));
module.exports = { scanText, findings, digest, loadAllowlist, isAllowed };
