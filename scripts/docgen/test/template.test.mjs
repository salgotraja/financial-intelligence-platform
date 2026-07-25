import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { assemble } from "../lib/template.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..");
const top = readFileSync(join(root, "template.top.html"), "utf8");
const bottom = readFileSync(join(root, "template.bottom.html"), "utf8");

test("template top carries the head, CSS, and opens the reference section", () => {
  assert.ok(top.includes("<title>"));
  assert.ok(top.includes("</style>"));
  assert.ok(top.trimEnd().endsWith('<section id="reference">'));
});

test("template bottom closes reference and carries the nav-building script", () => {
  assert.ok(bottom.trimStart().startsWith("</section>"));
  assert.ok(bottom.includes("#guide-nav"));
  assert.ok(bottom.includes("</html>"));
});

test("assemble places the body between top and bottom", () => {
  const html = assemble("<h2>Hi</h2>", { top, bottom });
  assert.ok(html.indexOf('<section id="reference">') < html.indexOf("<h2>Hi</h2>"));
  // The landing zone in `top` contains its own `<section class="card">...</section>` blocks,
  // so the body must be checked against the LAST `</section>` (the one closing #reference,
  // supplied verbatim by `bottom`), not the first.
  assert.ok(html.indexOf("<h2>Hi</h2>") < html.lastIndexOf("</section>"));
});
