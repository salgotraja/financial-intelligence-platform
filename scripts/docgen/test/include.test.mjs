import { test } from "node:test";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { resolveInclude } from "../lib/include.mjs";
import { slugify } from "../lib/slug.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = here; // fixtures are addressed relative to this root in tests

test("region extraction returns only the tagged span, dedented, without markers", () => {
  const code = resolveInclude({ file: "fixtures/Sample.java", region: "greet", repoRoot });
  assert.equal(
    code,
    'public String greet(String name) {\n    return "hello " + name;\n}'
  );
});

test("line range extraction returns the exact 1-based inclusive range", () => {
  const code = resolveInclude({ file: "fixtures/Sample.java", lines: "1-2", repoRoot });
  assert.equal(code, "package demo;\n");
});

test("whole-file include returns the file trimmed of a trailing newline", () => {
  const code = resolveInclude({ file: "fixtures/Sample.java", repoRoot });
  assert.ok(code.startsWith("package demo;"));
  assert.ok(code.includes("unused"));
});

test("missing file throws", () => {
  assert.throws(() => resolveInclude({ file: "fixtures/Nope.java", repoRoot }), /Nope\.java/);
});

test("missing region throws with the region name", () => {
  assert.throws(() => resolveInclude({ file: "fixtures/Sample.java", region: "ghost", repoRoot }), /ghost/);
});

test("slugify matches the runtime scheme", () => {
  assert.equal(slugify("6.2 Spring Cloud Function: the handler model"), "6-2-spring-cloud-function-the-handler-model");
  assert.equal(slugify("  Trailing --- "), "trailing");
});
