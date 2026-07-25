import { test } from "node:test";
import assert from "node:assert/strict";
import { buildHtml } from "../generate.mjs";

test("buildHtml produces a self-contained page with the real chrome and rendered body", () => {
  const html = buildHtml();
  assert.ok(html.startsWith("<!doctype html>"));
  assert.ok(html.includes('<section id="reference">'));
  assert.ok(html.includes("#guide-nav")); // script preserved
  assert.ok(!/<script src=|<link [^>]*href="http/.test(html)); // no external refs
  assert.ok(html.includes("</html>"));
});

import { assertNoMarkdownReferences } from "../generate.mjs";

test("assertNoMarkdownReferences throws when a .md filename appears", () => {
  assert.throws(
    () => assertNoMarkdownReferences("<p>see USER-GUIDE.md for details</p>"),
    /USER-GUIDE\.md/
  );
  assert.throws(
    () => assertNoMarkdownReferences("<p>(docs/STATUS.md, 2026)</p>"),
    /STATUS\.md/
  );
});

test("assertNoMarkdownReferences passes clean html", () => {
  assert.doesNotThrow(() => assertNoMarkdownReferences("<p>see the operator guide</p>"));
});

test("assertNoMarkdownReferences stays fast on multi-MB base64 (no catastrophic backtracking)", () => {
  // A ~3MB base64-like blob full of '/' chars (as in embedded SVG data URIs) must not hang the guard.
  const blob = "data:image/svg+xml;base64," + "aB9/x-Z.q0".repeat(320000);
  const start = Date.now();
  assert.doesNotThrow(() => assertNoMarkdownReferences(`<img src="${blob}">`));
  assert.ok(Date.now() - start < 2000, "guard must scan multi-MB input in well under 2s");
});

import { assertNoEmDashes } from "../generate.mjs";

test("assertNoEmDashes throws on an em-dash and passes clean text", () => {
  assert.throws(() => assertNoEmDashes("<p>fast, deterministic — not an error</p>"), /em\/en-dash/);
  assert.doesNotThrow(() => assertNoEmDashes("<p>fast, deterministic, not an error</p>"));
});
