#!/usr/bin/env node
// Claude Code PreToolUse hook. Reads the tool call as JSON on stdin; exit code 2 blocks the call and sends stderr to
// the model. Any other outcome (including malformed input) lets the normal permission rules decide.
const { evaluate } = require("./guard-rules.js");

let raw = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (c) => (raw += c));
process.stdin.on("end", () => {
  let event;
  try {
    event = JSON.parse(raw);
  } catch {
    process.exit(0);
  }
  const reason = evaluate(event.tool_name, event.tool_input);
  if (reason) {
    process.stderr.write(`Blocked by .claude/hooks/guard.js: ${reason}. See docs/AI_POLICY.md.\n`);
    process.exit(2);
  }
  process.exit(0);
});
