#!/usr/bin/env node
// Dependency-free secret scan for tracked (or staged) files. Prints `file:line  kind` ONLY, never the matched text.
// Used by the pre-commit hook (.githooks/pre-commit) and by CI. Exit code 1 when a likely secret is found.
//   node scripts/dev/secret-scan.js            scan every tracked file
//   node scripts/dev/secret-scan.js --staged   scan the staged version of staged files
//   node scripts/dev/secret-scan.js --file p   scan one file (used by tests)
// Gitleaks is the preferred scanner where it is installed; this script is the fallback that always runs.
const { execFileSync } = require("node:child_process");
const fs = require("node:fs");

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

function scanText(text) {
  const hits = [];
  text.split(/\r?\n/).forEach((line, i) => {
    for (const [kind, re] of RULES) {
      if (re.test(line) && !PLACEHOLDER.test(line)) {
        hits.push({ line: i + 1, kind });
        break;
      }
    }
  });
  return hits;
}

function git(args) {
  return execFileSync("git", args, { encoding: "utf8", maxBuffer: 256 * 1024 * 1024 });
}

function targets(mode) {
  if (mode === "staged") return git(["diff", "--cached", "--name-only", "--diff-filter=ACM"]).split("\n").filter(Boolean);
  return git(["ls-files"]).split("\n").filter(Boolean);
}

function main(argv) {
  const fileIdx = argv.indexOf("--file");
  let found = 0;
  const report = (file, hits) => hits.forEach((h) => { found++; console.log(`${file}:${h.line}  ${h.kind}  [REDACTED]`); });
  if (fileIdx >= 0) {
    report(argv[fileIdx + 1], scanText(fs.readFileSync(argv[fileIdx + 1], "utf8")));
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
      report(f, scanText(text));
    }
  }
  console.log(found === 0 ? "secret-scan: no likely secrets found" : `secret-scan: ${found} likely secret(s) found (values not printed)`);
  return found === 0 ? 0 : 1;
}

if (require.main === module) process.exit(main(process.argv.slice(2)));
module.exports = { scanText };
