// Address of the web application under test. Defaults to the standard disposable stack (port 3100); set E2E_WEB_PORT to run a
// second, isolated stack beside it (scripts/e2e/stack.js reads the same variable). docs/RUNNING.md, "Browser tests".
export const WEB_PORT = process.env.E2E_WEB_PORT ?? "3100";
export const WEB_URL = `http://localhost:${WEB_PORT}`;
