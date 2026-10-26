// Run: node --test scripts/e2e/stack.test.js
// Proves that `stack.js stop` affects only the process and container recorded in the selected stack's state file.
// Part 1 uses mocks (no Docker, no processes). Part 2 uses REAL disposable resources created by the test itself: two throw-away
// containers with random names and two dummy node processes; it never touches any other stack, container or database.
const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawn, spawnSync } = require("node:child_process");
const { stopStack } = require("./stack-stop.js");

function tmp() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "stack-stop-test-"));
}
function fake(over = {}) {
  const calls = { docker: [], kill: [] };
  return {
    calls,
    deps: {
      docker: (n) => { calls.docker.push(n); return true; },
      kill: (p) => { calls.kill.push(p); },
      describe: () => "java -jar mandi-backend-0.0.1-SNAPSHOT.jar --server.port=8092",
      ...over,
    },
  };
}
function stateFile(dir, content, name = "mandi-e2e-state-8092.json") {
  const p = path.join(dir, name);
  fs.writeFileSync(p, typeof content === "string" ? content : JSON.stringify(content));
  return p;
}

test("a valid state: only the recorded container and process are stopped, then the state is removed", () => {
  const f = fake();
  const p = stateFile(tmp(), { pid: 4242, container: "mandi-e2e-pg-b", apiPort: 8092 });
  const r = stopStack({ statePath: p, deps: f.deps });
  assert.strictEqual(r.action, "stopped");
  assert.deepStrictEqual(f.calls.docker, ["mandi-e2e-pg-b"]);
  assert.deepStrictEqual(f.calls.kill, [4242]);
  assert.ok(!fs.existsSync(p));
});

test("no state file and no explicit container: nothing at all is stopped (the default container is never guessed)", () => {
  const f = fake();
  const r = stopStack({ statePath: path.join(tmp(), "mandi-e2e-state.json"), deps: f.deps });
  assert.strictEqual(r.action, "nothing");
  assert.deepStrictEqual(f.calls.docker, []);
  assert.deepStrictEqual(f.calls.kill, []);
});

test("no state file but a container named explicitly: only that container is removed", () => {
  const f = fake();
  const r = stopStack({ statePath: path.join(tmp(), "none.json"), explicitContainer: "mandi-e2e-pg-b", deps: f.deps });
  assert.strictEqual(r.action, "stopped");
  assert.deepStrictEqual(f.calls.docker, ["mandi-e2e-pg-b"]);
  assert.deepStrictEqual(f.calls.kill, []);
});

for (const [label, content] of [["not JSON", "{ nope"], ["empty", ""], ["a JSON array", "[1,2]"], ["JSON null", "null"], ["a number", "7"]]) {
  test(`malformed state (${label}): nothing is stopped and the file is kept`, () => {
    const f = fake();
    const p = stateFile(tmp(), content);
    const r = stopStack({ statePath: p, deps: f.deps });
    assert.strictEqual(r.action, "refused");
    assert.deepStrictEqual(f.calls.docker, []);
    assert.deepStrictEqual(f.calls.kill, []);
    assert.ok(fs.existsSync(p));
  });
}

for (const [label, state] of [
  ["no container", { pid: 1234 }],
  ["a container name with shell characters", { pid: 1234, container: "x; docker rm -f other" }],
  ["a container name starting with a dash", { pid: 1234, container: "-f" }],
  ["a non-string container", { pid: 1234, container: 42 }],
]) {
  test(`state with ${label}: refused, nothing touched`, () => {
    const f = fake();
    const p = stateFile(tmp(), state);
    assert.strictEqual(stopStack({ statePath: p, deps: f.deps }).action, "refused");
    assert.deepStrictEqual(f.calls.docker, []);
    assert.deepStrictEqual(f.calls.kill, []);
    assert.ok(fs.existsSync(p));
  });
}

test("mismatched state: the state records one container, another is named explicitly: refused", () => {
  const f = fake();
  const p = stateFile(tmp(), { pid: 4242, container: "mandi-e2e-pg-b", apiPort: 8092 });
  const r = stopStack({ statePath: p, explicitContainer: "mandi-e2e-pg", deps: f.deps });
  assert.strictEqual(r.action, "refused");
  assert.deepStrictEqual(f.calls.docker, []);
  assert.deepStrictEqual(f.calls.kill, []);
  assert.ok(fs.existsSync(p));
});

