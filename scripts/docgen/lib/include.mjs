import { readFileSync } from "node:fs";
import { join } from "node:path";

function dedent(lines) {
  const indents = lines
    .filter((l) => l.trim().length > 0)
    .map((l) => l.match(/^\s*/)[0].length);
  const min = indents.length ? Math.min(...indents) : 0;
  return lines.map((l) => l.slice(min));
}

export function resolveInclude({ file, region, lines, repoRoot }) {
  const abs = join(repoRoot, file);
  let raw;
  try {
    raw = readFileSync(abs, "utf8");
  } catch (e) {
    throw new Error(`include: cannot read file ${file}: ${e.message}`);
  }
  const all = raw.replace(/\n$/, "").split("\n");

  let selected;
  if (region) {
    const start = all.findIndex((l) => l.includes(`tag::${region}[]`));
    const end = all.findIndex((l) => l.includes(`end::${region}[]`));
    if (start === -1 || end === -1 || end <= start) {
      throw new Error(`include: region "${region}" not found (or malformed) in ${file}`);
    }
    selected = all.slice(start + 1, end);
  } else if (lines) {
    const m = /^(\d+)-(\d+)$/.exec(lines);
    if (!m) throw new Error(`include: bad lines spec "${lines}" for ${file}`);
    const a = Number(m[1]);
    const b = Number(m[2]);
    if (a < 1 || b < a || b > all.length) {
      throw new Error(`include: line range ${lines} out of bounds for ${file} (${all.length} lines)`);
    }
    selected = all.slice(a - 1, b);
  } else {
    selected = all;
  }
  return dedent(selected).join("\n");
}
