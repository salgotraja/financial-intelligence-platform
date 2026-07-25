import { test } from "node:test";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { dirname } from "node:path";
import { renderBody } from "../lib/render.mjs";

const repoRoot = dirname(fileURLToPath(import.meta.url)); // fixtures live under test/

test("leading H1 is dropped, hero owns the title", () => {
  const html = renderBody("# Big Title\n\n## Section A\n\ntext\n", { repoRoot });
  assert.ok(!html.includes("Big Title"));
  assert.ok(html.includes("Section A"));
});

test("headings get slug ids matching the runtime scheme", () => {
  const html = renderBody("## 6.2 The handler model\n", { repoRoot });
  assert.match(html, /<h2[^>]*id="6-2-the-handler-model"/);
});

test("plain fenced code is highlighted (wrapped in hljs markup)", () => {
  const html = renderBody("```java\nint x = 1;\n```\n", { repoRoot });
  assert.ok(html.includes("<pre"));
  assert.ok(html.includes("hljs"));
});

test("include directive pulls real source into the code block", () => {
  const md = "```java include=fixtures/Sample.java region=greet\n```\n";
  const html = renderBody(md, { repoRoot });
  assert.ok(html.includes("hello "));
  assert.ok(html.includes("greet"));
});

test("a broken include fails the render loudly", () => {
  const md = "```java include=fixtures/Sample.java region=ghost\n```\n";
  assert.throws(() => renderBody(md, { repoRoot }), /ghost/);
});