test("an invalid explicit container name is refused even with a good state", () => {
  const f = fake();
  const p = stateFile(tmp(), { pid: 4242, container: "mandi-e2e-pg-b", apiPort: 8092 });
  assert.strictEqual(stopStack({ statePath: p, explicitContainer: "a b", deps: f.deps }).action, "refused");
  assert.deepStrictEqual(f.calls.docker, []);
});

test("stale state (process already gone): the recorded container and the state are removed, nobody is killed", () => {
  const f = fake({ describe: () => null });
  const p = stateFile(tmp(), { pid: 4242, container: "mandi-e2e-pg-b", apiPort: 8092 });
  const r = stopStack({ statePath: p, deps: f.deps });
  assert.strictEqual(r.action, "stopped");
  assert.deepStrictEqual(f.calls.docker, ["mandi-e2e-pg-b"]);
  assert.deepStrictEqual(f.calls.kill, []);
  assert.ok(!fs.existsSync(p));
});

test("a reused pid (a different program now has it) is never killed; the recorded container is still removed", () => {
  const f = fake({ describe: () => "C:\\Windows\\System32\\notepad.exe" });
  const p = stateFile(tmp(), { pid: 4242, container: "mandi-e2e-pg-b", apiPort: 8092 });
  const r = stopStack({ statePath: p, deps: f.deps });
  assert.deepStrictEqual(f.calls.kill, []);
  assert.deepStrictEqual(f.calls.docker, ["mandi-e2e-pg-b"]);
  assert.match(r.notes.join(" "), /different program/);
});

test("a backend of ANOTHER stack (other port) is not killed even if the pid in the state matches it", () => {
  const f = fake({ describe: () => "java -jar mandi-backend.jar --server.port=8091" });
  const p = stateFile(tmp(), { pid: 4242, container: "mandi-e2e-pg-b", apiPort: 8092 });
  stopStack({ statePath: p, deps: f.deps });
  assert.deepStrictEqual(f.calls.kill, []);
});

test("an old-format state without apiPort still requires the backend's jar name in the command line", () => {
  const ok = fake();
  stopStack({ statePath: stateFile(tmp(), { pid: 11, container: "mandi-e2e-pg" }), deps: ok.deps });
  assert.deepStrictEqual(ok.calls.kill, [11]);
  const other = fake({ describe: () => "node something-else.js" });
  stopStack({ statePath: stateFile(tmp(), { pid: 11, container: "mandi-e2e-pg" }), deps: other.deps });
  assert.deepStrictEqual(other.calls.kill, []);
});

for (const pid of [0, -5, 1.5, "12", null, undefined]) {
  test(`an invalid recorded pid (${String(pid)}) kills nothing`, () => {
    const f = fake();
    stopStack({ statePath: stateFile(tmp(), { pid, container: "mandi-e2e-pg-b" }), deps: f.deps });
    assert.deepStrictEqual(f.calls.kill, []);
  });
}

test("two stacks have separate state files: stopping one leaves the other's state alone", () => {
  const dir = tmp();
  const f = fake();
  const other = stateFile(dir, { pid: 9, container: "mandi-e2e-pg", apiPort: 8091 }, "mandi-e2e-state.json");
  const mine = stateFile(dir, { pid: 4242, container: "mandi-e2e-pg-b", apiPort: 8092 });
  stopStack({ statePath: mine, deps: f.deps });
  assert.ok(fs.existsSync(other));
  assert.ok(!fs.existsSync(mine));
  assert.deepStrictEqual(f.calls.docker, ["mandi-e2e-pg-b"]);
});

// ---- Part 2: real, disposable resources, through the real command line ----------------------------------------------------

const dockerOk = spawnSync("docker", ["info"], { stdio: "ignore" }).status === 0;
const rand = Math.random().toString(36).slice(2, 8);
const nameA = `mandi-stacktest-a-${rand}`;
const nameB = `mandi-stacktest-b-${rand}`;
const exists = (n) => spawnSync("docker", ["inspect", "-f", "{{.State.Running}}", n], { encoding: "utf8" }).stdout.trim() === "true";
const alive = (pid) => {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
};
const dummy = (port) => {
  const c = spawn(process.execPath, ["-e", "setInterval(()=>{},1000)", "--", `--server.port=${port}`, "mandi-backend-test"], { stdio: "ignore", detached: true });
  c.unref();
  return c.pid;
};
const stopCli = (env) => spawnSync(process.execPath, [path.join(__dirname, "stack.js"), "stop"], { env: { ...process.env, ...env }, encoding: "utf8" });

