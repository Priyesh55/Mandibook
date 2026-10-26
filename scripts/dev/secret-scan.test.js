// Run: node --test scripts/dev/secret-scan.test.js
// Fake credentials are assembled at run time from fragments so that no secret-shaped literal exists in the repository.
const test = require("node:test");
const assert = require("node:assert");
const { scanText } = require("./secret-scan.js");

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
