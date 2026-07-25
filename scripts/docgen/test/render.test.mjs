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

test("links to .md files are stripped (text kept, no <a href=...md>)", () => {
  const html = renderBody("See [the user guide](USER-GUIDE.md) and [spec](./docs/spec.md#top).\n", { repoRoot });
  assert.ok(!/href="[^"]*\.md/i.test(html), "no .md href should remain");
  assert.ok(html.includes("the user guide"), "link text is preserved");
  assert.ok(html.includes("spec"), "second link text preserved");
});

test("bare .md filenames are not auto-linked (linkify off)", () => {
  const html = renderBody("Refer to USER-GUIDE.md for commands.\n", { repoRoot });
  assert.ok(!/href="[^"]*\.md/i.test(html), "no auto-linked .md");
  assert.ok(html.includes("USER-GUIDE.md"), "filename remains as text");
});

test("non-md links are left intact", () => {
  const html = renderBody("[AWS](https://aws.amazon.com/lambda).\n", { repoRoot });
  assert.ok(html.includes('href="https://aws.amazon.com/lambda"'));
});

test("embed: image src becomes a base64 data URI", () => {
  const md = "![Arch](embed:docs/assets/financial_intelligence_platform_architecture.drawio.png)\n";
  const realRoot = new URL("../../../", import.meta.url).pathname;
  const html = renderBody(md, { repoRoot: realRoot });
  assert.match(html, /<img[^>]+src="data:image\/png;base64,/);
  assert.ok(!html.includes("embed:"), "embed: prefix is resolved away");
});

test("embed: on a missing image fails loudly", () => {
  const realRoot = new URL("../../../", import.meta.url).pathname;
  assert.throws(() => renderBody("![x](embed:docs/assets/nope.png)\n", { repoRoot: realRoot }), /nope\.png/);
});
