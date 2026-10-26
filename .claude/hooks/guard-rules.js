// Deterministic guard rules for Claude Code PreToolUse (see guard.js and docs/AI_POLICY.md).
// Pure functions: given a tool name and its input, return a reason string when the action must be blocked, else null.
// This is a safety net against clearly prohibited actions, NOT a security boundary: a determined shell command can
// still be written around it. The real boundaries are the permission rules in .claude/settings.json, the database
// roles and CI.

const SECRET_PATH = /(^|[\\/])\.env(\.local|\.[\w-]*\.local|\.production|\.prod)?$/i;
const PROTECTED_PATH = /(^|[\\/])backups([\\/]|$)|\.(dump|bak|mdf|ldf)$/i;

const BASH_RULES = [
  [/\bgit\s+push\b[^\n;|&]*(--force\b|--force-with-lease\b|\s-f\b)/, "force-push is not allowed"],
  [/\bgit\s+reset\s+--hard\b/, "git reset --hard discards work"],
  [/\bgit\s+clean\b[^\n;|&]*\s-[a-z]*f/, "git clean -f deletes untracked work"],
  [/\bgit\s+(stash\s+(drop|clear)|checkout\s+--\s|restore\s+\.)/, "this discards uncommitted work"],
  [/--no-verify\b|--no-gpg-sign\b/, "bypassing hooks or signing is not allowed"],
  [/\brm\s+(-[a-z]*r[a-z]*f|-[a-z]*f[a-z]*r)[a-z]*\s+(?!(\.\/)?(\S*\/)?(node_modules|target|\.next|\.next-e2e|test-results|playwright-report)(\s|\/|$))/, "rm -rf is only allowed on build output folders"],
  [/\b(curl|wget)\b[^\n]*\|\s*(sudo\s+)?(ba)?sh\b/, "piping a download into a shell is not allowed"],
  [/\b(drop|truncate)\s+(table|database|schema)\b/i, "destructive SQL is not allowed from an agent"],
  [/\bdocker\s+(compose\s+down\s+[^\n]*-v|volume\s+(rm|prune)|system\s+prune)/, "this deletes database volumes"],
  [/--dangerously-skip-permissions|bypassPermissions/, "permission bypass modes are not allowed"],
];

// Any command that opens a connection to, or changes, the protected development database.
const MANDI_DEMO_TOOLS = /\b(psql|pg_dump|pg_restore|pg_dumpall|dropdb|createdb|flyway|docker\s+exec|mvnw?\s+[^\n]*flyway)\b/;

function checkBash(command) {
  for (const [re, why] of BASH_RULES) if (re.test(command)) return why;
  if (/mandi_demo/.test(command) && MANDI_DEMO_TOOLS.test(command)) {
    return "mandi_demo is the protected development database; use a disposable database (scripts/e2e/stack.js or Testcontainers)";
  }
  // reading secret or backup files through the shell
  const readers = /\b(cat|type|less|more|head|tail|grep|rg|sed|awk|cp|mv|scp|source|\.|Get-Content|gc)\b/;
  if (readers.test(command)) {
    for (const tok of command.split(/\s+/)) {
      const t = tok.replace(/^["']|["']$/g, "");
      if (SECRET_PATH.test(t) && !/\.env\.example$/i.test(t)) return "environment files hold local secrets and must not be read";
      if (PROTECTED_PATH.test(t)) return "backup and database files must not be read";
    }
  }
  return null;
}

function checkPath(p) {
  if (!p) return null;
  if (/\.env\.example$/i.test(p)) return null;
  if (SECRET_PATH.test(p)) return "environment files hold local secrets";
  if (PROTECTED_PATH.test(p)) return "backup and database files are protected";
  return null;
}

function evaluate(toolName, toolInput) {
  const input = toolInput || {};
  if (toolName === "Bash" || toolName === "PowerShell") return checkBash(String(input.command || ""));
  if (["Read", "Edit", "Write", "NotebookEdit"].includes(toolName)) return checkPath(String(input.file_path || input.notebook_path || ""));
  if (toolName === "Grep" || toolName === "Glob") return checkPath(String(input.path || ""));
  return null;
}

module.exports = { evaluate, checkBash, checkPath };
