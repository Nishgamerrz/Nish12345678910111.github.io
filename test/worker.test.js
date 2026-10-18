import assert from "node:assert/strict";
import test from "node:test";
import worker from "../src/index.js";

const makeRequest = (path = "/", method = "GET") =>
  new Request(`https://worker.example${path}`, { method });

test("root returns safe service metadata, not executable code", async () => {
  const response = await worker.fetch(makeRequest());
  const body = await response.json();

  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type"), /application\/json/);
  assert.equal(body.executableLua, false);
  assert.deepEqual(body.endpoints, ["/health", "/version"]);
});

test("health endpoint reports the Worker as healthy", async () => {
  const response = await worker.fetch(makeRequest("/health"));
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { status: "ok" });
});

test("version endpoint returns metadata only", async () => {
  const response = await worker.fetch(makeRequest("/version"));
  const body = await response.json();

  assert.equal(response.status, 200);
  assert.equal(body.version, "1.0.0");
  assert.equal(body.contentType, "metadata-only");
});

test("unknown routes return 404", async () => {
  const response = await worker.fetch(makeRequest("/anything-else"));
  assert.equal(response.status, 404);
  assert.deepEqual(await response.json(), { error: "Not found" });
});

test("only GET and HEAD are allowed", async () => {
  const response = await worker.fetch(makeRequest("/", "POST"));
  assert.equal(response.status, 405);
  assert.equal(response.headers.get("allow"), "GET, HEAD");
});

test("HEAD returns headers without a response body", async () => {
  const response = await worker.fetch(makeRequest("/health", "HEAD"));
  assert.equal(response.status, 200);
  assert.equal(await response.text(), "");
});
