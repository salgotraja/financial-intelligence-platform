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
