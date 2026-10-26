#!/usr/bin/env node
// Disposable stack for the browser tests: a NEW PostgreSQL container (never the development database), the real backend
// built from this repository and started against it as the restricted runtime role, nothing else. Synthetic data only.
//
//   node scripts/e2e/stack.js start    # container + backend, prints nothing secret
//   node scripts/e2e/stack.js stop     # removes both
//
// Ports (loopback only): database 55434, backend 8091 (E2E_PG_PORT, E2E_API_PORT, E2E_PG_CONTAINER, E2E_WEB_PORT, E2E_JAR choose a second, isolated stack). The web application is started by Playwright (port 3100) from a build
// that proxies to the backend above (BACKEND_URL=http://127.0.0.1:8091 at build time). docs/RUNNING.md, "Browser tests".
"use strict";

const { spawn, spawnSync } = require("node:child_process");
const crypto = require("node:crypto");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { stopStack } = require("./stack-stop.js");

const ROOT = path.resolve(__dirname, "..", "..");
const CONTAINER = process.env.E2E_PG_CONTAINER || "mandi-e2e-pg";
const DB = "e2e";
const PG_PORT = Number(process.env.E2E_PG_PORT || 55434);
const API_PORT = Number(process.env.E2E_API_PORT || 8091);
// A second, isolated stack (other ports, container and web port) must not share state with the standard one.
const WEB_PORT = process.env.E2E_WEB_PORT || "3100";
const SUFFIX = API_PORT === 8091 ? "" : `-${API_PORT}`;
const STATE_DIR = process.env.E2E_STATE_DIR || os.tmpdir(); // E2E_STATE_DIR is used by the shutdown tests
const STATE = path.join(STATE_DIR, `mandi-e2e-state${SUFFIX}.json`);
const LOG = path.join(STATE_DIR, `mandi-e2e-backend${SUFFIX}.log`);

function sh(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, { encoding: "utf8", ...opts });
  if (r.status !== 0) throw new Error(`${cmd} ${args.join(" ")} failed: ${(r.stderr || r.stdout || "").trim().split("\n").slice(-3).join(" | ")}`);
  return r.stdout;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitFor(what, check, tries = 90) {
  for (let i = 0; i < tries; i++) {
    try {
      if (await check()) return;
    } catch {
      /* not yet */
    }
    await sleep(1000);
  }
  throw new Error(`timed out waiting for ${what}`);
}

async function start() {
  if (fs.existsSync(STATE)) await stop();
  const adminPw = crypto.randomBytes(12).toString("hex");
  const appPw = crypto.randomBytes(12).toString("hex");
  spawnSync("docker", ["rm", "-f", CONTAINER], { stdio: "ignore" });
  sh("docker", ["run", "-d", "--name", CONTAINER, "-e", `POSTGRES_DB=${DB}`, "-e", "POSTGRES_USER=mandi", "-e", `POSTGRES_PASSWORD=${adminPw}`,
    "-p", `127.0.0.1:${PG_PORT}:5432`, "postgres:16-alpine"]);
  await waitFor("PostgreSQL", () => spawnSync("docker", ["exec", CONTAINER, "pg_isready", "-U", "mandi", "-d", DB]).status === 0);
  await sleep(1500); // the image restarts the server once after initialization
  await waitFor("PostgreSQL (after init)", () => spawnSync("docker", ["exec", CONTAINER, "pg_isready", "-U", "mandi", "-d", DB]).status === 0);
  sh("docker", ["exec", "-i", CONTAINER, "psql", "-U", "mandi", "-d", DB, "-X", "-q", "-v", "ON_ERROR_STOP=1"], {
    input: `create role mandi_app login nosuperuser nocreatedb nocreaterole noinherit nobypassrls noreplication connection limit 30 password '${appPw}';
grant connect on database ${DB} to mandi_app;
revoke temporary on database ${DB} from public;`,
  });

  // the real application, as built from this repository
  const javaHome = process.env.JAVA_HOME || "C:/Users/Priyesh55/tools/jdk-21.0.12.1+1";
  const env = { ...process.env, JAVA_HOME: javaHome };
  const mvnw = path.join(ROOT, "backend", process.platform === "win32" ? "mvnw.cmd" : "mvnw");
  const q = (x) => `"${x}"`; // the repository path contains spaces
  // E2E_JAR: use an already built jar (for example when backend/target is locked by another stack) instead of packaging
  let jarPath = process.env.E2E_JAR;
  if (!jarPath) {
    sh(q(mvnw), ["-B", "-q", "-f", q(path.join(ROOT, "backend", "pom.xml")), "package", "-DskipTests"], { env, shell: true });
    const jar = fs.readdirSync(path.join(ROOT, "backend", "target")).filter((f) => /^mandi-backend-.*\.jar$/.test(f) && !f.endsWith(".original"))[0];
    if (!jar) throw new Error("backend jar not found");
    jarPath = path.join(ROOT, "backend", "target", jar);
  }
  const out = fs.openSync(LOG, "w");
  const java = path.join(javaHome, "bin", process.platform === "win32" ? "java.exe" : "java");
  const child = spawn(java, ["-jar", jarPath, `--server.port=${API_PORT}`, `--mandi.security.allowed-origins=http://localhost:${WEB_PORT},http://127.0.0.1:${WEB_PORT}`,
    "--mandi.security.login.max-attempts-per-ip=100000", "--mandi.security.login.max-attempts-per-email=1000"], {
    detached: true,
    stdio: ["ignore", out, out],
    env: {
      ...env,
      SPRING_PROFILES_ACTIVE: "demo",
      DB_URL: `jdbc:postgresql://127.0.0.1:${PG_PORT}/${DB}`,
      DB_USER: "mandi_app",
      DB_PASSWORD: appPw,
      MIGRATION_DB_USER: "mandi",
      MIGRATION_DB_PASSWORD: adminPw,
      MANDI_REQUIRE_RESTRICTED_DB_ROLE: "true",
      MANDI_PUBLIC_BASE_URL: `http://localhost:${WEB_PORT}`,
    },
  });
  child.unref();
  fs.writeFileSync(STATE, JSON.stringify({ pid: child.pid, container: CONTAINER, apiPort: API_PORT }));
  await waitFor("the backend", async () => (await fetch(`http://127.0.0.1:${API_PORT}/actuator/health`)).ok, 120);
  console.log(`e2e stack ready: database 127.0.0.1:${PG_PORT} (container ${CONTAINER}), backend http://127.0.0.1:${API_PORT} (log ${LOG})`);
}

async function stop() {
  // shutdown only ever touches what THIS stack's state file records (see stack-stop.js and stack.test.js)
  const r = stopStack({ statePath: STATE, explicitContainer: process.env.E2E_PG_CONTAINER });
  for (const n of r.notes) console.log(`stack stop: ${n}`);
  if (r.removedContainer) console.log(`stack stop: removed container ${r.removedContainer}`);
  if (r.killedPid) console.log(`stack stop: stopped backend process ${r.killedPid}`);
  if (r.action === "refused") throw new Error("stack stop refused: nothing was touched");
}

const cmd = process.argv[2];
(cmd === "start" ? start() : cmd === "stop" ? stop() : Promise.reject(new Error("usage: stack.js start|stop"))).catch((e) => {
  console.error(e.message);
  process.exit(1);
});
