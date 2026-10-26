// Shutdown of a disposable browser-test stack, written so that it can only ever affect the stack whose state file it was given.
// Rules (tested in stack.test.js):
//  - no state file: nothing is stopped, unless a container was named explicitly (E2E_PG_CONTAINER); the default container name is never guessed
//  - unreadable or malformed state: nothing is stopped and the file is kept for a person to look at
//  - the container removed is the one RECORDED in the state; if a container is also named explicitly and differs, nothing is touched
//  - the process is killed only if it is still running AND its command line shows it is this stack's backend (guards against a reused PID)
//  - a stale state (process already gone) still removes the recorded container and the state file
"use strict";

const { spawnSync, execFileSync } = require("node:child_process");
const fs = require("node:fs");

const NAME_RE = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,62}$/;

const realDeps = {
  docker: (name) => spawnSync("docker", ["rm", "-f", name], { stdio: "ignore" }).status === 0,
  kill: (pid) => {
    if (process.platform === "win32") execFileSync("taskkill", ["/PID", String(pid), "/T", "/F"], { stdio: "ignore" });
    else process.kill(pid);
  },
  describe: (pid) => {
    const r = process.platform === "win32"
      ? spawnSync("powershell", ["-NoProfile", "-Command", `(Get-CimInstance Win32_Process -Filter 'ProcessId=${Number(pid)}').CommandLine`], { encoding: "utf8" })
      : spawnSync("ps", ["-o", "args=", "-p", String(Number(pid))], { encoding: "utf8" });
    const text = (r.stdout || "").trim();
    return text === "" ? null : text;
  },
};

function stopStack({ statePath, explicitContainer, deps = {} }) {
  const d = { ...realDeps, ...deps };
  const notes = [];
  const result = (action) => ({ action, notes, removedContainer: null, killedPid: null });

  if (explicitContainer !== undefined && explicitContainer !== "" && !NAME_RE.test(explicitContainer)) {
    notes.push("the container name given in E2E_PG_CONTAINER is not a valid name");
    return result("refused");
  }
  if (!fs.existsSync(statePath)) {
    if (explicitContainer) {
      d.docker(explicitContainer);
      const r = result("stopped");
      r.removedContainer = explicitContainer;
      notes.push("no state file; removed only the container named explicitly");
      return r;
    }
    notes.push("no state file for this stack; nothing was stopped");
    return result("nothing");
  }

  let recorded;
  try {
    recorded = JSON.parse(fs.readFileSync(statePath, "utf8"));
  } catch {
    recorded = null;
  }
  if (!recorded || typeof recorded !== "object" || Array.isArray(recorded)) {
    notes.push("the state file is not valid; nothing was stopped and the file was kept");
    return result("refused");
  }
  if (typeof recorded.container !== "string" || !NAME_RE.test(recorded.container)) {
    notes.push("the state file records no valid container; nothing was stopped and the file was kept");
    return result("refused");
  }
  if (explicitContainer && explicitContainer !== recorded.container) {
    notes.push(`the state records container ${recorded.container} but ${explicitContainer} was named; nothing was stopped`);
    return result("refused");
  }

  const r = result("stopped");
  const pid = recorded.pid;
  if (Number.isInteger(pid) && pid > 0) {
    const cmdline = d.describe(pid);
    if (cmdline === null) {
      notes.push("the recorded process is no longer running (stale state)");
    } else {
      const expected = Number.isInteger(recorded.apiPort) ? `--server.port=${recorded.apiPort}` : "mandi-backend";
      if (cmdline.includes(expected)) {
        try {
          d.kill(pid);
          r.killedPid = pid;
        } catch {
          notes.push("the recorded process could not be stopped");
        }
      } else {
        notes.push("the recorded pid now belongs to a different program; it was not stopped");
      }
    }
  } else {
    notes.push("the state records no valid pid; no process was stopped");
  }
  d.docker(recorded.container);
  r.removedContainer = recorded.container;
  fs.rmSync(statePath, { force: true });
  return r;
}

module.exports = { stopStack, NAME_RE };
