export function assemble(bodyHtml, { top, bottom }) {
  return `${top}\n${bodyHtml}\n${bottom}`;
}