test("real resources: stop removes only the recorded container and process; a control stack survives, and a default stop touches nothing",
  { skip: dockerOk ? false : "Docker is not available" }, async () => {
  const dir = tmp();
  const port = 58000 + Math.floor(Math.random() * 1000);
  const pidMine = dummy(port);
  const pidControl = dummy(port + 1);
  try {
    for (const n of [nameA, nameB]) {
      assert.strictEqual(spawnSync("docker", ["run", "-d", "--name", n, "-e", "POSTGRES_HOST_AUTH_METHOD=trust", "postgres:16-alpine"], { encoding: "utf8" }).status, 0);
    }
    await new Promise((r) => setTimeout(r, 1500)); // let the dummy processes start
    assert.ok(alive(pidMine) && alive(pidControl) && exists(nameA) && exists(nameB));

    // state of "my" stack at API port P; the control stack has its own state (default name) and must be left alone
    fs.writeFileSync(path.join(dir, `mandi-e2e-state-${port}.json`), JSON.stringify({ pid: pidMine, container: nameA, apiPort: port }));
    fs.writeFileSync(path.join(dir, "mandi-e2e-state.json"), JSON.stringify({ pid: pidControl, container: nameB, apiPort: port + 1 }));

    // 1. a default stop (the standard stack's state) with an unrelated state dir entry removed would be wrong; first prove that a
    //    stop for a port without any state file touches nothing
    const none = stopCli({ E2E_STATE_DIR: dir, E2E_API_PORT: String(port + 500), E2E_PG_CONTAINER: "" });
    assert.strictEqual(none.status, 0, none.stderr + none.stdout);
    assert.ok(alive(pidMine) && alive(pidControl) && exists(nameA) && exists(nameB), "a stop without state must touch nothing");

    // 2. stop MY stack
    const mine = stopCli({ E2E_STATE_DIR: dir, E2E_API_PORT: String(port) });
    assert.strictEqual(mine.status, 0, mine.stderr + mine.stdout);
    await new Promise((r) => setTimeout(r, 1000));
    assert.ok(!alive(pidMine), "my backend process must be stopped");
    assert.ok(!exists(nameA), "my container must be removed");
    assert.ok(alive(pidControl), "the control process must survive");
    assert.ok(exists(nameB), "the control container must survive");
    assert.ok(fs.existsSync(path.join(dir, "mandi-e2e-state.json")), "the control state file must survive");
    assert.ok(!fs.existsSync(path.join(dir, `mandi-e2e-state-${port}.json`)));

    // 3. a mismatched explicit container is refused and nothing more is touched
    fs.writeFileSync(path.join(dir, `mandi-e2e-state-${port}.json`), JSON.stringify({ pid: pidControl, container: nameB, apiPort: port }));
    const mismatch = stopCli({ E2E_STATE_DIR: dir, E2E_API_PORT: String(port), E2E_PG_CONTAINER: nameA });
    assert.notStrictEqual(mismatch.status, 0);
    assert.ok(alive(pidControl) && exists(nameB), "a refused stop must touch nothing");

    // 4. the pid in the state belongs to a process of ANOTHER port: it is not killed
    const wrongPort = stopCli({ E2E_STATE_DIR: dir, E2E_API_PORT: String(port) });
    assert.strictEqual(wrongPort.status, 0, wrongPort.stderr + wrongPort.stdout);
    assert.ok(alive(pidControl), "a process of another port must not be killed even if its pid is in this state");
    assert.ok(!exists(nameB), "the recorded container of that state is removed");
  } finally {
    for (const n of [nameA, nameB]) spawnSync("docker", ["rm", "-f", n], { stdio: "ignore" });
    for (const p of [pidMine, pidControl]) {
      try {
        process.kill(p);
      } catch {
        /* already gone */
      }
    }
  }
});
