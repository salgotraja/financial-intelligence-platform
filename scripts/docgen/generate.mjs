import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { renderBody, embedImage } from "./lib/render.mjs";
import { assemble } from "./lib/template.mjs";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(scriptDir, "..", "..");
const MD_PATH = join(repoRoot, "docs", "LEARNING-GUIDE.md");
const HTML_PATH = join(repoRoot, "docs", "learning-guide.html");

// The markdown sources are never released; the published HTML must not reference any .md file at
// all - not as a link, and not as a prose or code mention of a filename. This guard fails the build
// if any `*.md` token survives, so a reader of the HTML never sees a pointer to an unpublished file.
export function assertNoMarkdownReferences(html) {
  // Cheap linear check first: a bare `.md\b` scan has no greedy prefix, so it stays fast even on the
  // multi-MB base64 data URIs of embedded diagrams (a greedy `[\w./-]*\.md` prefix would backtrack
  // catastrophically over base64 runs). Only on an actual hit do we extract the filename for the error.
  if (!/\.md\b/.test(html)) return;
  const matches = [...html.matchAll(/[A-Za-z0-9._/-]{0,80}?\.md\b/g)].map((m) => m[0].replace(/^[^A-Za-z0-9]+/, ""));
  const unique = [...new Set(matches)].sort();
  throw new Error(
    `learning-guide.html references ${matches.length} markdown file(s) - inline the content or ` +
      `reword instead. Offending tokens: ${unique.join(", ")}`
  );
}

// Resolve `embed:PATH` inside src="" attributes that come from the static template (the markdown path
// is already resolved in renderBody). Keeps template-hosted diagrams self-contained too.
function embedTemplateAssets(html) {
  return html.replace(/src="embed:([^"]+)"/g, (_m, path) => `src="${embedImage("embed:" + path, repoRoot)}"`);
}

export function buildHtml() {
  const md = readFileSync(MD_PATH, "utf8");
  const top = readFileSync(join(scriptDir, "template.top.html"), "utf8");
  const bottom = readFileSync(join(scriptDir, "template.bottom.html"), "utf8");
  const body = renderBody(md, { repoRoot });
  const html = embedTemplateAssets(assemble(body, { top, bottom }));
  assertNoMarkdownReferences(html);
  return html;
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
