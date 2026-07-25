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
