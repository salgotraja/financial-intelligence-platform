import MarkdownIt from "markdown-it";
import hljs from "highlight.js";
import { slugify } from "./slug.mjs";
import { resolveInclude } from "./include.mjs";

function highlight(code, lang) {
  if (lang && hljs.getLanguage(lang)) {
    try {
      return hljs.highlight(code, { language: lang, ignoreIllegals: true }).value;
    } catch {
      /* fall through to escaped plain text */
    }
  }
  return null; // signal caller to use markdown-it's default escaping
}

// The markdown sources are local-only and never published; the generated HTML must therefore
// contain no link to any .md file. Match hrefs that point at a markdown file (with optional #anchor).
function isMarkdownHref(href) {
  return /\.md(#[^"']*)?$/i.test(href || "");
}

// Strip .md links in place: turn link_open/link_close into empty text tokens so the inner link text
// still renders, but no <a href="...md"> is emitted.
function stripMarkdownLinks(tokens) {
  for (const token of tokens) {
    if (token.type !== "inline" || !token.children) continue;
    const kids = token.children;
    for (let j = 0; j < kids.length; j++) {
      if (kids[j].type !== "link_open" || !isMarkdownHref(kids[j].attrGet("href"))) continue;
      let depth = 1;
      let k = j + 1;
      for (; k < kids.length && depth > 0; k++) {
        if (kids[k].type === "link_open") depth++;
        else if (kids[k].type === "link_close" && --depth === 0) break;
      }
      kids[j].type = "text";
      kids[j].content = "";
      if (k < kids.length) {
        kids[k].type = "text";
        kids[k].content = "";
      }
    }
  }
}

export function renderBody(markdown, { repoRoot }) {
  const md = new MarkdownIt({
    html: true,
    linkify: false,
    typographer: false,
    highlight(code, infoLang) {
      const hl = highlight(code, infoLang);
      const body = hl ?? md.utils.escapeHtml(code);
      const cls = infoLang ? ` class="hljs language-${md.utils.escapeHtml(infoLang)}"` : ' class="hljs"';
      return `<pre><code${cls}>${body}</code></pre>`;
    },
  });

  // Fence rule override: parse the info string, resolve include= directives before highlighting.
  const defaultFence = md.renderer.rules.fence.bind(md.renderer.rules);
  md.renderer.rules.fence = (tokens, idx, options, env, self) => {
    const token = tokens[idx];
    const info = token.info.trim();
    const parts = info.split(/\s+/);
    const lang = parts[0] && !parts[0].includes("=") ? parts[0] : "";
    const attrs = Object.fromEntries(
      parts
        .filter((p) => p.includes("="))
        .map((p) => {
          const [k, ...rest] = p.split("=");
          return [k, rest.join("=")];
        })
    );
    if (attrs.include) {
      token.content = resolveInclude({
        file: attrs.include,
        region: attrs.region,
        lines: attrs.lines,
        repoRoot,
      }) + "\n";
      token.info = lang; // keep only the language for highlight()
    }
    return defaultFence(tokens, idx, options, env, self);
  };

  // Heading ids via the shared slug (skip the leading H1).
  const tokens = md.parse(markdown, {});
  stripMarkdownLinks(tokens);
  let leadingH1Dropped = false;
  for (let i = 0; i < tokens.length; i++) {
    const t = tokens[i];
    if (t.type === "heading_open") {
      const inline = tokens[i + 1];
      const text = inline && inline.type === "inline" ? inline.content : "";
      if (t.tag === "h1" && !leadingH1Dropped) {
        // Drop the leading H1 open/inline/close triplet.
        tokens.splice(i, 3);
        leadingH1Dropped = true;
        i -= 1;
        continue;
      }
      t.attrSet("id", slugify(text));
    }
  }
  return md.renderer.render(tokens, md.options, {});
}
