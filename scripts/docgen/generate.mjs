import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { renderBody } from "./lib/render.mjs";
import { assemble } from "./lib/template.mjs";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(scriptDir, "..", "..");
const MD_PATH = join(repoRoot, "docs", "LEARNING-GUIDE.md");
const HTML_PATH = join(repoRoot, "docs", "learning-guide.html");

export function buildHtml() {
  const md = readFileSync(MD_PATH, "utf8");
  const top = readFileSync(join(scriptDir, "template.top.html"), "utf8");
  const bottom = readFileSync(join(scriptDir, "template.bottom.html"), "utf8");
  const body = renderBody(md, { repoRoot });
  return assemble(body, { top, bottom });
}

function main() {
  const check = process.argv.includes("--check");
  const html = buildHtml();
  if (check) {
    const current = readFileSync(HTML_PATH, "utf8");
    if (current !== html) {
      console.error("learning-guide.html is stale. Run `npm run docs:build` and commit the result.");
      process.exit(1);
    }
    console.log("learning-guide.html is up to date.");
    return;
  }
  writeFileSync(HTML_PATH, html);
  console.log(`Wrote ${HTML_PATH}`);
}

if (process.argv[1] && process.argv[1].endsWith("generate.mjs")) main();
